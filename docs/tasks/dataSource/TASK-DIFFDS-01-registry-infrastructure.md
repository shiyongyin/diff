# TASK-DIFFDS-01: DataSource 注册表基础设施

> **定位**：多数据源能力的基石——提供数据源注册、解析、**生命周期管理**和 **fail-fast 连接验证**。
> **状态**：待确认
> **依赖**：无

---

## 一、核心 ✏️设计时填

### 背景

当前 Diff 组件所有数据库操作共用 Spring 主 DataSource。需要支持源端/目标端指定不同数据库。本卡提供「数据源注册表 + 配置绑定 + 自动装配 + 生命周期管理」。

### 目标（DoD）

- [ ] 新增 `DiffDataSourceRegistry`，支持 `register(key, ds)` / `resolve(key)` / `contains(key)` / `registeredKeys()`
- [ ] `resolve(null)` 和 `resolve("primary")` 返回主数据源的 JdbcTemplate
- [ ] `resolve("不存在的key")` 抛 `IllegalArgumentException`
- [ ] `register("primary", ...)` 被拒绝（不可覆盖主数据源）
- [ ] `DiffDataSourceRegistry` 实现 `DisposableBean`，`destroy()` 时 close 所有已注册的 HikariDataSource（**F10 修复**）
- [ ] 新增 `DiffDataSourceProperties`，支持 `tenant-diff.datasources.{key}` 下的完整 HikariCP 参数（含 `minimumIdle`/`maxLifetime`/`readOnly`）（**F12/F14 修复**）
- [ ] 新增 `DiffDataSourceAutoConfiguration`，读取配置创建 HikariDataSource 并注册
- [ ] **启动时连接验证**：每个外部数据源注册后立即执行 `getConnection().close()` 验证连通性，失败则阻止应用启动（**F08 修复**）
- [ ] 新增 `DiffDataSourceRegistryTest` 覆盖以上行为
- [ ] 编译通过：`./mvnw -pl xaigendoc -am -DskipTests package`

### 重点分布

| 方向 | 权重 | 说明 |
|------|------|------|
| Registry 核心逻辑 | 高 | resolve 的正确性（null/primary/不存在的 key） |
| 配置绑定 | 中 | Properties → HikariDataSource 创建 |
| 可测试性 | 中 | 单测覆盖 fail-fast 和向后兼容 |

### 关键决策

| 决策点 | 选择 | 理由 | 否定的备选 |
|--------|------|------|-----------|
| JdbcTemplate 缓存策略 | ConcurrentHashMap 懒创建 | 数据源数量少（<10），简单高效 | 每次 new JdbcTemplate（浪费） |
| 连接池实现 | HikariCP | Spring Boot 默认，代码库已使用 | Druid（额外依赖） |
| 配置前缀 | `tenant-diff.datasources` | 与 `tenant-diff.standalone` 同域 | `spring.datasource.diff`（侵入全局） |

---

## 二、执行 ✏️设计时填

### 前置准备

无 DDL / 配置变更 / 依赖组件确认。

### 核心步骤

#### 步骤 1：创建 `DiffDataSourceProperties`

**文件**：`xaigendoc/src/main/java/com/digiwin/xai/gendoc/component/diff/standalone/datasource/DiffDataSourceProperties.java`

```java
package com.digiwin.xai.gendoc.component.diff.standalone.datasource;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diff 多数据源配置属性。
 *
 * <p>配置示例：</p>
 * <pre>
 * tenant-diff:
 *   datasources:
 *     erp-prod:
 *       url: jdbc:mysql://...
 *       username: xxx
 *       password: xxx
 * </pre>
 *
 * <p>{@code "primary"} 为保留 key，自动绑定 Spring 主 DataSource，无需手动配置。</p>
 */
@Data
@ConfigurationProperties(prefix = "tenant-diff")
public class DiffDataSourceProperties {

    private Map<String, DiffDataSourceConfig> datasources = new LinkedHashMap<>();

    @Data
    public static class DiffDataSourceConfig {
        private String url;
        private String username;
        private String password;
        private String driverClassName = "com.mysql.cj.jdbc.Driver";
        /** 最大连接数。Diff 对比为低频操作，默认 3 足够。 */
        private int maximumPoolSize = 3;
        /** 最小空闲连接数。低频使用建议小值，减少资源浪费。 */
        private int minimumIdle = 1;
        /** 连接超时（ms）。 */
        private long connectionTimeoutMs = 30000;
        /** 连接最大存活时间（ms）。跨网段时需低于防火墙/LB 的连接存活限制。 */
        private long maxLifetimeMs = 1800000;
        /** 空闲连接超时（ms）。 */
        private long idleTimeoutMs = 600000;
        /**
         * 是否只读。source 端数据源建议设为 true，
         * 防止因 bug 导致意外写入外部数据库。
         */
        private boolean readOnly = false;
    }
}
```

#### 步骤 2：创建 `DiffDataSourceRegistry`

**文件**：`xaigendoc/src/main/java/com/digiwin/xai/gendoc/component/diff/standalone/datasource/DiffDataSourceRegistry.java`

```java
package com.digiwin.xai.gendoc.component.diff.standalone.datasource;

import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Diff 组件多数据源注册表。
 *
 * <p>持有命名数据源并按 key 解析 {@link JdbcTemplate}。
 * {@code null} / {@code "primary"} 始终指向 Spring 主 DataSource。</p>
 */
public class DiffDataSourceRegistry implements DisposableBean {
    public static final String PRIMARY_KEY = "primary";

    private final DataSource primaryDataSource;
    private final Map<String, DataSource> registry = new ConcurrentHashMap<>();
    private final Map<String, JdbcTemplate> jdbcTemplateCache = new ConcurrentHashMap<>();

    public DiffDataSourceRegistry(DataSource primaryDataSource) {
        if (primaryDataSource == null) {
            throw new IllegalArgumentException("primaryDataSource must not be null");
        }
        this.primaryDataSource = primaryDataSource;
    }

    /**
     * 应用关闭时释放所有外部数据源的连接池。
     * 主数据源由 Spring 容器管理，不在此关闭。
     */
    @Override
    public void destroy() {
        registry.values().forEach(ds -> {
            if (ds instanceof HikariDataSource hikari) {
                log.info("关闭 Diff 数据源连接池: {}", hikari.getPoolName());
                hikari.close();
            }
        });
        registry.clear();
        jdbcTemplateCache.clear();
    }

    /** 注册命名数据源。"primary" 为保留 key，不可注册。 */
    public void register(String key, DataSource dataSource) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("dataSource key must not be blank");
        }
        if (PRIMARY_KEY.equals(key)) {
            throw new IllegalArgumentException("'" + PRIMARY_KEY + "' is reserved and cannot be registered");
        }
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        registry.put(key, dataSource);
    }

    /**
     * 按 key 解析 JdbcTemplate。
     *
     * @param dataSourceKey 数据源 key；null/"primary" 返回主数据源
     * @return 对应的 JdbcTemplate（懒创建并缓存）
     * @throws IllegalArgumentException key 未注册
     */
    public JdbcTemplate resolve(String dataSourceKey) {
        String effectiveKey = normalizeKey(dataSourceKey);
        return jdbcTemplateCache.computeIfAbsent(effectiveKey, k -> {
            DataSource ds = PRIMARY_KEY.equals(k) ? primaryDataSource : registry.get(k);
            if (ds == null) {
                throw new IllegalArgumentException("dataSourceKey '" + k + "' not registered");
            }
            return new JdbcTemplate(ds);
        });
    }

    public boolean contains(String dataSourceKey) {
        String effectiveKey = normalizeKey(dataSourceKey);
        return PRIMARY_KEY.equals(effectiveKey) || registry.containsKey(effectiveKey);
    }

    public Set<String> registeredKeys() {
        Set<String> keys = new HashSet<>(registry.keySet());
        keys.add(PRIMARY_KEY);
        return Collections.unmodifiableSet(keys);
    }

    private static String normalizeKey(String key) {
        return (key == null || key.isBlank()) ? PRIMARY_KEY : key.trim();
    }
}
```

#### 步骤 3：创建 `DiffDataSourceAutoConfiguration`

**文件**：`xaigendoc/src/main/java/com/digiwin/xai/gendoc/component/diff/standalone/datasource/DiffDataSourceAutoConfiguration.java`

```java
package com.digiwin.xai.gendoc.component.diff.standalone.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import javax.sql.DataSource;

/**
 * Diff 多数据源自动配置。
 *
 * <p>读取 {@code tenant-diff.datasources.*} 配置，创建额外数据源并注册到 {@link DiffDataSourceRegistry}。</p>
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "tenant-diff.standalone", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DiffDataSourceProperties.class)
public class DiffDataSourceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DiffDataSourceRegistry diffDataSourceRegistry(
            DataSource primaryDataSource,
            DiffDataSourceProperties properties) {
        DiffDataSourceRegistry registry = new DiffDataSourceRegistry(primaryDataSource);

        if (properties.getDatasources() != null) {
            properties.getDatasources().forEach((key, config) -> {
                if (DiffDataSourceRegistry.PRIMARY_KEY.equals(key)) {
                    log.warn("跳过 '{}' 数据源配置：primary 为保留 key", key);
                    return;
                }
                HikariDataSource ds = createDataSource(key, config);
                registry.register(key, ds);
                log.info("注册 Diff 数据源: key={}, url={}", key, config.getUrl());
            });
        }

        log.info("DiffDataSourceRegistry 初始化完成, 已注册数据源: {}", registry.registeredKeys());
        return registry;
    }

    private static HikariDataSource createDataSource(String key, DiffDataSourceProperties.DiffDataSourceConfig config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("diff-ds-" + key);
        hikari.setJdbcUrl(config.getUrl());
        hikari.setUsername(config.getUsername());
        hikari.setPassword(config.getPassword());
        hikari.setDriverClassName(config.getDriverClassName());
        hikari.setMaximumPoolSize(config.getMaximumPoolSize());
        hikari.setMinimumIdle(config.getMinimumIdle());
        hikari.setConnectionTimeout(config.getConnectionTimeoutMs());
        hikari.setMaxLifetime(config.getMaxLifetimeMs());
        hikari.setIdleTimeout(config.getIdleTimeoutMs());
        hikari.setReadOnly(config.isReadOnly());
        // fail-fast：强制启动时验证连接（F08 修复）
        hikari.setInitializationFailTimeout(1);
        return new HikariDataSource(hikari);
    }
}
```

#### 步骤 4：创建 `DiffDataSourceRegistryTest`

**文件**：`xaigendoc/src/test/java/com/digiwin/xai/gendoc/component/diff/standalone/datasource/DiffDataSourceRegistryTest.java`

```java
// 覆盖用例：
// 1. resolve(null) → 主数据源 JdbcTemplate
// 2. resolve("primary") → 主数据源 JdbcTemplate
// 3. resolve("已注册key") → 对应 JdbcTemplate
// 4. resolve("未注册key") → IllegalArgumentException
// 5. register("primary", ...) → IllegalArgumentException
// 6. register(null/blank, ...) → IllegalArgumentException
// 7. contains 检查
// 8. registeredKeys 包含 primary + 已注册 key
```

### 审核检查点

- [ ] CP-1: `resolve(null)` 返回主数据源 JdbcTemplate
- [ ] CP-2: `resolve("不存在")` 抛 `IllegalArgumentException`
- [ ] CP-3: `register("primary", ...)` 被拒绝
- [ ] CP-4: 不配置 `tenant-diff.datasources` 时 Registry 仅含 primary
- [ ] CP-5: 配置错误的 URL/密码时，**应用启动失败**并打印明确的数据源连接错误（fail-fast）
- [ ] CP-6: `DiffDataSourceRegistry.destroy()` 调用后，所有 HikariDataSource 已 close
- [ ] CP-7: source 端数据源配置 `readOnly: true` 时，执行 INSERT/UPDATE 操作被数据库拒绝

---

## 三、自省 ✏️设计完成后、实现前填

- [ ] **目标偏离**：本卡专注基础设施层，不涉及 Plugin/Apply/Service 层 — 未偏离
- [ ] **认知负担**：Registry 是简单 Map 封装，代码库已有同类先例（PluginRegistry/ApplySupportRegistry） — 合理
- [ ] **比例失调**：核心逻辑（resolve 正确性）占最大篇幅 — 符合权重
- [ ] **ROI**：3 个文件 + 1 个测试，解决多数据源核心基座 — 正向
- [ ] **洁癖检测**：无"更优雅但不解决实际问题"的改动
- [ ] **局部 vs 全局**：为 T02–T05 提供基础，不增加其他卡复杂度
- [ ] **过度设计**：未预留动态热加载/连接池监控等未来需求
- [ ] **生命周期**：DisposableBean 是 Spring 标准模式，不是过度设计（F10 修复）
- [ ] **fail-fast**：`initializationFailTimeout=1` 是 HikariCP 标准用法，确保启动时暴露配置错误（F08 修复）

**结论**：通过

---

## 四、反馈 ✏️实现过程中回填

### 偏差记录

| 偏差点 | 计划 | 实际 | 原因 |
|--------|------|------|------|
| — | — | — | — |

### 检查点结果

- [ ] CP-1:
- [ ] CP-2:
- [ ] CP-3:
- [ ] CP-4:

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
