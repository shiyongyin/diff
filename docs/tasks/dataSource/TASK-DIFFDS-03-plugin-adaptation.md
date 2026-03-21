# TASK-DIFFDS-03: Plugin 层适配（ApiDefinition）

> **定位**：让 ApiDefinitionStandalonePlugin 从 LoadOptions 中按 dataSourceKey 解析 JdbcTemplate，实现多数据源读取。
> **状态**：待确认
> **依赖**：← TASK-DIFFDS-01, TASK-DIFFDS-02 | → TASK-DIFFDS-05

---

## 一、核心 ✏️设计时填

### 背景

`ApiDefinitionStandalonePlugin` 当前通过构造函数注入单一 `JdbcTemplate`。T02 已在 `AbstractStandaloneBusinessPlugin` 提供 `resolveJdbcTemplate(LoadOptions)` 方法。本卡将 Plugin 的 JdbcTemplate 使用方式从"构造时绑定"改为"每次调用时解析"。

### 目标（DoD）

- [ ] `ApiDefinitionStandalonePlugin` 构造函数：`JdbcTemplate` → `DiffDataSourceRegistry`
- [ ] **（F05 修复）** `listBusinessKeys` 必须覆写**三参数版** `listBusinessKeys(Long tenantId, ScopeFilter filter, LoadOptions options)`，标注 `@Override`，内部调用 `resolveJdbcTemplate(options)` — **不是改旧的两参数版**（旧签名无 `LoadOptions` 参数，无法获取 `dataSourceKey`）
- [ ] `loadBusiness` 内部调用 `resolveJdbcTemplate(options)` 替代原 `this.jdbcTemplate`
- [ ] 旧签名 `listBusinessKeys(tenantId, filter)` 委托到新签名（传入空 LoadOptions），保持向后兼容
- [ ] 编译通过：`./mvnw -pl xaigendoc -am -DskipTests package`

### 重点分布

| 方向 | 权重 | 说明 |
|------|------|------|
| JdbcTemplate 替换正确性 | 高 | 所有 SQL 执行点都用 resolve 后的 JdbcTemplate |
| 向后兼容 | 中 | dataSourceKey=null 时行为与改动前完全一致 |
| 可读性 | 低 | 变量名从 `this.jdbcTemplate` 改为局部 `jdbc` |

### 关键决策

| 决策点 | 选择 | 理由 | 否定的备选 |
|--------|------|------|-----------|
| JdbcTemplate 解析粒度 | 方法入口处一次 resolve | 一个方法内所有 SQL 应使用同一连接源 | 每条 SQL 前 resolve（浪费且可能不一致） |

---

## 二、执行 ✏️设计时填

### 前置准备

依赖 T01 `DiffDataSourceRegistry` + T02 `AbstractStandaloneBusinessPlugin.resolveJdbcTemplate()` 已就绪。

### 核心步骤

#### 步骤 1：修改 `ApiDefinitionStandalonePlugin` 构造函数

**文件**：`xaigendoc/src/main/java/.../apidefinition/plugin/ApiDefinitionStandalonePlugin.java`

```java
// Before:
private final JdbcTemplate jdbcTemplate;
public ApiDefinitionStandalonePlugin(ObjectMapper objectMapper, JdbcTemplate jdbcTemplate, DictionaryService dictionaryService) {
    super(objectMapper);
    this.jdbcTemplate = jdbcTemplate;
    ...
}

// After:
// 删除 private final JdbcTemplate jdbcTemplate;
public ApiDefinitionStandalonePlugin(ObjectMapper objectMapper, DiffDataSourceRegistry dataSourceRegistry, DictionaryService dictionaryService) {
    super(objectMapper, dataSourceRegistry);
    this.dictionaryService = dictionaryService;
}
```

#### 步骤 2：重写 `listBusinessKeys` 新签名

```java
@Override
public List<String> listBusinessKeys(Long tenantId, ScopeFilter filter, LoadOptions options) {
    JdbcTemplate jdbc = resolveJdbcTemplate(options);
    // 原逻辑不变，所有 jdbcTemplate.queryForList → jdbc.queryForList
    ...
}

@Override
public List<String> listBusinessKeys(Long tenantId, ScopeFilter filter) {
    return listBusinessKeys(tenantId, filter, LoadOptions.builder().build());
}
```

#### 步骤 3：修改 `loadBusiness`

```java
@Override
public BusinessData loadBusiness(Long tenantId, String businessKey, LoadOptions options) {
    JdbcTemplate jdbc = resolveJdbcTemplate(options);
    // 所有内部方法传递 jdbc 参数（或在方法入口处赋值）
    ...
}
```

> **陷阱提示**：`loadBusiness` 内部调用了 `loadMainTable`、`loadApiDefs`、`loadFieldLibraryDefinitions`、`loadFieldLibraryItems` 4 个私有方法，都使用 `this.jdbcTemplate`。需要将局部 `jdbc` 变量传递到这些方法（或改为方法参数）。

推荐做法：为 4 个 private 方法统一新增 `JdbcTemplate jdbc` 参数，在 `loadBusiness` 入口处 resolve 一次后传入。

### 审核检查点

- [ ] CP-1: `loadBusiness` 中 4 个子方法均使用传入的 `jdbc` 而非类字段
- [ ] CP-2: `dataSourceKey=null` 时查询结果与改动前一致
- [ ] CP-3: 类中不再有 `private final JdbcTemplate jdbcTemplate` 字段

---

## 三、自省 ✏️设计完成后、实现前填

- [ ] **目标偏离**：Plugin 层适配，直接消费 T01/T02 的基础设施 — 未偏离
- [ ] **认知负担**：JdbcTemplate → resolve 是简单替换 — 合理
- [ ] **比例失调**：替换正确性占主体 — 符合权重
- [ ] **ROI**：1 个文件改动，打通 Plugin 多数据源读取 — 正向
- [ ] **洁癖检测**：4 个私有方法新增 jdbc 参数不是洁癖，是功能必要
- [ ] **局部 vs 全局**：不影响其他 Plugin 实现
- [ ] **过度设计**：无

**结论**：通过

---

## 四、反馈 ✏️实现过程中回填

### 偏差记录

| 偏差点 | 计划 | 实际 | 原因 |
|--------|------|------|------|

### 检查点结果

- [ ] CP-1:
- [ ] CP-2:
- [ ] CP-3:

---

## 五、总结 ✏️完成后回填

### 评分

| 维度 | 分数 | 证据 |
|------|------|------|
| 正确性 | /25 | |
| 完整性 | /25 | |
| 可维护性 | /25 | |
| 风险控制 | /25 | |

### Code-Review 回填

| 级别 | 编号 | 描述 | 文件:行号 | 处置 |
|------|------|------|-----------|------|
