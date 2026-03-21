# TASK-DIFFDS-04: Apply 层适配（含事务安全加固）

> **定位**：让 Apply/Rollback 执行器使用 Registry 解析的目标 JdbcTemplate 写入数据，**并解决跨数据源事务一致性和 LAST_INSERT_ID 可靠性问题**。
> **状态**：✅ 已完成
> **依赖**：← TASK-DIFFDS-01, TASK-DIFFDS-02 | → TASK-DIFFDS-05（与 T03 可并行）

---

## 一、核心 ✏️设计时填

### 背景

`StandaloneApplyExecutorCore` 当前通过构造函数绑定单一 JdbcTemplate 执行 INSERT/UPDATE/DELETE。多数据源场景下，Apply 的目标 JdbcTemplate 需要从 session 的 `optionsJson` 中解析 `targetDataSourceKey`。

**对抗评审发现的三个 P0 风险**：

1. **事务不一致**：`TenantDiffStandaloneApplyServiceImpl.execute(ApplyPlan)` 上的 `@Transactional` 使用 Spring 默认 `DataSourceTransactionManager`（绑定主数据源）。当 targetJdbc 指向外部数据源时，业务 SQL 实际在 autocommit 模式下执行，异常时无法回滚已执行的 SQL。
2. **LAST_INSERT_ID 不可靠**：当前代码用 `SELECT LAST_INSERT_ID()` 获取自增 ID。这依赖"INSERT 和 SELECT 在同一 MySQL Connection 上"。HikariCP 连接池可能在两次执行间切换 Connection，导致获取到错误的 ID。
3. **解析异常静默吞掉**：`resolveDataSourceKeyForDirection` 的 `catch (Exception) { return null; }` 会在 optionsJson 格式错误时静默回退到主数据源，导致 Apply 写入错误的数据库。

### 目标（DoD）

- [ ] `StandaloneApplyExecutorCore`：`execute()` 方法新增 `JdbcTemplate targetJdbc` 参数，删除构造函数中的 JdbcTemplate 字段
- [ ] **（F03 修复）** `StandaloneApplyExecutorCore`：`LAST_INSERT_ID()` 替换为 `KeyHolder` + `PreparedStatement.RETURN_GENERATED_KEYS`
- [ ] **（F07 修复）** `StandaloneApplyExecutorCore`：`execute()` 中的 `catch (Exception) { return result; }` 改为 `catch-throw`，抛出 `ApplyExecutionException`（或直接 rethrow `RuntimeException`），确保调用方的 `@Transactional` 能感知异常并回滚
- [ ] **（F02 修复）** `StandaloneApplyExecutorImpl`：注入 `DiffDataSourceRegistry`；当 targetDataSourceKey 非 null/primary 时，使用手动事务控制（`DataSourceTransactionManager` + `TransactionTemplate`/`TransactionStatus`）包裹 Core 执行
- [ ] **（F01 修复）** `StandaloneApplyExecutorImpl`：`resolveDataSourceKeyForDirection(session, direction)` 中 `catch (Exception)` 改为 `throw new IllegalStateException("无法解析 dataSourceKey", e)`，不静默回退
- [ ] `StandaloneBusinessDiffApplyExecutorImpl`：注入 `DiffDataSourceRegistry`，`execute()` 新增 `String targetDataSourceKey` 参数
- [ ] **（F06 修复）** 回滚安全：v1 回滚仅支持 target=主库方向；若 Apply 的 targetDataSourceKey 非 primary，标记为"不可回滚"
- [ ] **（F13 修复）** 可观测性：`actionHint`、`ApplyActionError`、日志中包含 `dataSourceKey` 信息
- [ ] 编译通过：`./mvnw -pl xaigendoc -am -DskipTests package`

### 重点分布

| 方向 | 权重 | 说明 |
|------|------|------|
| 跨数据源事务安全 | **最高** | targetJdbc 的事务必须被正确管理，异常时可回滚 |
| LAST_INSERT_ID → KeyHolder | 高 | 消除连接池切换导致的 ID 错误风险 |
| targetJdbc 一致性 | 高 | 同一个 Apply 事务内所有 SQL 使用同一 JdbcTemplate |
| 异常传播正确性 | 中 | Core 不吞异常，确保事务感知 |
| Session → dataSourceKey 解析 | 中 | 从 optionsJson 中提取 targetLoadOptions.dataSourceKey |
| 回滚安全约束 | 中 | v1 限制回滚方向 |
| 可观测性 | 低 | dataSourceKey 出现在日志和错误信息中 |

### 关键决策

| 决策点 | 选择 | 理由 | 否定的备选 |
|--------|------|------|-----------|
| JdbcTemplate 传递方式 | 方法参数传入 Core | Core 无状态，更灵活 | Core 构造时绑定（每个数据源一个 Core 实例——浪费） |
| targetDataSourceKey 来源 | session.optionsJson 中解析 | 与 Compare 时的 dataSourceKey 一致，无需额外持久化字段 | 新增 session 表字段（DDL 变更） |
| 自增 ID 获取方式 | KeyHolder + RETURN_GENERATED_KEYS | JDBC 标准方式，不依赖同一 Connection | LAST_INSERT_ID()（连接池不安全） |
| 跨数据源事务管理 | 手动 DataSourceTransactionManager | 精确控制 target 数据源的事务边界 | @Transactional 配合多 TransactionManager（侵入全局配置） |
| 解析失败处理 | throw IllegalStateException | 静默回退会导致数据写入错误的库 | catch return null（P0 安全漏洞） |
| 回滚方向 | v1 仅支持 target=primary | 回滚需读 apply_record，改造量大放二期 | 全面支持（scope creep） |

---

## 二、执行 ✏️设计时填

### 前置准备

依赖 T01 `DiffDataSourceRegistry` + T02 `LoadOptions.dataSourceKey` 已就绪。

### 核心步骤

#### 步骤 1：修改 `StandaloneApplyExecutorCore`（F03 + F07 修复）

**文件**：`xaigendoc/src/main/java/.../apply/StandaloneApplyExecutorCore.java`

**1a. 删除 JdbcTemplate 字段，改为方法参数**

```java
// Before:
private final JdbcTemplate jdbcTemplate;
public StandaloneApplyExecutorCore(StandaloneApplySupportRegistry supportRegistry, JdbcTemplate jdbcTemplate) { ... }
public ApplyResult execute(ApplyPlan plan, ApplyMode mode, Long targetTenantId, BusinessDiffLoader loader) { ... }

// After:
// 删除 jdbcTemplate 字段
public StandaloneApplyExecutorCore(StandaloneApplySupportRegistry supportRegistry) { ... }
public ApplyResult execute(ApplyPlan plan, ApplyMode mode, Long targetTenantId, BusinessDiffLoader loader, JdbcTemplate targetJdbc) { ... }
```

内部所有 `jdbcTemplate.update(...)` / `jdbcTemplate.queryForMap(...)` → `targetJdbc.update(...)` / `targetJdbc.queryForMap(...)`。

**1b. LAST_INSERT_ID() → KeyHolder**

```java
// Before（约 line 303-322）:
targetJdbc.update(insertSql, args);
Map<String, Object> idRow = targetJdbc.queryForMap("SELECT LAST_INSERT_ID() AS id");
Long newId = ((Number) idRow.get("id")).longValue();

// After:
KeyHolder keyHolder = new GeneratedKeyHolder();
targetJdbc.update(connection -> {
    PreparedStatement ps = connection.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS);
    // 绑定参数...
    for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
    }
    return ps;
}, keyHolder);
Long newId = Objects.requireNonNull(keyHolder.getKey(), "INSERT 未返回自增 ID").longValue();
```

> **优势**：`KeyHolder` 在 `PreparedStatement` 级别绑定，不依赖 Connection 复用，连接池安全。

**1c. catch-return → catch-throw**

```java
// Before（约 line 186-190）:
try {
    // ... 执行逻辑
} catch (Exception e) {
    log.error("Apply 执行异常", e);
    result.addError(...);
    return result;  // ← 吞掉异常，@Transactional 不会回滚
}

// After:
try {
    // ... 执行逻辑
} catch (Exception e) {
    log.error("Apply 执行异常, dataSourceKey={}", targetDataSourceKey, e);
    // 不吞异常，让调用方的事务管理器感知并回滚
    throw new ApplyExecutionException("Apply 执行失败: " + e.getMessage(), e, result);
}
```

> **新增**：`ApplyExecutionException extends RuntimeException`，携带已有的部分结果 `result`，供调用方在 catch 时提取错误信息写入 DB。

#### 步骤 2：修改 `StandaloneApplyExecutorImpl`（F01 + F02 修复）

**文件**：`xaigendoc/src/main/java/.../apply/StandaloneApplyExecutorImpl.java`

```java
// 新增注入
private final DiffDataSourceRegistry dataSourceRegistry;
private final ObjectMapper objectMapper;

@Override
public ApplyResult execute(ApplyPlan plan, ApplyMode mode) {
    ...
    // 从 session.optionsJson 按 Apply 方向解析写入端 dataSourceKey
    String targetDsKey = resolveDataSourceKeyForDirection(session, direction);
    JdbcTemplate targetJdbc = dataSourceRegistry.resolve(targetDsKey);

    // 判断是否需要手动事务管理
    if (isExternalDataSource(targetDsKey)) {
        return executeWithManualTransaction(plan, mode, targetTenantId, loader, targetJdbc, targetDsKey);
    } else {
        // 主数据源：依赖调用方（Service 层）的 @Transactional
        return core.execute(plan, mode, targetTenantId, loader, targetJdbc);
    }
}

/**
 * 外部数据源：手动管理事务。
 *
 * <p>因为 Spring 默认 TransactionManager 只绑定主数据源，
 * 外部数据源的 JdbcTemplate 执行不受 @Transactional 管控。
 * 此处创建临时 DataSourceTransactionManager 手动控制事务边界。</p>
 */
private ApplyResult executeWithManualTransaction(
        ApplyPlan plan, ApplyMode mode, Long targetTenantId,
        BusinessDiffLoader loader, JdbcTemplate targetJdbc, String targetDsKey) {

    DataSource targetDs = targetJdbc.getDataSource();
    DataSourceTransactionManager txManager = new DataSourceTransactionManager(targetDs);
    TransactionTemplate txTemplate = new TransactionTemplate(txManager);

    return txTemplate.execute(status -> {
        try {
            return core.execute(plan, mode, targetTenantId, loader, targetJdbc);
        } catch (ApplyExecutionException e) {
            status.setRollbackOnly();
            log.error("外部数据源 Apply 事务回滚, dataSourceKey={}", targetDsKey, e);
            throw e;
        }
    });
}

private static boolean isExternalDataSource(String key) {
    return key != null && !key.isBlank() && !DiffDataSourceRegistry.PRIMARY_KEY.equals(key.trim());
}

/**
 * 按 Apply 方向解析写入端 dataSourceKey。
 *
 * <p><b>（F01 修复）</b>解析失败直接抛异常，不静默回退到主数据源，
 * 防止数据写入错误的数据库。</p>
 */
private String resolveDataSourceKeyForDirection(TenantDiffSessionPo session, ApplyDirection direction) {
    if (session.getOptionsJson() == null || session.getOptionsJson().isBlank()) {
        return null; // 无配置 → 主数据源，正常路径
    }
    try {
        DiffSessionOptions options = objectMapper.readValue(
            session.getOptionsJson(), DiffSessionOptions.class);
        LoadOptions loadOptions = StandaloneLoadOptionsResolver.resolveForDirection(options, direction);
        return loadOptions == null ? null : loadOptions.getDataSourceKey();
    } catch (Exception e) {
        // F01 修复：不吞异常，不 fallback
        throw new IllegalStateException(
            "无法解析 session optionsJson 中的 dataSourceKey, sessionId="
            + session.getId() + ", direction=" + direction, e);
    }
}
```

#### 步骤 3：修改 `StandaloneBusinessDiffApplyExecutorImpl`

**文件**：`xaigendoc/src/main/java/.../apply/StandaloneBusinessDiffApplyExecutorImpl.java`

```java
// Before:
public StandaloneBusinessDiffApplyExecutorImpl(StandaloneApplySupportRegistry supportRegistry, JdbcTemplate jdbcTemplate) {
    this.core = new StandaloneApplyExecutorCore(supportRegistry, jdbcTemplate);
}

// After:
private final DiffDataSourceRegistry dataSourceRegistry;

public StandaloneBusinessDiffApplyExecutorImpl(StandaloneApplySupportRegistry supportRegistry, DiffDataSourceRegistry dataSourceRegistry) {
    this.core = new StandaloneApplyExecutorCore(supportRegistry);
    this.dataSourceRegistry = dataSourceRegistry;
}

@Override
public ApplyResult execute(Long targetTenantId, ApplyPlan plan, List<BusinessDiff> diffs, ApplyMode mode) {
    // 默认用主数据源（回滚场景 v1 仅支持主库）
    return execute(targetTenantId, plan, diffs, mode, null);
}

public ApplyResult execute(Long targetTenantId, ApplyPlan plan, List<BusinessDiff> diffs, ApplyMode mode, String targetDataSourceKey) {
    ...
    JdbcTemplate targetJdbc = dataSourceRegistry.resolve(targetDataSourceKey);
    return core.execute(plan, mode, targetTenantId, loader, targetJdbc);
}
```

#### 步骤 4：新增 `ApplyExecutionException`

**文件**：`xaigendoc/src/main/java/.../apply/ApplyExecutionException.java`

```java
/**
 * Apply 执行过程中的异常。
 *
 * <p>携带已执行的部分结果 {@link ApplyResult}，供调用方在 catch 时提取错误信息。</p>
 */
public class ApplyExecutionException extends RuntimeException {
    private final ApplyResult partialResult;

    public ApplyExecutionException(String message, Throwable cause, ApplyResult partialResult) {
        super(message, cause);
        this.partialResult = partialResult;
    }

    public ApplyResult getPartialResult() {
        return partialResult;
    }
}
```

#### 步骤 5：回滚安全约束（F06 修复）

**文件**：`xaigendoc/src/main/java/.../service/impl/TenantDiffStandaloneRollbackServiceImpl.java`

在回滚逻辑入口处增加校验：

```java
/**
 * v1 回滚仅支持 target=主库方向。
 *
 * <p>如果原始 Apply 的 targetDataSourceKey 指向外部数据源，
 * 则该 session 标记为"不可回滚"。二期将通过 apply_record
 * 追踪 targetDataSourceKey 来支持外部数据源回滚。</p>
 */
private void validateRollbackDataSourceSupport(TenantDiffSessionPo session, ApplyDirection direction) {
    DiffSessionOptions options = objectMapper.readValue(session.getOptionsJson(), DiffSessionOptions.class);
    LoadOptions applyTargetOptions = StandaloneLoadOptionsResolver.resolveForDirection(options, direction);
    String dsKey = applyTargetOptions == null ? null : applyTargetOptions.getDataSourceKey();
    if (dsKey != null && !dsKey.isBlank() && !DiffDataSourceRegistry.PRIMARY_KEY.equals(dsKey.trim())) {
        throw new UnsupportedOperationException(
            "v1 回滚暂不支持外部数据源 target (dataSourceKey=" + dsKey + ")，请联系管理员手动处理");
    }
}
```

#### 步骤 6：可观测性增强（F13 修复）

在以下位置增加 `dataSourceKey` 信息：

1. **actionHint**：`"[ds:" + targetDsKey + "] INSERT INTO ..."` 前缀
2. **ApplyActionError**：新增 `dataSourceKey` 字段（或在 message 中包含）
3. **日志**：所有 Apply 相关日志包含 `dataSourceKey={}`

```java
// 日志示例
log.info("Apply 开始, sessionId={}, targetDataSourceKey={}, mode={}", sessionId, targetDsKey, mode);
log.info("Apply 完成, sessionId={}, targetDataSourceKey={}, 成功={}, 失败={}", sessionId, targetDsKey, successCount, failCount);
```

### 审核检查点

- [ ] CP-1: `StandaloneApplyExecutorCore` 不再持有 JdbcTemplate 字段
- [ ] CP-2: **（F03）** `LAST_INSERT_ID()` 已替换为 `KeyHolder` + `RETURN_GENERATED_KEYS`
- [ ] CP-3: **（F02）** 外部数据源 Apply 使用手动事务控制（`TransactionTemplate`），而非依赖 `@Transactional`
- [ ] CP-4: **（F01）** `resolveDataSourceKeyForDirection` 解析失败时抛异常，不静默 fallback
- [ ] CP-5: **（F07）** Core 的 `execute()` 方法中不存在 `catch (Exception) { return result; }` 模式
- [ ] CP-6: `dataSourceKey=null` 时 Apply 使用主数据源，行为与改动前一致
- [ ] CP-7: **（F06）** 回滚入口校验：targetDataSourceKey 为外部数据源时拒绝回滚
- [ ] CP-8: **（F13）** Apply 日志中包含 dataSourceKey 信息

---

## 三、自省 ✏️设计完成后、实现前填

- [ ] **目标偏离**：Apply 层适配 + 事务安全加固 — 未偏离，事务和 ID 可靠性是多数据源的必要前提
- [ ] **认知负担**：手动事务管理增加了复杂度，但这是跨数据源的标准做法 — 合理
- [ ] **比例失调**：事务安全（F02）和 KeyHolder（F03）占最大篇幅 — 符合"最高"权重
- [ ] **ROI**：4 个文件改动 + 1 新增，彻底打通 Apply 多数据源写入 — 正向
- [ ] **洁癖检测**：
  - 回滚场景暂用主数据源（实际需求驱动，不提前扩展） ✓
  - `ApplyExecutionException` 不是洁癖，是功能必要（携带部分结果） ✓
- [ ] **局部 vs 全局**：不影响 Compare 流程，不修改全局 TransactionManager 配置
- [ ] **过度设计**：回滚的多数据源暂不实现，v1 直接拒绝 — 简单直接
- [ ] **风险**：手动事务管理需 100% 覆盖 commit/rollback 路径，否则连接泄漏 — 通过 `TransactionTemplate` 而非手动 `getTransaction/commit/rollback` 降低此风险

**结论**：通过

---

## 四、反馈 ✏️实现过程中回填

### 偏差记录

| 偏差点 | 计划 | 实际 | 原因 |
|--------|------|------|------|
| F06 回滚校验 | 独立 `validateRollbackSupport()` 方法 | `RollbackServiceImpl.validateRollbackDataSourceSupport()` 显式校验 + `BusinessDiffApplyExecutorImpl` 默认传 `null` key | 对抗评审发现仅靠默认 null 不够显式，补充了 `validateRollbackDataSourceSupport()` 在回滚入口显式拒绝外部数据源 target |
| F13 actionHint 前缀 | `[ds:key] INSERT INTO ...` | 仅在日志中包含 dataSourceKey | actionHint 拼接改动侵入性大且用处有限，日志已足够排障 |

### 检查点结果

- [x] CP-1: `StandaloneApplyExecutorCore` 不再持有 `jdbcTemplate` 字段，Grep 确认无引用
- [x] CP-2: INSERT 使用 `KeyHolder` + `RETURN_GENERATED_KEYS`，Grep 确认无 `LAST_INSERT_ID`
- [x] CP-3: 外部数据源 `executeWithManualTransaction()` 用 `DataSourceTransactionManager` + `TransactionTemplate`
- [x] CP-4: `resolveDataSourceKeyForDirection()` 解析异常时 throw `IllegalStateException`，不 fallback（对抗评审后重命名，按方向路由）
- [x] CP-5: Core `execute()` 中 catch 后抛 `ApplyExecutionException`，不 return
- [x] CP-6: `dataSourceKey=null` → `registry.resolve(null)` → 主数据源 `JdbcTemplate`，向后兼容
- [x] CP-7: 回滚入口 `RollbackServiceImpl.validateRollbackDataSourceSupport()` 显式拒绝外部数据源 target；执行端 `BusinessDiffApplyExecutor.execute(4-param)` 默认 null → 主库
- [x] CP-8: `StandaloneApplyExecutorImpl` 日志包含 `targetDataSourceKey={}`

---

## 五、总结 ✏️完成后回填

### 评分

| 维度 | 分数 | 证据 |
|------|------|------|
| 正确性 | 24/25 | F01/F02/F03/F07 全部修复；KeyHolder 替换 LAST_INSERT_ID；手动事务管理外部数据源 |
| 完整性 | 24/25 | 8 个 CP 全部通过；F06 增加回滚入口显式校验（A_TO_B/B_TO_A 均覆盖）；F13 actionHint 前缀简化为日志 |
| 可维护性 | 24/25 | Core 无状态化（1-param 构造）；JdbcTemplate 由调用方传入，职责清晰 |
| 风险控制 | 24/25 | ApplyExecutionException 确保 @Transactional 回滚；外部数据源用 TransactionTemplate 隔离 |

### Code-Review 回填

| 级别 | 编号 | 描述 | 文件:行号 | 处置 |
|------|------|------|-----------|------|
| 注意 | N1 | `resolveDataSourceKeyForDirection` 中 `resolveForDirection()` 可能返回 null，需防御性处理 | StandaloneApplyExecutorImpl:169-177 | 已加 null 安全判断 `loadOptions == null ? null : loadOptions.getDataSourceKey()` |
| 注意 | N2 | 手动 TransactionManager 每次 Apply 新建实例，高频场景可考虑缓存 | StandaloneApplyExecutorImpl:137 | 二期优化项，当前频率低可接受 |
| **P0** | CR-1 | **B_TO_A 方向数据源路由错误**：`resolveTargetDataSourceKey` 始终调用 `resolveTarget()`，B_TO_A 时写入端应为 source | StandaloneApplyExecutorImpl:169 | 重命名为 `resolveDataSourceKeyForDirection()`，使用 `resolveForDirection(options, direction)` |
| **P0** | CR-2 | **回滚缺少外部数据源拦截**：回滚入口无 guard，外部 DS 回滚会走错库 | TenantDiffStandaloneRollbackServiceImpl:135 | 新增 `validateRollbackDataSourceSupport()` 显式拒绝 |
| P1 | CR-3 | Registry `register()` 未 trim key，且允许静默覆盖 | DiffDataSourceRegistry:58-72 | 补 `key.trim()` + 重复 key 抛 `IllegalStateException` |
| P2 | CR-4 | `isExternalDataSource()` 未 trim key，`" primary "` 误判为外部数据源 | StandaloneApplyExecutorImpl:186 | 补 `key.trim()` |
