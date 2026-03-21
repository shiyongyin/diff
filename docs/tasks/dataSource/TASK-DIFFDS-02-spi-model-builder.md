# TASK-DIFFDS-02: SPI 层 + ModelBuilder 适配

> **定位**：让 dataSourceKey 能从 LoadOptions 流经 Plugin SPI 到 ModelBuilder，为 T03/T04 提供传输通道。
> **状态**：待确认
> **依赖**：← TASK-DIFFDS-01

---

## 一、核心 ✏️设计时填

### 背景

`LoadOptions` 是 Plugin 加载数据时的配置载体，已有 per-side 解析管线（`DiffSessionOptions.sourceLoadOptions/targetLoadOptions`）。本卡为 `LoadOptions` 新增 `dataSourceKey` 字段，并修改 `StandaloneBusinessTypePlugin` 接口让 `listBusinessKeys` 也能接收 `LoadOptions`（向后兼容 default 方法）。

### 目标（DoD）

- [ ] `LoadOptions` 新增 `dataSourceKey` 字段（String，nullable）
- [ ] `StandaloneBusinessTypePlugin` 新增 `listBusinessKeys(tenantId, filter, options)` default 方法
- [ ] `AbstractStandaloneBusinessPlugin` 新增 `resolveJdbcTemplate(LoadOptions)` 辅助方法，依赖 `DiffDataSourceRegistry`
- [ ] `AbstractStandaloneBusinessPlugin` 构造函数新增 `DiffDataSourceRegistry` 参数
- [ ] `StandaloneTenantModelBuilder.buildWithWarnings()` 调用新签名 `listBusinessKeys(tenantId, filter, options)`
- [ ] 编译通过：`./mvnw -pl xaigendoc -am -DskipTests package`

### 重点分布

| 方向 | 权重 | 说明 |
|------|------|------|
| SPI 接口向后兼容 | 高 | default 方法确保已有插件不需改动 |
| AbstractPlugin 基类改造 | 中 | resolveJdbcTemplate 辅助方法 |
| ModelBuilder 调用点适配 | 低 | 一行调用改动 |

### 关键决策

| 决策点 | 选择 | 理由 | 否定的备选 |
|--------|------|------|-----------|
| dataSourceKey 放在 LoadOptions vs ScopeFilter | LoadOptions | ScopeFilter 在 scope 中是共享的（source/target 同一个），不适合放 per-side 字段 | ScopeFilter（共享冲突） |
| 接口变更方式 | default 方法 | 向后兼容，未启用的 Plugin（Instruction/ApiTemplate）无需改动 | 直接改签名（破坏兼容） |

---

## 二、执行 ✏️设计时填

### 前置准备

依赖 T01 中 `DiffDataSourceRegistry` 已就绪。

### 核心步骤

#### 步骤 1：修改 `LoadOptions`

**文件**：`xaigendoc/src/main/java/com/digiwin/xai/gendoc/component/diff/spi/LoadOptions.java`

新增字段：

```java
/**
 * 数据源 key。null/"primary" 表示使用 Spring 主数据源。
 *
 * <p>用于多数据源场景，插件据此从 {@code DiffDataSourceRegistry} 解析 JdbcTemplate。</p>
 */
private String dataSourceKey;
```

#### 步骤 2：修改 `StandaloneBusinessTypePlugin`

**文件**：`xaigendoc/src/main/java/com/digiwin/xai/gendoc/component/diff/standalone/plugin/StandaloneBusinessTypePlugin.java`

新增 default 方法：

```java
/**
 * 列出业务键（支持 LoadOptions 传递 dataSourceKey）。
 *
 * <p>默认实现委托到不含 LoadOptions 的旧签名，保持向后兼容。</p>
 */
default List<String> listBusinessKeys(Long tenantId, ScopeFilter filter, LoadOptions options) {
    return listBusinessKeys(tenantId, filter);
}
```

#### 步骤 3：修改 `AbstractStandaloneBusinessPlugin`

**文件**：`xaigendoc/src/main/java/com/digiwin/xai/gendoc/component/diff/standalone/plugin/AbstractStandaloneBusinessPlugin.java`

- 新增 `DiffDataSourceRegistry` 字段
- 新增构造函数重载（保留无 Registry 的旧构造函数）
- 新增 `resolveJdbcTemplate(LoadOptions)` 方法

```java
protected final DiffDataSourceRegistry dataSourceRegistry;

protected AbstractStandaloneBusinessPlugin(ObjectMapper objectMapper, DiffDataSourceRegistry dataSourceRegistry) {
    this.objectMapper = objectMapper;
    this.dataSourceRegistry = dataSourceRegistry;
}

/**
 * 根据 LoadOptions 中的 dataSourceKey 解析 JdbcTemplate。
 */
protected JdbcTemplate resolveJdbcTemplate(LoadOptions options) {
    if (dataSourceRegistry == null) {
        throw new IllegalStateException("dataSourceRegistry is not configured");
    }
    String key = options == null ? null : options.getDataSourceKey();
    return dataSourceRegistry.resolve(key);
}
```

> **陷阱提示**：保留旧的无参/单参构造函数（`dataSourceRegistry = null`），避免破坏编译。

#### 步骤 4：修改 `StandaloneTenantModelBuilder`

**文件**：`xaigendoc/src/main/java/com/digiwin/xai/gendoc/component/diff/standalone/model/StandaloneTenantModelBuilder.java`

改一行调用：

```java
// Before:
keys = plugin.listBusinessKeys(tenantId, scope.getFilter());
// After:
keys = plugin.listBusinessKeys(tenantId, scope.getFilter(), effectiveOptions);
```

### 审核检查点

- [ ] CP-1: `StandaloneBusinessTypePlugin` 旧签名 `listBusinessKeys(tenantId, filter)` 仍可编译
- [ ] CP-2: `AbstractStandaloneBusinessPlugin` 无参构造函数仍存在
- [ ] CP-3: `StandaloneTenantModelBuilder` 传递 LoadOptions 到 listBusinessKeys
- [ ] CP-4: **（F04 修复）** 确认 `TenantDiffStandaloneServiceImpl.doCompare()` 中两次 `buildWithWarnings` 调用分别传入了 `sourceLoadOptions`（含 sourceDataSourceKey）和 `targetLoadOptions`（含 targetDataSourceKey）。如果现有代码已通过 `StandaloneLoadOptionsResolver.resolveSource/resolveTarget` 实现 per-side 传入，则无需改动，在此标记 ✓ 即可。如果未实现，需在本卡补充改动

---

## 三、自省 ✏️设计完成后、实现前填

- [ ] **目标偏离**：SPI 通道适配，为 T03/T04 提供基础 — 未偏离
- [ ] **认知负担**：default 方法是 Java 8+ 标准做法 — 合理
- [ ] **比例失调**：SPI 接口兼容性占最大篇幅 — 符合权重
- [ ] **ROI**：4 个文件小改动，打通 dataSourceKey 传输通道 — 正向
- [ ] **洁癖检测**：无
- [ ] **局部 vs 全局**：为 T03/T04 提供基础，不增加复杂度
- [ ] **过度设计**：未引入泛型/抽象工厂等

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
