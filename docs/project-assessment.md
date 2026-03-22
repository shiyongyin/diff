# Tenant Diff 项目质量评估

> 最后更新：2026-03-23
> 文档类型：阶段性评估快照，不作为运行时行为 SSOT
> 运行时事实请以 `prd.md`、`design-doc.md`、`ops-guide.md` 为准。

---

## 1. 评估概要

### 1.1 评估基线

| 维度 | 结论 |
|------|------|
| 代码基线 | `master` 分支，基于 `048b855`，**工作区包含未提交修改** |
| 评估范围 | `tenant-diff-core`、`tenant-diff-standalone`、`tenant-diff-spring-boot-starter`、`tenant-diff-demo` |
| 评估方法 | 源码核查 + 构建配置核查 + 文档核查 + 本轮执行 `./mvnw -B -ntp verify`（2026-03-22 21:24:13 通过） |
| 结论边界 | 本文评价的是**当前工作区状态**，不是历史提交，也不是路线图目标态 |

### 1.2 代码规模

| 指标 | 当前值 |
|------|--------|
| 主代码 Java 文件 | `119` |
| 测试 Java 文件 | `31` |
| 主代码行数 | 约 `12,702` |
| 测试代码行数 | 约 `7,100+` |
| 主代码 + 测试总行数 | 约 `19,800+` |

模块拆分口径：

| 模块 | 主代码文件 | 测试文件 | 说明 |
|------|-----------|---------|------|
| `tenant-diff-core` | `40` | `2` | 纯核心引擎与领域模型 |
| `tenant-diff-standalone` | `73` | `11` | 运行时、REST、持久化、编排 |
| `tenant-diff-demo` | `6` | `18` | 示例应用与集成测试主战场；含 6 个 MySQL 真实数据库 E2E |
| `tenant-diff-spring-boot-starter` | `0` | `0` | 纯依赖聚合器 |

### 1.3 本轮验证结果

| 项目 | 结果 | 说明 |
|------|------|------|
| `./mvnw -B -ntp verify` | **通过** | Reactor 5/5 成功，`BUILD SUCCESS` |
| `tenant-diff-core` 测试 | 通过 | 60 tests, 0 failures |
| `tenant-diff-standalone` 测试 | 通过 | 68 tests, 0 failures |
| `tenant-diff-demo` 测试 | 通过 | 70 tests, 0 failures |
| SpotBugs | 通过 | `core`、`standalone` 在本轮 `verify` 中无告警 |
| JaCoCo | 通过 / 部分门禁 | `core` 和 `standalone` 达标；`demo` 仍未设门禁 |

本轮已验证并修复的关键问题：

- rollback drift 检测曾错误使用源端 FK/ID 重建预期状态，导致级联回滚前误判残留 diff；现已按目标端真实 ID/FK 重建并通过回归测试。
- apply 子表写入曾仅依赖 `IdMapping` 替换父 FK；当父记录已存在目标端且本次未新插入父记录时，存在把源端 FK 直接写入目标端的真实风险。现已增加按业务键回查目标端 ID 的回退逻辑。
- 新增 `AbstractSchemaBusinessApplySupportTest` 后，`tenant-diff-standalone` 的 JaCoCo line coverage 已重新满足 `>= 30%` 门禁。

### 1.4 综合评分

| 维度 | 评分 | 说明 |
|------|------|------|
| 产品定位 | A- | 场景聚焦，边界清楚，赛道表达仍偏内部视角 |
| 架构设计 | A- | 模块拆分和治理能力成熟，rollback/apply 关键链路已修回，但外部数据源 rollback 仍有限制 |
| 代码质量 | A- | 结构、可读性和问题修复质量较好，当前工作区已无阻断性回归 |
| 测试质量 | A- | 核心 ErrorCode 和方向覆盖基本闭合，6 个 MySQL E2E 消除了先前关键盲区；仍有少量 P1/P2 测试债 |
| 工程基础设施 | B | CI、SpotBugs、模块级覆盖率门禁已具备，但基础设施还不完整 |
| 文档质量 | B+ | 主线文档丰富，先前 DIFF_E_2015/2016 和配置项漂移已修复；评估/路线图维护纪律仍需持续 |
| 数据安全 | B | 已有 warning/audit/lease/drift 控制，且本轮修复了真实 FK 映射与 rollback 误判问题 |
| 生产就绪度 | C+ | 仍是 0.x，缺认证、容器化、依赖安全扫描，但当前工作区已恢复全量 verify 绿灯 |
| **总评** | **A-** | **先前 P0 阻塞项已全部消除，核心安全链路 E2E 闭合，文档漂移已修复；当前处于"可发布，但仍有少量 P1/P2 测试债和产品化缺口"的状态** |

---

## 2. 产品定位评估

### 2.1 当前定位

Tenant Diff 的定位已经比较清晰：它不是通用 ETL、不是数据库复制、也不是运行时业务数据同步工具，而是**多租户 SaaS 场景下的配置型数据差异对比与选择性同步组件**。

当前定位成立的原因：

- `core` 提供独立的 diff / plan 领域模型，不绑具体业务表
- `standalone` 通过插件体系承接业务数据加载和 Apply 支持
- `demo` 同时覆盖单表与多表级联场景，说明目标不是"只做玩具示例"

### 2.2 优势

- 场景真实：标准租户向客户租户同步配置，是实施交付阶段高频痛点。
- 边界明确：文档和代码都明确排除了运行时交易数据同步、数据库主从复制、分布式事务。
- 扩展模型可复用：单表和多表插件都已有示例，不是只停留在抽象接口层。

### 2.3 不足

- 品类叙事仍弱：对外仍偏"内部工程术语"，缺少开源生态中易传播的产品描述。
- 缺竞品对比：尚无与 Flyway/Liquibase、自研脚本、平台内建同步工具的横向对照。
- Adoption story 不足：README 和主文档仍偏技术说明，缺完整接入案例。

结论：**产品方向是对的，但"为什么值得采用"仍需要更强的外部表达。**

---

## 3. 架构设计评估

### 3.1 架构强项

- 四模块分层清楚：`core -> standalone -> starter -> demo`
- `core` 保持纯 Java，领域模型和引擎没有 Spring 运行时耦合
- `standalone` 已形成完整编排层，而不是简单 Controller + SQL 拼接
- 当前代码已经落地多项治理能力：
  - compare warning 结构化持久化
  - Apply durable audit
  - 跨 session target lease
  - preview token TTL
  - compare freshness gate
  - rollback drift confirm / verification summary

### 3.2 架构上的成熟点

- 安全控制不再只靠"文档约束"，而是已经落到 `ErrorCode`、持久化字段和服务编排中。
- 表名前缀和建表脚本具备动态化支持，说明运维层面已经开始考虑嵌入式接入。
- 外部数据源已经不是概念设计，代码和测试都覆盖了外部数据源 Apply 失败审计场景。

### 3.3 当前短板

- `starter` 目前只是纯依赖聚合，产物为空 jar；接入体验简洁，但没有提供更多显式约束或自动化治理能力。
- PostgreSQL 仍不是开箱即用支持：方言能识别，但发行包没有内置 DDL。
- 外部数据源 rollback 仍未落地，能力边界需要继续明确。

结论：**架构设计整体成熟度已明显高于旧评估，关键回归已修复，但距离完整产品化仍有边界能力缺口。**

---

## 4. 代码质量评估

### 4.1 明显优点

- 命名和分层基本统一，`*ServiceImpl`、`*Mapper`、`*Po`、`*Controller` 职责边界清楚。
- 错误码集中管理，避免了跨层硬编码字符串扩散。
- 安全和一致性控制已有显式实现，不只是注释承诺。
- Javadoc 和设计动机注释质量整体较高，阅读成本明显低于同规模项目平均水平。

### 4.2 当前风险

- 历史上已暴露过真实回归：rollback 对 `EXAMPLE_ORDER` 的纯 INSERT / 父子表 / 三层级联场景，以及 apply 子表 FK 映射在父记录已存在目标端时的错写风险；本轮代码已修复并通过全量验证，但这类链路仍应保持高压回归测试。
- 文档和实现之间曾发生明显漂移，说明"代码变更后同步更新评估材料"的纪律还没有完全收口。
- `StandalonePluginRegistry` 只能对插件可变字段给出告警，无法从框架层强制线程安全。

### 4.3 结论

代码结构和治理意识是强项，且本轮修复不是表面消警，而是把 rollback 预期状态重建和 apply FK 映射逻辑修正到了目标端真实主键语义上。因此本轮代码质量上调到 `A-`，但仍需继续靠回归测试守住复杂链路。

---

## 5. 测试质量评估

### 5.1 当前测试面

| 层级 | 现状 |
|------|------|
| 核心单元测试 | `core` 有高质量引擎与 PlanBuilder 测试，覆盖率充足 |
| standalone 单元/集成测试 | 已覆盖 selection、warning、Decision filter、schema init、plugin registry 等关键治理点 |
| demo 集成测试 | 覆盖 Session / Apply / Rollback / 并发 / freshness / 外部数据源等端到端链路 |
| MySQL 真实数据库 E2E | 6 个 E2E 类，覆盖下表所列关键安全链路 |
| 多 JDK CI | GitHub Actions 以 JDK `17/21` 双矩阵跑 `verify` |

#### MySQL E2E 覆盖矩阵

| E2E 测试类 | 覆盖场景 |
|-----------|---------|
| `MysqlReleaseGateE2ETest` | preview / decision / compare / apply / rollback 全链路；B_TO_A 方向覆盖 |
| `MysqlAdversarialGuardrailE2ETest` | `DIFF_E_2001` maxAffectedRows 阈值拒绝、`DIFF_E_1004` 重复 Apply 拒绝、B_TO_A apply + rollback |
| `MysqlRollbackConcurrencyE2ETest` | `DIFF_E_3002` rollback 并发冲突 |
| `MysqlPreviewLimitE2ETest` | `DIFF_E_2014` preview action 数量限额 |
| `MysqlStandaloneDisabledE2ETest` | `tenant-diff.standalone.enabled=false` 时端点不暴露 |
| `MysqlWarningDegradationE2ETest` | compare warning 降级为 partial success 后仍可完成 apply + rollback |

### 5.2 可量化结果

| 模块 | Line Coverage | Branch Coverage | 门禁 |
|------|---------------|----------------|------|
| `tenant-diff-core` | `75.31%` | `58.78%` | `>= 50%` |
| `tenant-diff-standalone` | `30.97%` | `16.09%` | `>= 30%` |
| `tenant-diff-demo` | 未形成本轮可用汇总值 | 未形成本轮可用汇总值 | **跳过 JaCoCo check** |

### 5.3 已消除的覆盖盲区

以下在先前评估中列为关键缺口的场景，现已通过 MySQL E2E 补齐：

| 先前缺口 | 当前覆盖证据 |
|---------|------------|
| B_TO_A 方向"零覆盖" | `MysqlAdversarialGuardrailE2ETest:200` — B_TO_A apply + rollback |
| `DIFF_E_2001` 无 E2E | `MysqlAdversarialGuardrailE2ETest:106` — maxAffectedRows 阈值拒绝 |
| `DIFF_E_1004` 无 E2E | `MysqlAdversarialGuardrailE2ETest:158` — 重复 Apply 拒绝 |
| `DIFF_E_3002` 无 E2E | `MysqlRollbackConcurrencyE2ETest:49` — rollback 并发冲突 |

### 5.4 仍然成立的测试债

- `demo` 作为关键端到端回归承载模块，仍没有覆盖率门禁。
- `RollbackEndToEndTest#rollback_afterDelete_restoresDeletedRecords`（`:186`）仍是可疑的"永假/条件跳过"测试：当前种子数据下 A_TO_B 不产生 DELETE，`hasDelete` 始终为 false，测试直接 return 通过而不验证任何 rollback 逻辑。
- `DIFF_E_0002`、`DIFF_E_1002` 缺少更强的 REST 层验证，可列为次级补强项。
- `DiffDetailView` 三种模式（FULL / DIFF_ONLY / SUMMARY）未见专门测试。
- 复杂级联 rollback 和"已存在父记录"场景的 FK 映射仍需继续压实。

### 5.5 结论

相比先前评估，测试资产已从"关键安全链路存在多个 E2E 盲区"跃升到**核心 ErrorCode 和方向覆盖基本闭合**。6 个 MySQL E2E 类直接对真实数据库跑全链路，消除了先前 B_TO_A、maxAffectedRows、重复 Apply、rollback 并发冲突等关键覆盖缺口。当前遗留项均为 P1/P2 级测试债，不构成发布阻塞。本轮上调至 **A-**。

---

## 6. 工程基础设施评估

### 6.1 已具备

- Maven Wrapper
- GitHub Actions CI（JDK `17/21` 双矩阵）
- SpotBugs 集成
- 模块级 JaCoCo 门禁（`core` / `standalone`）
- Issue 模板、PR 模板
- `release.yml` 的 changelog 分类配置
- `SECURITY.md` 和 `CONTRIBUTING.md`

### 6.2 仍缺失

- 无 Dockerfile / compose / 容器化运行资产
- 无 Dependabot / Snyk / Trivy / OWASP 依赖安全扫描
- 无 Spotless / Checkstyle / formatter 门禁
- 无 Maven Central 发布与签名配置
- 无仓库级统一覆盖率门禁；`demo` 直接跳过 JaCoCo check

### 6.3 结论

工程基础设施已经越过"裸仓库"阶段，但离"可持续发布"还有明显距离。当前更适合评价为 **B**，而不是旧版的 `A-`。

---

## 7. 文档质量评估

### 7.1 优点

- 主线文档齐全：README、PRD、设计、运维、机制说明都有。
- 文档密度高，能支持用户从 Demo 体验一路读到实现机制。
- 已开始区分"运行时 SSOT"和"阶段性评估/规划文档"。

### 7.2 已修复的文档漂移

以下先前报告中指出的文档漂移问题已修复：

| 漂移项 | 修复位置 |
|-------|---------|
| `DIFF_E_2015`（PREVIEW_TOKEN_EXPIRED）缺少文档 | `prd.md:695-697`、`ops-guide.md:271`、`design-doc.md:1722` |
| `DIFF_E_2016`（APPLY_COMPARE_TOO_OLD）缺少文档 | `prd.md:695-697`、`ops-guide.md:271`、`design-doc.md:1722` |
| `tenant-diff.apply.preview-token-ttl` 配置项未同步 | `ops-guide.md:271`、`design-doc.md:1722` |
| `tenant-diff.apply.max-compare-age` 配置项未同步 | `ops-guide.md:271`、`design-doc.md:1722` |

### 7.3 仍然存在的问题

- `project-assessment.md` 和 `evolution-roadmap.md` 过去曾明显滞后于代码，维护纪律仍需持续加强。
- Decision 默认暴露、PostgreSQL 支持边界、Quick Start 是否推荐先 preview 等点仍存在文档漂移。
- 仍无 OpenAPI / Swagger 自动化契约文档。

### 7.4 结论

文档量和可读性依然是项目优势。先前报告指出的 `DIFF_E_2015`/`DIFF_E_2016` 和 `preview-token-ttl`/`max-compare-age` 配置项漂移已修复，三份 SSOT 文档（PRD、设计、运维）之间的一致性有所改善。当前文档质量上调到 **B+**。

---

## 8. 数据安全评估

> 本章"安全"仅指数据一致性、失败可追溯、并发写入保护和回滚可验证，不涉及认证鉴权。

### 8.1 已落地控制

- Compare partial warning 已持久化，不再只写日志
- Apply 失败审计独立事务保留，不随业务事务一起丢失
- target tenant + datasource 粒度的跨 session 互斥租约已落地
- preview token TTL 与 compare freshness 已落地
- rollback snapshot 覆盖校验、drift confirm、verification summary 已落地

### 8.2 当前核心风险

- 级联 rollback 误判和子表 FK 错写风险本轮都曾被真实暴露，当前代码已修复；这说明该区域属于高风险演进面，后续任何重构都必须保留针对性回归测试。
- 外部数据源 rollback 仍不支持，能力边界必须继续明确。
- 鉴权 / RBAC / 审计留存策略仍未形成完整产品化方案。

### 8.3 结论

数据安全控制面比旧评估时期强得多，本轮又补上了 rollback 预期状态重建和 apply 外键映射两个真实缺口，因此当前上调到 `B`；但"安全"仍主要体现在一致性治理，不等于完整产品安全能力。

---

## 9. 生产就绪度评估

### 9.1 为什么仍是 C+

以下任一项单独存在，都足以阻止项目进入"可直接生产落地"的评级：

- 无认证鉴权 / RBAC / OpenAPI / 容器化资产
- PostgreSQL 非开箱即用
- 依赖安全扫描缺失

### 9.2 但比旧版本更成熟的地方

- 失败审计、compare warning、lease、freshness、drift confirm 都已落地
- standalone 覆盖率门禁已从旧版描述的低水平提升到 `30%`
- CI 不再只是"能编译"，而是会跑全量 `verify`

### 9.3 结论

当前项目处在 **"关键链路已恢复稳定，但距离完整生产化仍差最后一层平台能力"** 的阶段。生产就绪度上调为 `C+`，但还不应表述成"可直接生产落地"。

---

## 10. 距"标准化工具"的差距分析

### 先前 P0 阻塞项：已全部消除

以下在先前评估中列为 P0 核心阻塞的事项，现已通过新增 MySQL E2E 和文档同步得到解决：

| 先前 P0 阻塞项 | 消除证据 |
|---------------|---------|
| B_TO_A apply + rollback"零覆盖" | `MysqlAdversarialGuardrailE2ETest:200` |
| `DIFF_E_2001` maxAffectedRows 阈值无 E2E | `MysqlAdversarialGuardrailE2ETest:106` |
| `DIFF_E_1004` 重复 Apply 拒绝无 E2E | `MysqlAdversarialGuardrailE2ETest:158` |
| `DIFF_E_3002` rollback 并发冲突无 E2E | `MysqlRollbackConcurrencyE2ETest:49` |
| `DIFF_E_2015`/`DIFF_E_2016` 和配置项文档漂移 | `prd.md:695-697`、`ops-guide.md:271`、`design-doc.md:1722` |

### P1：仍应补强

1. 修复 `RollbackEndToEndTest#rollback_afterDelete_restoresDeletedRecords`（`:186`）—— 当前种子数据下永假跳过，应改为真正验证 DELETE rollback 或显式标注 `@Disabled`。
2. 为 `DIFF_E_0002`、`DIFF_E_1002` 补更强的 REST 层验证。
3. 为 `DiffDetailView` 三种模式（FULL / DIFF_ONLY / SUMMARY）补专门测试。
4. 为 `demo` 增加覆盖率门禁或其他等效质量闸门。
5. 增加 OpenAPI / Swagger 或等效契约文档。

### P2：中期优化项

1. 增加依赖安全扫描。
2. 增加容器化运行资产。
3. 明确 PostgreSQL 支持策略：补 DDL，或明确降级为手工适配。
4. 补产品对外定位与竞品对比材料。
5. 补发布签名、中央仓库发布治理。
6. 进一步提升 standalone 分支覆盖与复杂 rollback 链路测试强度。
7. 让评估/路线图文档和当前代码持续对齐，停止把历史结论当现状。

---

## 11. 最终结论

Tenant Diff 在本轮评估中完成了从"存在多个 P0 阻塞项"到"P0 清零、可发布但仍有少量 P1/P2 测试债"的跃迁：

- **B_TO_A 方向覆盖、maxAffectedRows 阈值、重复 Apply 拒绝、rollback 并发冲突** 四个先前 P0 级覆盖盲区已通过 MySQL 真实数据库 E2E 全部补齐
- **DIFF_E_2015/2016 和 preview-token-ttl / max-compare-age 文档漂移** 已在 PRD、设计、运维三份 SSOT 文档中同步修复
- 测试资产从先前的 8 个 demo 测试类扩展到 **18 个**（含 6 个 MySQL E2E），关键安全链路 ErrorCode 覆盖基本闭合

当前仍然存在的短板：

- `RollbackEndToEndTest` 中存在一个"永假/条件跳过"的可疑测试
- `DIFF_E_0002`、`DIFF_E_1002` REST 层验证和 `DiffDetailView` 三种模式缺少专门测试
- 工程化与产品化缺口（无认证鉴权、容器化、依赖扫描）仍在

本轮更新后的总评为 **A-**。这个结论反映的是：**质量门禁进展已显著超出先前评估所描述的状态，项目已从"被 P0 阻塞项卡住"进入"可发布，仍有少量 P1/P2 测试债和产品化缺口"的阶段**。
