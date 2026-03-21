# Tenant-Diff Starter 化演进方案

> **目标**：将 tenant-diff 打造为开箱即用的 Spring Boot Starter。业务方在 Compare 场景下只需引入依赖 + 实现 Plugin；若要启用 Apply，还需注册对应的 `BusinessApplySupport`。
>
> **文档版本**：v1.3 | 2026-03-08

---

## 目录

1. [现状分析](#1-现状分析)
2. [ROI 评估与分期决策](#2-roi-评估与分期决策)
3. [目标架构](#3-目标架构)
4. [本期实施：Tier 1 + Tier 2 精选（~4天）](#4-本期实施tier-1--tier-2-精选4天)
5. [后续迭代：需求驱动](#5-后续迭代需求驱动)
6. [附录 A：完整技术方案（所有项目）](#6-附录-a完整技术方案所有项目)
7. [附录 B：业务方接入最终效果](#7-附录-b业务方接入最终效果)

---

## 1. 现状分析

### 1.1 当前模块结构

```
tenant-diff/
├── tenant-diff-core                  # 纯领域模型 + 比对引擎（无 Spring 依赖）
├── tenant-diff-standalone            # Spring Boot 运行时 + REST API + 持久化
├── tenant-diff-spring-boot-starter   # 仅依赖聚合，无自有代码
└── tenant-diff-demo                  # 示例应用
```

### 1.2 已有能力

| 能力 | 状态 | 说明 |
|------|------|------|
| 多租户比对引擎 | ✅ 完成 | businessKey 对齐、4层diff、指纹优化 |
| Apply 执行 | ✅ 完成 | 计划构建、方向反转、依赖排序、tenantsid 安全 |
| 快照 + 回滚 | ✅ 完成 | Apply 前快照、回滚恢复 |
| Plugin SPI | ✅ 完成 | StandaloneBusinessTypePlugin + BusinessApplySupport |
| REST API | ✅ 完成 | Session / Apply / Rollback 完整接口 |
| 多数据源 | ✅ 完成 | DiffDataSourceRegistry 注册 + 路由 |
| 自动配置 | ⚠️ 部分 | AutoConfiguration.imports 已有，但缺少分层 |

### 1.3 核心差距

| 问题 | 影响 | 原始优先级 |
|------|------|--------|
| 框架建表脚本未随 Starter 分发 | 业务方不知道需要哪些表，启动即失败 | P0 |
| 不支持复用宿主数据源 | 强制要求独立配置数据源，增加接入成本 | P0 |
| 自动配置未分层 | Web/持久化/核心绑死，无法按需裁剪 | P0 |
| 缺少配置元数据 | IDE 无属性提示 | P1 |
| Plugin 样板代码过多 | 简单单表业务也要写 ~200 行代码 | P1 |
| MapperScan 可能与宿主冲突 | 扫描范围过大影响宿主项目 | P1 |
| 无事件/回调机制 | 无法在比对/Apply 前后插入业务逻辑 | P2 |
| 持久化层硬绑 MyBatis-Plus | 与 JPA 项目不兼容 | P2 |
| SQL 无数据库方言支持 | 仅 MySQL 兼容 | P3 |
| 无测试辅助工具 | 业务方写 Plugin 单测困难 | P3 |
| 未发布到 Maven 仓库 | 无法通过坐标引入 | P3 |

---

## 2. ROI 评估与分期决策

> **核心原则**：先让第一个业务方能用且好用，剩下的全部等真实需求驱动，避免过度设计。

### 2.1 ROI 总览（按投资回报率排序）

#### Tier 1：不做就没法用 — 本期必做

| 项目 | 投入 | 回报 | ROI | 理由 |
|------|------|------|-----|------|
| **数据源复用宿主 DataSource** | 0.5天 | 极高 | ★★★★★ | 改几行代码的事，但不做业务方就要重复配数据源，体验极差 |
| **DDL 脚本随 Starter 分发 + 自动建表** | 1天 | 极高 | ★★★★★ | 把 demo 里的 schema.sql 搬到 standalone 资源目录，加个初始化器。不做业务方连表都建不上 |
| **MapperScan 精确限定** | 0.5天 | 高 | ★★★★☆ | 不隔离大概率和宿主冲突，是个阻塞性 bug。改动量很小 |

#### Tier 2：ROI 高但需看时机 — 精选本期做

| 项目 | 投入 | 回报 | ROI | 本期？ | 理由 |
|------|------|------|-----|--------|------|
| **SimpleTablePlugin 基类** | 2天 | 高 | ★★★★☆ | **做** | 第一批接入方大概率有单表场景，200行→15行的降幅是 Starter 核心卖点 |
| **配置元数据（IDE提示）** | 0.5天 | 中 | ★★★☆☆ | **顺手做** | pom 已引 processor，补全注释和嵌套类即可，半天的事体验提升明显 |
| **配置分层（Web/持久化可关）** | 1.5天 | 中高 | ★★★☆☆ | **不做** | 现阶段接入方大概率全功能使用，等有人只要引擎时再拆，否则是过度设计 |
| **MultiTablePlugin 基类** | 2天 | 中 | ★★★☆☆ | **看需求** | 如果第一批接入的都是多表业务就做，否则先用现有 AbstractPlugin 手写够用 |

#### Tier 3：现在做是浪费 — 需求驱动再做

| 项目 | 投入 | ROI | 最佳时机 | 触发条件 |
|------|------|-----|----------|----------|
| **事件/回调机制** | 2天 | ★★☆☆☆ | 第 2-3 个业务方接入时 | 出现真实的审批/通知需求 |
| **接入文档** | 2天 | ★★☆☆☆ | 第一个外部团队接入前 | 非本团队成员需要接入 |
| **Repository 抽象** | 3-5天 | ★★☆☆☆ | 有 JPA 项目要接入时 | 业务方明确不用 MyBatis-Plus |
| **SQL 方言支持** | 2天 | ★☆☆☆☆ | 有 PG/Oracle 项目接入时 | MySQL+H2 已覆盖 90% 场景 |
| **测试辅助模块** | 3天 | ★☆☆☆☆ | 3+ 个业务方接入后 | 共性测试模式沉淀出来后 |
| **Maven 发布** | 1天 | ★☆☆☆☆ | 对外推广前 | 内部 install 或私服够用 |

### 2.2 本期实施清单

```
本期做（~4天）：
  ✅ 数据源复用宿主 DataSource        0.5天    Tier1-必做
  ✅ DDL 脚本分发 + 自动建表          1天      Tier1-必做
  ✅ MapperScan 隔离                  0.5天    Tier1-必做
  ✅ SimpleTablePlugin 基类           2天      Tier2-核心卖点
  ✅ 配置元数据（顺手）               0.5天    Tier2-顺手做

本期不做：
  ❌ 配置分层     → 等有人只要引擎不要Web时再拆
  ❌ MultiTablePlugin → 等多表需求出现
  ❌ 事件机制     → 等真实审批需求驱动
  ❌ Repository抽象 → 等JPA项目出现
  ❌ SQL方言      → 等非MySQL项目出现
  ❌ 测试模块     → 等模式沉淀后再抽象
  ❌ Maven发布    → 等对外推广时
  ❌ 完整文档     → 等外部团队接入时
```

### 2.3 决策逻辑说明

**为什么"配置分层"不做？**
- 投入 1.5 天，涉及拆分 Configuration 类、新增属性、测试各种组合
- 当前 0.x 阶段，第一批接入方几乎必然是全功能使用（比对 + 持久化 + REST API）
- 过早拆分反而增加维护成本，且拆分点可能不准确（等真实需求才知道怎么拆最合理）
- **触发条件**：有业务方明确说"我只要引擎，不需要 Web 和持久化"

**为什么"事件机制"不做？**
- 现在设计出来的 Event 类大概率要改（不知道业务方真正需要在哪些节点介入）
- 提前设计的 `veto()` 审批拦截机制可能过于简单或过于复杂
- **触发条件**：有业务方说"Apply 之前我需要审批流"或"比对完成我要发通知"

**为什么"SimpleTablePlugin"本期要做？**
- 这是 Starter 的**核心价值主张**：200 行 → 15 行
- 如果业务方接入还是要写 200 行样板代码，和直接复制 demo 代码没本质区别
- 这个抽象比较稳定，不太需要后续推翻重做

---

## 3. 目标架构

### 3.1 模块演进

```
tenant-diff/
├── tenant-diff-core                          # [不变] 纯领域模型 + 比对引擎
├── tenant-diff-standalone                    # [重构] 运行时基础设施（持久化、Apply执行、服务层）
├── tenant-diff-spring-boot-starter           # [增强] 自动配置 + 条件装配
│   └── src/main/resources/
│       ├── META-INF/spring/AutoConfiguration.imports
│       ├── META-INF/spring-configuration-metadata.json  (生成)
│       └── META-INF/tenant-diff/
│           ├── schema-mysql.sql
│           ├── schema-h2.sql
│           └── schema-postgresql.sql
├── tenant-diff-spring-boot-autoconfigure     # [新增] 自动配置模块（可选，见方案A/B）
├── tenant-diff-test                          # [新增] 测试辅助
└── tenant-diff-demo                          # [不变] 示例应用
```

### 3.2 分层配置架构

```
                    ┌─────────────────────┐
                    │  业务方 Application  │
                    │ (Plugin + 可选 Apply)│
                    └──────────┬──────────┘
                               │ 引入
                    ┌──────────▼──────────┐
                    │  spring-boot-starter │
                    │  (依赖 + 自动配置)   │
                    └──────────┬──────────┘
                               │
            ┌──────────────────┼──────────────────┐
            │                  │                  │
   ┌────────▼────────┐ ┌──────▼──────┐ ┌────────▼────────┐
   │ Web 层 (可选)    │ │ 持久化 (可选)│ │ 核心层 (必选)    │
   │ REST Controller │ │ Session     │ │ DiffEngine      │
   │ ExceptionHandler│ │ Snapshot    │ │ PlanBuilder     │
   │                 │ │ ApplyRecord │ │ PluginRegistry  │
   └─────────────────┘ └─────────────┘ └─────────────────┘
```

### 3.3 业务方接入视角

```java
// Compare-only：完成前 3 件事即可启动并做比对
// Compare + Apply：还需补第 4 件事

// 1. 引入依赖
// <dependency>
//   <groupId>com.diff</groupId>
//   <artifactId>tenant-diff-spring-boot-starter</artifactId>
//   <version>1.0.0</version>
// </dependency>

// 2. 配置开关
// tenant-diff.standalone.enabled=true

// 3. 实现 Plugin（比对侧核心业务代码）
@Component
public class ContractPlugin extends SimpleTablePlugin {
    @Override
    public String businessType() { return "CONTRACT"; }

    @Override
    protected String tableName() { return "biz_contract"; }

    @Override
    protected String businessKeyColumn() { return "contract_code"; }
}

// 4. 如需 Apply，注册配套的 BusinessApplySupport
@Bean
public BusinessApplySupport contractApplySupport(ContractPlugin plugin, ObjectMapper objectMapper) {
    return new SimpleTableApplySupport(plugin.businessType(), objectMapper, plugin.schema());
}
```

---

## 4. 本期实施：Tier 1 + Tier 2 精选（~4天）

> **目标**：业务方引入 Starter 后能正常启动和使用；单表 Compare 场景 15 行代码搞定，如需 Apply 再额外注册 1 个 `BusinessApplySupport` Bean。

### 4.1 框架建表脚本随 Starter 分发

#### 4.1.1 问题

当前框架所需的 5 张表（session/result/apply_record/snapshot/decision_record）的 DDL 只存在于 demo 的 `schema.sql` 中。业务方引入 Starter 后无法自动建表，也不知道需要哪些表。

#### 4.1.2 方案

**将建表脚本打包到 standalone 或 starter 的 classpath 资源中**，并提供三种初始化模式：

**目录结构**：

```
tenant-diff-standalone/src/main/resources/
└── META-INF/tenant-diff/
    ├── schema-mysql.sql       # MySQL DDL
    ├── schema-h2.sql          # H2 DDL（测试用）
    └── schema-postgresql.sql  # PostgreSQL DDL（预留）
```

**`schema-mysql.sql` 的 SSOT 路径**：

- `tenant-diff-standalone/src/main/resources/META-INF/tenant-diff/schema-mysql.sql`
- 涉及手工建表、排障、字段含义时，以上资源文件为唯一准绳；文档不再复制整段 SQL，避免与实现漂移。
- 当前脚本定义的 5 张表及关键列如下：

| 表 | 关键列（当前实现） | 说明 |
|------|--------------------|------|
| `xai_tenant_diff_session` | `session_key`、`source_tenant_id`、`target_tenant_id`、`scope_json`、`options_json`、`status`、`error_msg`、`version`、`created_at`、`finished_at` | Diff 会话主记录 |
| `xai_tenant_diff_result` | `session_id`、`business_type`、`business_table`、`business_key`、`business_name`、`diff_type`、`statistics_json`、`diff_json`、`created_at` | 业务级 diff 结果 |
| `xai_tenant_diff_apply_record` | `apply_key`、`session_id`、`direction`、`plan_json`、`status`、`error_msg`、`version`、`started_at`、`finished_at` | Apply 执行审计 |
| `xai_tenant_diff_snapshot` | `apply_id`、`session_id`、`side`、`business_type`、`business_table`、`business_key`、`snapshot_json`、`created_at` | 回滚快照 |
| `xai_tenant_diff_decision_record` | `session_id`、`business_type`、`business_key`、`table_name`、`record_business_key`、`diff_type`、`decision`、`decision_reason`、`decision_time`、`execution_status`、`execution_time`、`error_msg`、`apply_id`、`created_at`、`updated_at` | 人工决策与执行审计 |

#### 4.1.3 初始化策略

提供新的配置属性控制建表行为：

```yaml
tenant-diff:
  standalone:
    enabled: true
    schema:
      # 自动初始化模式：none / always / embedded-only
      init-mode: none          # 默认不自动建表
      # 自定义表前缀（同时作用于建表脚本与运行时持久层访问）
      table-prefix: "xai_tenant_diff_"
```

> **注意**：当前 `table-prefix` 已通过 `TenantDiffSchemaInitializer` + 独立 `SqlSessionFactory` 的动态表名映射同时作用于 DDL 初始化与 MyBatis 运行时访问，可安全用于自定义框架表前缀。

**三种初始化模式**：

| 模式 | 说明 | 适用场景 |
|------|------|----------|
| `none` | 不自动建表，业务方自行执行 DDL 或集成 Flyway | 生产环境（推荐） |
| `always` | 每次启动执行 `CREATE TABLE IF NOT EXISTS` | 开发/测试环境 |
| `embedded-only` | 仅对嵌入式数据库（H2）自动建表 | Demo / 单元测试 |

#### 4.1.4 实现要点

```java
@Configuration
@ConditionalOnProperty(name = "tenant-diff.standalone.enabled", havingValue = "true")
public class TenantDiffSchemaInitConfiguration {

    @Bean
    @ConditionalOnProperty(name = "tenant-diff.standalone.schema.init-mode",
                           havingValue = "always")
    public TenantDiffSchemaInitializer alwaysSchemaInitializer(DataSource dataSource,
                                                               TenantDiffProperties properties) {
        return new TenantDiffSchemaInitializer(dataSource, properties, false);
    }

    @Bean
    @ConditionalOnProperty(name = "tenant-diff.standalone.schema.init-mode",
                           havingValue = "embedded-only")
    public TenantDiffSchemaInitializer embeddedSchemaInitializer(DataSource dataSource,
                                                                  TenantDiffProperties properties) {
        return new TenantDiffSchemaInitializer(dataSource, properties, true);
    }
}

public class TenantDiffSchemaInitializer implements InitializingBean {

    private final DataSource dataSource;
    private final TenantDiffProperties properties;
    private final boolean embeddedOnly;

    @Override
    public void afterPropertiesSet() {
        if (embeddedOnly && !isEmbeddedDatabase()) {
            return;
        }
        String dialect = detectDialect(); // mysql / h2 / postgresql
        String script = loadScript("META-INF/tenant-diff/schema-" + dialect + ".sql");
        // 替换表前缀
        script = script.replace("xai_tenant_diff_", properties.getSchema().getTablePrefix());
        executeScript(dataSource, script);
    }
}
```

---

### 4.2 支持复用宿主应用数据源

#### 4.2.1 问题

当前 `DiffDataSourceAutoConfiguration` 强制从 `tenant-diff.datasources` 配置中创建新的数据源。但实际业务场景中，比对的数据表通常和业务表在同一个库中，业务方已经有了自己的 DataSource。

#### 4.2.2 方案

**自动发现 Spring 容器中的 `@Primary` DataSource**，作为 `DiffDataSourceRegistry` 的 primary 数据源：

```java
@Configuration
@ConditionalOnProperty(name = "tenant-diff.standalone.enabled", havingValue = "true")
public class DiffDataSourceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(DiffDataSourceRegistry.class)
    public DiffDataSourceRegistry diffDataSourceRegistry(
            @Autowired DataSource primaryDataSource,  // 自动注入宿主的主数据源
            @Autowired(required = false) DiffDataSourceProperties properties) {

        DiffDataSourceRegistry registry = new DiffDataSourceRegistry(primaryDataSource);

        // 如果配置了额外的数据源，也注册进去
        if (properties != null && properties.getDatasources() != null) {
            properties.getDatasources().forEach((key, config) -> {
                if (!"primary".equalsIgnoreCase(key)) {
                    DataSource ds = createHikariDataSource(config);
                    registry.register(key, ds);
                }
            });
        }

        return registry;
    }
}
```

**业务方配置对比**：

```yaml
# === 之前（必须手动配置） ===
tenant-diff:
  datasources:
    primary:
      url: jdbc:mysql://localhost:3306/mydb
      username: root
      password: xxx

# === 之后（自动复用，零配置） ===
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/mydb  # 宿主已有的数据源
    username: root
    password: xxx

tenant-diff:
  standalone:
    enabled: true
  # datasources: 不需要了！自动复用 spring.datasource

  # 如果需要跨库比对，只配额外的数据源即可
  # datasources:
  #   other-tenant-db:
  #     url: jdbc:mysql://other-host:3306/otherdb
  #     username: root
  #     password: xxx
```

---

### 4.3 MapperScan 隔离

#### 4.3.1 问题

当前 `@MapperScan("com.diff.standalone.persistence.mapper")` 在宿主应用的 Spring 上下文中生效。如果宿主也有 `@MapperScan`，可能产生扫描冲突。更严重的是，如果宿主使用了不同版本的 MyBatis-Plus，可能引发类加载冲突。

#### 4.3.2 方案

**方案 A（推荐）：使用独立的 SqlSessionFactory**

为 tenant-diff 的 Mapper 配置独立的 `SqlSessionFactory`，避免和宿主的 MyBatis 配置互相干扰：

```java
@Configuration
@ConditionalOnProperty(name = "tenant-diff.standalone.enabled", havingValue = "true")
public class TenantDiffMybatisConfiguration {

    @Bean("tenantDiffSqlSessionFactory")
    public SqlSessionFactory tenantDiffSqlSessionFactory(
            @Qualifier("tenantDiffDataSource") DataSource dataSource) throws Exception {

        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);

        // 只扫描框架自己的 entity
        factory.setTypeAliasesPackage("com.diff.standalone.persistence.entity");

        // 独立的 MyBatis 配置
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);

        // 乐观锁插件
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        factory.setPlugins(interceptor);

        return factory.getObject();
    }
}

// MapperScan 指定 sqlSessionFactoryRef
@MapperScan(
    basePackages = "com.diff.standalone.persistence.mapper",
    sqlSessionFactoryRef = "tenantDiffSqlSessionFactory"
)
```

**方案 B（轻量）：精确限定扫描范围 + 标记接口**

```java
@MapperScan(
    basePackages = "com.diff.standalone.persistence.mapper",
    markerInterface = TenantDiffMapper.class  // 只扫描实现了该接口的 Mapper
)
```

**推荐方案 A**：虽然配置稍多，但完全隔离了 MyBatis 的配置空间，不会和宿主产生任何冲突。

---

### 4.4 IDE 配置属性自动提示

#### 4.4.1 问题

业务方在 `application.yml` 中配置 `tenant-diff.*` 时，IDE 无法自动补全，容易写错属性名。

#### 4.4.2 方案

standalone 模块的 pom.xml 已经引入了 `spring-boot-configuration-processor`，但需要确保：

**步骤 1**：确认 `TenantDiffProperties` 的所有嵌套配置类都有正确的注解：

```java
@ConfigurationProperties(prefix = "tenant-diff.standalone")
@Data
public class TenantDiffProperties {

    /** 是否启用 tenant-diff standalone 模式 */
    private boolean enabled = false;

    /** 比对时默认忽略的字段 */
    private Set<String> defaultIgnoreFields = Set.of(
        "id", "tenantsid", "version", "data_modify_time"
    );

    /** 主业务键派生字段名 */
    private String mainBusinessKeyField = "main_business_key";

    /** 父业务键派生字段名 */
    private String parentBusinessKeyField = "parent_business_key";

    /** 持久化层配置 */
    private PersistenceProperties persistence = new PersistenceProperties();

    /** Web 层配置 */
    private WebProperties web = new WebProperties();

    /** Schema 初始化配置 */
    private SchemaProperties schema = new SchemaProperties();

    @Data
    public static class PersistenceProperties {
        /** 是否启用持久化层（Session/Snapshot 等） */
        private boolean enabled = true;
    }

    @Data
    public static class WebProperties {
        /** 是否启用内置 REST API */
        private boolean enabled = true;
        /** API 路径前缀 */
        private String basePath = "/api/tenantDiff/standalone";
    }

    @Data
    public static class SchemaProperties {
        /** 建表初始化模式：none / always / embedded-only */
        private String initMode = "none";
        /** 框架表的表名前缀 */
        private String tablePrefix = "xai_tenant_diff_";
    }
}
```

**步骤 2**：在 pom.xml 中确保 processor 在编译时生效：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-configuration-processor</artifactId>
    <optional>true</optional>
</dependency>
```

**步骤 3**：编译后自动生成 `META-INF/spring-configuration-metadata.json`，IDE 即可识别所有属性。

**补充**：可额外编写 `META-INF/additional-spring-configuration-metadata.json` 为属性添加更丰富的描述和默认值提示。

---

### 4.5 SimpleTablePlugin - 单表低代码声明

#### 4.5.1 问题

当前即使是最简单的单表业务（如 product 表），业务方也需要实现完整的 `StandaloneBusinessTypePlugin` 接口，编写 SQL 查询、字段映射、RecordData 构建等大量样板代码。

#### 4.5.2 方案：提供分层抽象

```
StandaloneBusinessTypePlugin          (接口 - 最大灵活性)
    ↑
AbstractStandaloneBusinessPlugin      (已有 - 工具方法)
    ↑
SimpleTablePlugin                     (新增 - 单表声明式)
    ↑
MultiTablePlugin                      (新增 - 多表声明式)
```

**SimpleTablePlugin - 单表业务低代码封装**：

```java
/**
 * 当前实现的核心扩展面如下。
 * Compare 侧由基类自动实现；Apply 侧仍需注册一个配套的 BusinessApplySupport。
 */
public abstract class SimpleTablePlugin extends AbstractStandaloneBusinessPlugin {

    @Override
    public abstract String businessType();

    protected abstract String tableName();

    protected abstract String businessKeyColumn();

    protected String businessNameColumn() { return null; }

    protected String tenantIdColumn() { return "tenantsid"; }

    protected Set<String> additionalIgnoreFields() {
        return Collections.emptySet();
    }

    protected Map<String, String> fieldTypes() {
        return Collections.emptyMap();
    }

    @Override
    public final BusinessSchema schema() { ... }

    @Override
    public final List<String> listBusinessKeys(Long tenantId, ScopeFilter filter, LoadOptions options) { ... }

    @Override
    public final BusinessData loadBusiness(Long tenantId, String businessKey, LoadOptions options) {
        // 自动规范化字段、附加 main_business_key、构建 BusinessData
        ...
    }
}
```

**业务方使用示例 - 单表**：

```java
@Component
public class ContractPlugin extends SimpleTablePlugin {

    public ContractPlugin(ObjectMapper objectMapper, DiffDataSourceRegistry registry) {
        super(objectMapper, registry);
    }

    @Override
    public String businessType() { return "CONTRACT"; }

    @Override
    protected String tableName() { return "biz_contract"; }

    @Override
    protected String businessKeyColumn() { return "contract_code"; }

    @Override
    protected Set<String> additionalIgnoreFields() {
        return Set.of("internal_remark", "last_sync_time");
    }
}
// Compare 场景完成；若需要 Apply，继续注册一个 BusinessApplySupport Bean
```

**MultiTablePlugin - 多表声明式（规划态，当前未落地）**：

```java
/**
 * 多表业务的声明式 Plugin 基类。
 * 业务方通过 defineSchema() 声明表结构和关系，框架自动处理加载逻辑。
 */
public abstract class MultiTablePlugin extends AbstractStandaloneBusinessPlugin {

    public abstract String businessType();

    /**
     * 声明多表结构。使用 Builder 风格：
     *
     * return SchemaDefinition.builder()
     *     .mainTable("example_order", "order_code")
     *     .childTable("example_order_item", "item_code", "order_id", "example_order")
     *     .childTable("example_order_item_detail", "detail_code", "order_item_id", "example_order_item")
     *     .build();
     */
    protected abstract SchemaDefinition defineSchema();

    // === 以下自动实现 ===
    // schema()、listBusinessKeys()、loadBusiness() 全部基于 SchemaDefinition 自动生成

    @Data
    @Builder
    public static class SchemaDefinition {
        private TableDef mainTable;
        private List<TableDef> childTables;

        @Data
        @AllArgsConstructor
        public static class TableDef {
            private String tableName;
            private String businessKeyColumn;
            private String foreignKeyColumn;   // null for main table
            private String parentTableName;    // null for main table
            private int dependencyLevel;
            private Set<String> ignoreFields;
        }
    }
}
```

**业务方使用示例 - 多表（规划态）**：

```java
@Component
public class OrderPlugin extends MultiTablePlugin {

    public OrderPlugin(ObjectMapper objectMapper, DiffDataSourceRegistry registry) {
        super(objectMapper, registry);
    }

    @Override
    public String businessType() { return "ORDER"; }

    @Override
    protected SchemaDefinition defineSchema() {
        return SchemaDefinition.builder()
            .mainTable("biz_order", "order_code")
            .childTable("biz_order_item", "item_code", "order_id", "biz_order")
            .childTable("biz_order_item_detail", "detail_code", "order_item_id", "biz_order_item")
            .ignoreFields("biz_order", Set.of("internal_status"))
            .build();
    }
}
// 规划态目标：若后续实现该抽象，多表层级关系可仅通过声明完成
```

#### 4.5.3 对应的 ApplySupport 简化

当前已落地 `SimpleTableApplySupport`；多表场景仍通过自定义 `BusinessApplySupport` 扩展：

```java
/**
 * 与 SimpleTablePlugin 配对的 ApplySupport。
 * 多数单表场景：默认的字段转换逻辑已足够，无需自定义。
 */
public class SimpleTableApplySupport extends AbstractSchemaBusinessApplySupport {

    public SimpleTableApplySupport(String businessType, ObjectMapper objectMapper, BusinessSchema schema) {
        super(objectMapper, schema);
        this.businessType = businessType;
    }

    // 默认实现：
    // - INSERT: 移除派生字段、做类型归一化、按关系替换外键
    // - UPDATE: 同 INSERT
    // - DELETE: 不变
    // 如果业务方需要自定义，覆盖对应方法即可
}
```

**业务方只需注册 Bean**：

```java
@Configuration
public class ContractDiffConfig {

    @Bean
    public ContractPlugin contractPlugin(ObjectMapper om, DiffDataSourceRegistry ds) {
        return new ContractPlugin(om, ds);
    }

    @Bean
    public BusinessApplySupport contractApplySupport(ObjectMapper objectMapper, ContractPlugin plugin) {
        return new SimpleTableApplySupport(plugin.businessType(), objectMapper, plugin.schema());
    }
}
```

> **说明**：当前仓库没有 `MultiTablePlugin` / `MultiTableApplySupport`。多表业务仍应基于 `AbstractStandaloneBusinessPlugin` + 自定义 `BusinessApplySupport` 实现。

---

### 4.6 本期实施路线

```
Day 1:
├── [4.1] DDL 脚本抽取到 standalone 资源目录
├── [4.1] 实现 TenantDiffSchemaInitializer（自动建表）
├── [4.2] 改造 DiffDataSourceAutoConfiguration 支持复用宿主数据源
└── [4.3] MapperScan 隔离（精确限定扫描范围）

Day 2-3:
├── [4.5] 实现 SimpleTablePlugin 基类
├── [4.5] 实现 SimpleTableApplySupport
└── [4.5] 改造 demo 中的 ExampleProductPlugin 使用 SimpleTablePlugin 验证

Day 4:
├── [4.4] 完善 TenantDiffProperties 嵌套配置 + 确认元数据生成
├── 为所有 Bean 添加 @ConditionalOnMissingBean
└── 端到端验证：从零创建一个新项目接入 Starter
```

### 4.7 实施完成状态（2026-03-08）

| 项目 | 状态 | 实施说明 |
|------|------|----------|
| **DDL 脚本分发 + 自动建表** | ✅ 完成 | `schema-h2.sql`/`schema-mysql.sql` 放入 `META-INF/tenant-diff/`；`TenantDiffSchemaInitializer` 支持 none/always/embedded-only 三种模式；`table-prefix` 同时作用于建表脚本与运行时持久层访问 |
| **数据源复用宿主 DataSource** | ✅ 已有 | 审查发现 `DiffDataSourceAutoConfiguration` 已实现自动注入 Spring 主 DataSource，无需额外改动 |
| **MapperScan 隔离** | ✅ 完成 | 创建独立 `tenantDiffSqlSessionFactory` Bean，`@MapperScan` 绑定 `sqlSessionFactoryRef`，完全隔离框架 MyBatis 配置 |
| **SimpleTablePlugin 基类** | ✅ 完成 | `SimpleTablePlugin`（声明式单表 Plugin）+ `SimpleTableApplySupport`（配套 Apply）；单表 Compare 代码量显著下降，但启用 Apply 仍需显式注册 `BusinessApplySupport` |
| **配置元数据** | ✅ 完成 | `TenantDiffProperties` 增加 `SchemaProperties` 嵌套类，`spring-boot-configuration-processor` 已就绪 |

**变更文件清单**：

```
新增：
  standalone/resources/META-INF/tenant-diff/schema-h2.sql
  standalone/resources/META-INF/tenant-diff/schema-mysql.sql
  standalone/config/TenantDiffSchemaInitializer.java
  standalone/config/TenantDiffSchemaInitConfiguration.java
  standalone/plugin/SimpleTablePlugin.java
  standalone/apply/support/SimpleTableApplySupport.java
  standalone/test/.../SimpleTablePluginTest.java
  standalone/test/.../TenantDiffSchemaInitializerTest.java

修改：
  standalone/config/TenantDiffProperties.java          (+ SchemaProperties)
  standalone/config/TenantDiffStandaloneConfiguration.java (+ SqlSessionFactory 隔离)
  standalone/plugin/AbstractStandaloneBusinessPlugin.java  (+ asString/attachMainBusinessKey)
  standalone/resources/META-INF/spring/AutoConfiguration.imports
  demo/plugin/ExampleProductPlugin.java                (→ SimpleTablePlugin)
  demo/plugin/ExampleProductApplySupport.java          (→ SimpleTableApplySupport)
  demo/plugin/ExampleOrderPlugin.java                  (移除冗余 asString)
```

**测试验证**：`mvn verify` 全量通过（含 standalone 新增测试 + demo 全部回归测试）。

---

## 5. 后续迭代：需求驱动

> 以下内容不在本期范围。每项都标注了**触发条件**，满足条件时再实施，价值最大化。

### 5.1 配置分层（Web/持久化可关）

> **触发条件**：有业务方明确说"我只要引擎，不需要 Web 和持久化"
> **预计投入**：1.5 天

将 `TenantDiffStandaloneConfiguration` 拆分为 Core / Persistence / Web 三个独立配置类，通过 `tenant-diff.standalone.persistence.enabled` 和 `tenant-diff.standalone.web.enabled` 控制。详见[附录 A.1](#a1-配置分层方案)。

### 5.2 MultiTablePlugin 基类

> **触发条件**：第一批接入方中有多表业务需求
> **预计投入**：2 天

提供声明式多表 Plugin 基类，通过 `SchemaDefinition.builder()` 声明表结构和关系，框架自动处理多表加载逻辑。详见[附录 A.2](#a2-multitableplugin-方案)。

### 5.3 接入文档

> **触发条件**：非本团队成员需要接入
> **预计投入**：2 天

编写 getting-started.md、plugin-development-guide.md、configuration-reference.md 等文档。

### 5.4 事件/回调机制

> **触发条件**：业务方提出审批流、通知、自定义校验等需求
> **预计投入**：2 天

基于 Spring ApplicationEvent 的事件体系。详见[附录 A.3](#a3-事件机制方案)。

#### 5.4.1 方案预览：基于 Spring ApplicationEvent

定义一组事件，业务方通过 `@EventListener` 或 `ApplicationListener` 接入：

```java
// === 事件基类 ===
public abstract class TenantDiffEvent extends ApplicationEvent {
    private final Long sessionId;

    protected TenantDiffEvent(Object source, Long sessionId) {
        super(source);
        this.sessionId = sessionId;
    }
}

// === 比对相关事件 ===

/** 比对开始前 */
public class DiffStartedEvent extends TenantDiffEvent {
    private final Long sourceTenantId;
    private final Long targetTenantId;
    private final TenantModelScope scope;
}

/** 比对完成后 */
public class DiffCompletedEvent extends TenantDiffEvent {
    private final DiffStatistics statistics;
    private final SessionStatus status;      // COMPLETED or FAILED
    private final String errorMessage;       // null if success
}

// === Apply 相关事件 ===

/** Apply 执行前（可用于审批拦截） */
public class ApplyStartingEvent extends TenantDiffEvent {
    private final ApplyPlan plan;
    private final ApplyDirection direction;
    private boolean vetoed = false;          // 业务方可否决

    public void veto(String reason) {
        this.vetoed = true;
        this.vetoReason = reason;
    }
}

/** Apply 完成后 */
public class ApplyCompletedEvent extends TenantDiffEvent {
    private final String applyId;
    private final ApplyResult result;
}

/** 回滚完成后 */
public class RollbackCompletedEvent extends TenantDiffEvent {
    private final String applyId;
}
```

**业务方使用示例**：

```java
@Component
public class MyDiffEventListener {

    @EventListener
    public void onDiffCompleted(DiffCompletedEvent event) {
        // 比对完成后发送通知
        notificationService.send("比对完成，差异数：" + event.getStatistics().totalRecords());
    }

    @EventListener
    public void onApplyStarting(ApplyStartingEvent event) {
        // Apply 前审批检查
        if (!approvalService.isApproved(event.getSessionId())) {
            event.veto("未通过审批");
        }
    }

    @Async
    @EventListener
    public void onApplyCompleted(ApplyCompletedEvent event) {
        // 异步记录操作日志
        auditLogService.log("Apply执行完成", event.getApplyId());
    }
}
```

#### 5.4.2 实现位置

在 Service 层的关键节点发布事件：

```java
// TenantDiffStandaloneService
public void runCompare(Long sessionId) {
    eventPublisher.publishEvent(new DiffStartedEvent(this, sessionId, ...));
    try {
        // ... 执行比对
        eventPublisher.publishEvent(new DiffCompletedEvent(this, sessionId, stats, COMPLETED, null));
    } catch (Exception e) {
        eventPublisher.publishEvent(new DiffCompletedEvent(this, sessionId, null, FAILED, e.getMessage()));
        throw e;
    }
}

// TenantDiffStandaloneApplyService
public ApplyResult executeApply(Long sessionId, ApplyDirection direction, ApplyOptions options) {
    ApplyStartingEvent startingEvent = new ApplyStartingEvent(this, sessionId, plan, direction);
    eventPublisher.publishEvent(startingEvent);
    if (startingEvent.isVetoed()) {
        throw new TenantDiffException(ErrorCode.APPLY_VETOED, startingEvent.getVetoReason());
    }
    // ... 执行 Apply
    eventPublisher.publishEvent(new ApplyCompletedEvent(this, sessionId, applyId, result));
}
```

---

### 5.5 持久化层接口抽象

> **触发条件**：有 JPA 项目需要接入
> **预计投入**：3-5 天

#### 5.5.1 问题

当前持久化层直接使用 MyBatis-Plus 的 Mapper，硬编码了 ORM 依赖。如果业务方使用 JPA 或其他 ORM，会产生依赖冲突。

#### 5.5.2 方案：Repository 抽象接口

```java
// === 抽象接口层（在 standalone 中定义） ===

public interface DiffSessionRepository {
    DiffSession save(DiffSession session);
    DiffSession findById(Long id);
    void updateStatus(Long id, SessionStatus status, String errorMessage);
}

public interface DiffResultRepository {
    void save(Long sessionId, String businessType, String businessKey, String diffJson);
    String findDiffJson(Long sessionId, String businessType, String businessKey);
    List<DiffResultSummary> listBySession(Long sessionId, int page, int size);
}

public interface DiffSnapshotRepository {
    void save(Long sessionId, String applyId, String businessType,
              String businessKey, String tableName, String snapshotJson);
    List<SnapshotRecord> findByApplyId(Long sessionId, String applyId);
}

// ... 其他 Repository 接口
```

```java
// === MyBatis-Plus 实现（默认） ===

@Component
@ConditionalOnClass(name = "com.baomidou.mybatisplus.core.mapper.BaseMapper")
public class MybatisPlusDiffSessionRepository implements DiffSessionRepository {

    private final TenantDiffSessionMapper mapper;

    // ... 实现细节
}
```

```java
// === JPA 实现（业务方可选择引入） ===
// 未来可以提供 tenant-diff-jpa 模块
```

**好处**：
- Service 层只依赖 Repository 接口，不依赖具体 ORM
- 默认提供 MyBatis-Plus 实现，业务方可以覆盖
- 未来可以新增 JPA / JDBC Template / R2DBC 等实现

#### 5.5.3 渐进策略

考虑到改造量较大，建议分两步走：
1. **第一步**（P2）：抽出 Repository 接口，当前 MyBatis-Plus 实现保持不变，Service 层改为依赖接口
2. **第二步**（P3）：提供 `tenant-diff-persistence-jpa` 模块作为替代实现

---

### 5.6 数据库方言支持

> **触发条件**：有 PostgreSQL/Oracle 项目需要接入
> **预计投入**：2 天

#### 5.6.1 方案：SQL 方言抽象

```java
public interface SqlDialect {
    /** 生成 INSERT SQL */
    SqlAndArgs buildInsert(String tableName, Long tenantId, Map<String, Object> fields);
    /** 生成 UPDATE SQL */
    SqlAndArgs buildUpdateById(String tableName, Long targetId, Long tenantId, Map<String, Object> fields);
    /** 生成 DELETE SQL */
    SqlAndArgs buildDeleteById(String tableName, Long targetId, Long tenantId);
    /** 引用标识符（MySQL用``, PG用""） */
    String quoteIdentifier(String identifier);
}

// 默认实现
public class MySqlDialect implements SqlDialect { ... }
public class PostgreSqlDialect implements SqlDialect { ... }
public class H2Dialect implements SqlDialect { ... }
```

**自动检测**：

```java
@Bean
@ConditionalOnMissingBean
public SqlDialect sqlDialect(DataSource dataSource) {
    String url = extractJdbcUrl(dataSource);
    if (url.contains(":mysql:")) return new MySqlDialect();
    if (url.contains(":postgresql:")) return new PostgreSqlDialect();
    if (url.contains(":h2:")) return new H2Dialect();
    return new MySqlDialect(); // 默认回退
}
```

---

### 5.7 测试辅助模块

> **触发条件**：3+ 个业务方接入后，共性测试模式沉淀出来
> **预计投入**：3 天

#### 5.7.1 方案：提供 `tenant-diff-test` 模块

```xml
<!-- 业务方 test scope 引入 -->
<dependency>
    <groupId>com.diff</groupId>
    <artifactId>tenant-diff-test</artifactId>
    <version>${tenant-diff.version}</version>
    <scope>test</scope>
</dependency>
```

**提供的测试工具**：

```java
// 1. Plugin 测试基类
public abstract class PluginTestBase {
    @Autowired
    protected JdbcTemplate jdbcTemplate;
    @Autowired
    protected DiffDataSourceRegistry registry;

    /** 加载测试数据 SQL */
    protected void loadTestData(String sqlResource) { ... }

    /** 清除测试数据 */
    protected void cleanTestData(String tableName, Long tenantId) { ... }
}

// 2. 比对结果断言工具
public class DiffAssertions {

    /** 断言 businessDiff 包含指定数量的 INSERT */
    public static void assertInsertCount(BusinessDiff diff, String tableName, int expected) { ... }

    /** 断言某条记录存在字段差异 */
    public static void assertFieldDiff(RecordDiff record, String fieldName,
                                       Object expectedSource, Object expectedTarget) { ... }

    /** 断言 Apply 结果成功 */
    public static void assertApplySuccess(ApplyResult result) { ... }
}

// 3. Mock Plugin 工厂
public class MockPluginBuilder {

    public static StandaloneBusinessTypePlugin singleTable(
            String businessType, String tableName, List<Map<String, Object>> records) {
        // 构造一个内存中的 Mock Plugin，无需数据库
        ...
    }
}

// 4. 自动配置测试 Slice
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureTestDatabase
@Import(TenantDiffTestConfiguration.class)
public @interface TenantDiffTest { }
```

**业务方测试示例**：

```java
@TenantDiffTest
class ContractPluginTest extends PluginTestBase {

    @Autowired
    private ContractPlugin contractPlugin;

    @Test
    void shouldListBusinessKeys() {
        loadTestData("test-data/contracts.sql");

        List<String> keys = contractPlugin.listBusinessKeys(1L, null);

        assertThat(keys).containsExactlyInAnyOrder("CONTRACT-001", "CONTRACT-002");
    }

    @Test
    void shouldDiffContracts() {
        loadTestData("test-data/contracts.sql");

        // 使用框架引擎进行端到端比对
        TenantDiffEngine engine = new TenantDiffEngine();
        // ... 构建 models 并比对
        DiffAssertions.assertInsertCount(diff, "biz_contract", 1);
    }
}
```

---

### 5.8 Maven 发布

> **触发条件**：对外推广、非本团队项目引用
> **预计投入**：1 天

#### 5.8.1 发布前置条件

- [ ] 确定 groupId（建议 `com.diff` 或 `io.github.xxx`）
- [ ] 配置 GPG 签名
- [ ] 配置 Maven Central（Sonatype OSSRH）或私有 Nexus
- [ ] CI/CD 流水线（GitHub Actions）

#### 5.8.2 发布的产物

```
com.diff:tenant-diff-core:1.0.0
com.diff:tenant-diff-standalone:1.0.0
com.diff:tenant-diff-spring-boot-starter:1.0.0    ← 业务方主要引入这个
com.diff:tenant-diff-test:1.0.0                   ← 测试辅助（可选）
```

#### 5.8.3 版本策略

```
0.x.x  → 当前阶段，API 可能有破坏性变更
1.0.0  → 首个稳定版，Plugin SPI 接口冻结
1.x.x  → 向后兼容的功能迭代
2.0.0  → 允许 SPI 接口变更
```

---

---

## 6. 附录 A：完整技术方案（所有项目）

> 以下为各延期项目的详细技术方案，当触发条件满足时可直接参考实施。

### A.1 配置分层方案

将 `TenantDiffStandaloneConfiguration` 拆分为 3 个独立配置类：

```java
// === 核心层：始终加载（只要 enabled=true） ===
@AutoConfiguration
@ConditionalOnProperty(name = "tenant-diff.standalone.enabled", havingValue = "true")
@EnableConfigurationProperties(TenantDiffProperties.class)
public class TenantDiffCoreAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public TenantDiffEngine tenantDiffEngine() { ... }

    @Bean @ConditionalOnMissingBean
    public PlanBuilder planBuilder() { ... }

    @Bean @ConditionalOnMissingBean
    public StandalonePluginRegistry pluginRegistry(List<StandaloneBusinessTypePlugin> plugins) { ... }

    @Bean @ConditionalOnMissingBean
    public StandaloneTenantModelBuilder modelBuilder(...) { ... }

    @Bean @ConditionalOnMissingBean
    public BusinessApplySupportRegistry applySupportRegistry(List<BusinessApplySupport> supports) { ... }
}

// === 持久化层：可关闭 ===
@AutoConfiguration(after = TenantDiffCoreAutoConfiguration.class)
@ConditionalOnProperty(name = "tenant-diff.standalone.persistence.enabled",
                       havingValue = "true", matchIfMissing = true)
@MapperScan(basePackages = "com.diff.standalone.persistence.mapper",
            sqlSessionFactoryRef = "tenantDiffSqlSessionFactory")
public class TenantDiffPersistenceAutoConfiguration {
    // Service、ApplyExecutor、SnapshotBuilder 等 Bean
}

// === Web 层：可关闭 ===
@AutoConfiguration(after = TenantDiffPersistenceAutoConfiguration.class)
@ConditionalOnProperty(name = "tenant-diff.standalone.web.enabled",
                       havingValue = "true", matchIfMissing = true)
@ConditionalOnWebApplication
public class TenantDiffWebAutoConfiguration {
    // Controller、ExceptionHandler 等 Bean
}
```

### A.2 MultiTablePlugin 方案

```java
public abstract class MultiTablePlugin extends AbstractStandaloneBusinessPlugin {
    public abstract String businessType();

    /**
     * 声明多表结构：
     * return SchemaDefinition.builder()
     *     .mainTable("example_order", "order_code")
     *     .childTable("example_order_item", "item_code", "order_id", "example_order")
     *     .build();
     */
    protected abstract SchemaDefinition defineSchema();

    // schema()、listBusinessKeys()、loadBusiness() 全部基于 SchemaDefinition 自动生成
}
```

### A.3 事件机制方案

```java
// 事件类型
public class DiffStartedEvent extends TenantDiffEvent { ... }
public class DiffCompletedEvent extends TenantDiffEvent { ... }
public class ApplyStartingEvent extends TenantDiffEvent {
    public void veto(String reason) { ... }  // 业务方可否决
}
public class ApplyCompletedEvent extends TenantDiffEvent { ... }
public class RollbackCompletedEvent extends TenantDiffEvent { ... }

// 业务方使用
@EventListener
public void onApplyStarting(ApplyStartingEvent event) {
    if (!approved(event.getSessionId())) event.veto("需要审批");
}
```

---

## 7. 附录 B：业务方接入最终效果

### 7.1 最小接入（单表业务）

**pom.xml**：

```xml
<dependency>
    <groupId>com.diff</groupId>
    <artifactId>tenant-diff-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

**application.yml**：

```yaml
tenant-diff:
  standalone:
    enabled: true
    schema:
      init-mode: always  # 开发环境
```

**业务代码**（单表 Compare + Apply 的最小接线：1 个 Plugin + 1 个配置类/Bean）：

```java
// Plugin - 声明要比对的表
@Component
public class ContractPlugin extends SimpleTablePlugin {
    public ContractPlugin(ObjectMapper om, DiffDataSourceRegistry ds) { super(om, ds); }
    @Override public String businessType() { return "CONTRACT"; }
    @Override protected String tableName() { return "biz_contract"; }
    @Override protected String businessKeyColumn() { return "contract_code"; }
}

@Configuration
public class ContractDiffConfiguration {

    // ApplySupport - 声明如何写入
    @Bean
    public BusinessApplySupport contractApply(ObjectMapper om, ContractPlugin p) {
        return new SimpleTableApplySupport(p.businessType(), om, p.schema());
    }
}
```

**即可使用的能力**：
- `POST /api/tenantDiff/standalone/session/create` - 创建比对会话
- `GET /api/tenantDiff/standalone/session/get` - 查看比对结果
- `POST /api/tenantDiff/standalone/apply/preview` - 预览 Apply 计划
- `POST /api/tenantDiff/standalone/apply/execute` - 执行数据同步
- `POST /api/tenantDiff/standalone/apply/rollback` - 回滚

### 7.2 高级接入（多表 + 自定义逻辑）

> 当前仓库未提供 `MultiTablePlugin`。多表业务请基于 `AbstractStandaloneBusinessPlugin` 自定义实现，并按需配套一个 `BusinessApplySupport`。

```java
// 多表 Plugin：当前基于 AbstractStandaloneBusinessPlugin 自定义实现
@Component
public class OrderPlugin extends AbstractStandaloneBusinessPlugin {
    @Override
    public String businessType() { return "ORDER"; }

    @Override
    public BusinessSchema schema() {
        return BusinessSchema.builder()
            .tables(Map.of("biz_order", 0, "biz_order_item", 1))
            .relations(List.of(
                BusinessSchema.TableRelation.builder()
                    .childTable("biz_order_item")
                    .fkColumn("order_id")
                    .parentTable("biz_order")
                    .build()
            ))
            .build();
    }

    @Override
    public List<String> listBusinessKeys(Long tenantId, ScopeFilter filter, LoadOptions options) { ... }

    @Override
    public BusinessData loadBusiness(Long tenantId, String businessKey, LoadOptions options) { ... }
}

// 自定义 ApplySupport（需要特殊字段处理时）
public class OrderApplySupport extends AbstractSchemaBusinessApplySupport {

    public OrderApplySupport(ObjectMapper objectMapper, BusinessSchema schema) {
        super(objectMapper, schema);
    }

    @Override
    public String businessType() { return "ORDER"; }
}

@Configuration
public class OrderDiffConfiguration {

    @Bean
    public BusinessApplySupport orderApplySupport(ObjectMapper objectMapper, OrderPlugin plugin) {
        return new OrderApplySupport(objectMapper, plugin.schema());
    }
}
```

> 若多表 Apply 仅依赖 `schema().relations()` 做外键替换，`AbstractSchemaBusinessApplySupport` 的默认实现通常已足够；只有存在字段重写、编码转换、默认值注入时，才需要覆写 `transformForInsert/Update`。

> 事件监听（审批/通知）仍属于后续迭代，当前仓库尚未提供 `ApplyStartingEvent` / `ApplyCompletedEvent` 等运行时事件扩展点。

### 7.3 规划态示例：仅用引擎（需等待配置分层完成）

> 当前仓库尚未落地 `tenant-diff.standalone.persistence.enabled` / `tenant-diff.standalone.web.enabled`，以下配置仅表示后续配置分层完成后的目标形态，暂不可直接复制使用。

```yaml
tenant-diff:
  standalone:
    enabled: true
    persistence:
      enabled: false
    web:
      enabled: false
```

```java
@Service
public class MyDiffService {

    @Autowired
    private TenantDiffEngine engine;

    @Autowired
    private StandalonePluginRegistry pluginRegistry;

    @Autowired
    private StandaloneTenantModelBuilder modelBuilder;

    public DiffStatistics compare(Long tenantA, Long tenantB) {
        List<BusinessData> modelsA = modelBuilder.buildForTenant(tenantA, null, null);
        List<BusinessData> modelsB = modelBuilder.buildForTenant(tenantB, null, null);
        DiffRules rules = DiffRules.withDefaults();
        var result = engine.compare(modelsA, modelsB, rules);
        return result.statistics();
    }
}
```

---

> **总结**：当前项目的核心比对能力已经完备，主要差距在于 Starter 的**开箱即用性**和**接入便捷性**。
>
> **本期（~4天）**：3 项 Tier1 基础改造 + SimpleTablePlugin + 配置元数据，可把单表 Compare 接入压到约 15 行代码；若要启用 Apply，还需额外注册一个 `BusinessApplySupport` Bean。
>
> **后续**：配置分层、事件机制、Repository 抽象等全部需求驱动，触发条件明确，避免过度设计。
