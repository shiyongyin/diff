# Tenant Diff 演进路线

> SSOT 版本 | 最后更新：2026-03-22
> 本文档为项目演进规划的唯一权威源。
>
> 优先级原则：**功能正确 > 数据安全 > 工程规范 > 性能优化**
> 数据安全 = 失败后数据是否正确、回滚是否精确还原、并发下是否写错数据。
> 配置同步是低频运维操作，不是热路径，性能不是当前瓶颈。
> 依据：[项目评估](project-assessment.md)

---

## 目录

1. [优先级原则](#1-优先级原则)
2. [现状基线](#2-现状基线)
3. [Phase 1 -- 功能扎实（0.2.0）](#3-phase-1----功能扎实020)
4. [Phase 2 -- 数据安全（0.3.0）](#4-phase-2----数据安全030)
5. [Phase 3 -- 工程规范（0.4.0）](#5-phase-3----工程规范040)
6. [Phase 4 -- 生产就绪（0.5.0）](#6-phase-4----生产就绪050)
7. [Phase 5 -- 标准化工具（1.0.0）](#7-phase-5----标准化工具100)
8. [暂不排期项（性能 / 规模化）](#8-暂不排期项性能--规模化)

---

## 1. 优先级原则

### 1.1 为什么功能和数据安全优先

Tenant Diff 是一个**配置同步工具**，使用场景是：

- 实施阶段一次性或低频地将标准租户配置同步到客户租户
- 操作由实施顾问或运维人员主动触发
- 单次涉及的业务对象通常在百到千级别

这意味着：

| 关注点 | 优先级 | 理由 |
|--------|--------|------|
| 功能正确性 | **P0** | 同步错了等于引入生产事故，一条记录都不能错 |
| 数据安全 | **P0** | 失败必须回到一致状态，回滚必须精确还原，并发不能写错数据 |
| 审计可追溯 | **P1** | 出问题后必须能还原现场；当前已补 durable audit，但生产排障仍依赖这条链路 |
| 工程规范 | **P1** | 测试覆盖和 CI 门禁是功能正确性的守护网 |
| 可观测性 | **P2** | 生产环境需要但不阻塞当前阶段 |
| 性能优化 | **P3** | 低频操作，当前性能不是瓶颈，过早优化浪费资源 |
| 规模化 | **P3** | 异步/分布式锁等在需求明确后再做 |

### 1.2 本文中"数据安全"的定义

本文所有"安全"均指**数据层面的安全**，即：

- **失败一致性**：Apply 执行到一半失败了，目标租户数据是否能回到操作前的状态？
- **回滚精确性**：Rollback 之后，目标租户数据是否和 Apply 之前一模一样？
- **并发正确性**：两个人同时操作同一个租户的数据，是否会出现数据互相覆盖或写错的情况？
- **跨数据源一致性**：主库和外部数据源之间操作不是一个事务，一边成功一边失败怎么办？

不包括网络安全（认证鉴权）、人为恶意攻击等。

### 1.3 ROI 计算依据

- **收益** = 对功能正确性和数据安全的直接提升 + 消除已知风险
- **成本** = 开发工时 + 回归测试负担 + 破坏性变更风险
- 低频场景的性能优化 ROI 极低，除非触及了用户体验的硬边界

### 1.4 实现状态快照（2026-03-22）

| 范围 | 当前状态 | 说明 |
|------|----------|------|
| Phase 1（0.2.0） | **已完成** | warning 汇总、durable audit、rollback verify、preview TTL、30% JaCoCo 门禁均已落地 |
| Phase 2（0.3.0） | **基本完成** | 租约互斥、freshness、drift confirm、外部数据源审计补偿入口已落地；仍未支持外部数据源 rollback |
| Phase 3+ | **未开始 / 待推进** | 工程规范、发布能力、生产治理仍是后续路线 |

---

## 2. 现状基线

### 2.1 功能完成度

| 能力 | 状态 | 缺口 |
|------|------|------|
| Compare（对比） | 完整 | 局部失败已落 `warning_json` 并经 `GET /session/get` 暴露；仍是 partial success 语义，不会因为单个 businessKey 失败而中断全局 |
| Apply（同步） | 完整 | 已具备 durable audit、目标租约、freshness 校验、`estimatedAffectedRows` + `rows > 1` 防护；仍缺少独立 `GET /apply/{id}` 查询接口 |
| Rollback（回滚） | 部分 | 已支持 snapshot 覆盖校验、drift confirm、verify summary；仍仅支持主库，且不支持链式回滚 |
| Decision（审查） | 完整 | opt-in 设计，功能完备 |
| Selection（勾选） | 部分 | V1 仅主表（dependencyLevel=0），子表不可单独选择 |
| 多数据源 | 部分 | Compare/Apply + 外部数据源失败审计已支持；Rollback 仍不支持 |
| 多表级联 | 完整 | 外键替换、依赖层级排序均已实现 |

### 2.2 数据安全完成度

#### 失败一致性

| 场景 | 当前行为 | 问题 |
|------|----------|------|
| Apply 主库执行到一半 SQL 失败 | 主业务事务回滚；`ApplyAuditService` 用独立事务保留 `FAILED` 审计、`failureStage`、`failureActionId`、`diagnosticsJson` | **业务数据安全 + 审计可查**；失败 apply 的 snapshot 仍不会保留为可回滚快照 |
| Apply 外部数据源执行失败 | 外部数据源独立事务回滚；主库 `apply_record` 保留 `FAILED` 审计，并落 `targetDataSourceKey` / `failureStage` | **外部数据源业务数据安全 + 诊断可见**；仍不存在自动补偿 worker |
| Compare 过程中单个 Plugin 抛异常 | 该 businessKey 跳过，记录结构化 warning，并写入 session | **不阻断全局且 API 可见**；调用方需要自行识别“本次 compare 为 partial success” |

#### 回滚精确性

| 场景 | 当前行为 | 问题 |
|------|----------|------|
| 正常路径回滚 | 以快照为 source、当前 target 为 target 做 Diff 生成恢复计划并执行 | **可精确还原** |
| Apply 后目标数据被外部修改再回滚 | 先做 drift 检测；未确认时拒绝，确认后才继续 | **可见但非自动消解**：确认后仍会按快照覆盖当前数据 |
| Apply 的某些 businessKey 缺少快照 | 回滚前按 plan scope 校验 snapshot 覆盖 | **已 fail-fast**：返回 `DIFF_E_3003`，不会静默做不完整恢复 |
| 回滚本身执行失败 | `@Transactional` 全部回滚 | **安全**：回滚没有中间状态 |
| 回滚后再回滚（链式回滚） | **不支持** | 回滚不保存自身快照，无法撤销回滚操作 |

#### 并发正确性

| 场景 | 当前保护机制 | 问题 |
|------|-------------|------|
| 同一 session 并发 Apply | Session `@Version` 乐观锁 CAS（SUCCESS -> APPLYING）；二级防护检查同 session 无 SUCCESS apply_record | **安全**：后到的请求收到 `APPLY_CONCURRENT_CONFLICT` |
| 同一 apply 并发 Rollback | ApplyRecord `@Version` 乐观锁 CAS（SUCCESS -> ROLLING_BACK） | **安全**：后到的请求收到 `ROLLBACK_CONCURRENT_CONFLICT` |
| **不同 session 并发 Apply 到同一租户** | `xai_tenant_diff_apply_lease` 独立事务租约 + 唯一键互斥 | **安全**：后到请求收到 `APPLY_TARGET_BUSY` |
| Compare 和 Apply 并发（同一 session） | Session 状态机：RUNNING 期间不允许 Apply（需 SUCCESS） | **安全** |
| Plugin 线程安全 | `StandaloneBusinessTypePlugin` 明确单例/无状态契约；`StandalonePluginRegistry` 启动时扫描可变字段并告警 | **风险降低但未绝对消除**：框架能提示，不能替插件证明线程安全 |

#### 跨数据源一致性

| 场景 | 当前行为 | 问题 |
|------|----------|------|
| 外部数据源 Apply 写入成功，但应用在状态收尾前崩溃 | `RUNNING` / `FAILED` 审计先独立提交，外部库异常可通过残留审计定位 | **诊断窗口已显式化**；仍需人工补偿，不是自动恢复 |
| 外部数据源 Rollback | **直接拒绝**（`DIFF_E_3001`） | 安全但不完整 |

### 2.3 测试完成度

| 模块 | JaCoCo 门禁 | 实际覆盖 | 缺口 |
|------|-------------|----------|------|
| core | 50% | 充分 | 良好 |
| standalone | 30% | `30.97%`（`876/2829` line） | 已达到当前 Phase 1 门槛；距离 Phase 3 的 40% 还有空间 |
| demo（集成） | -- | 中高 | 已覆盖 rollback、并发租约、freshness、外部数据源审计，以及 compare partial warning |

### 2.4 Phase 1 ~ 2 落地摘要（按当前代码）

**已落地**

- Compare partial warning 已持久化到 `warning_json`，并通过 session summary 暴露。
- Apply 失败后已保留 durable audit，可定位 `failureStage`、`failureActionId`、`targetDataSourceKey`。
- Rollback 已具备 snapshot 覆盖校验、drift confirm、verification summary。
- 跨 session 同目标租户 Apply 已由 `apply_lease` 租约互斥。
- `previewToken` TTL、compare freshness、`rows > 1` 防护和 30% JaCoCo 门禁均已落地。

**仍未完成 / 明确保留**

- 外部数据源 Rollback（留在 Phase 4）
- Rollback 仍不支持外部数据源，也不支持链式回滚。
- Selection 仍只支持主表（`dependencyLevel=0`），子表不可单独勾选。
- 外部数据源异常后的补偿入口仍主要依赖 DB + 日志，没有自动清理/恢复机制。
- 仍没有面向运维的独立 apply 查询接口。

### 2.5 当前代码落地结论（M2 after implementation）

| 维度 | 结论 | 影响 |
|------|------|------|
| 表结构 | `warning_json`、`apply_record` 增量字段、`apply_lease` 表均已在 H2/MySQL/demo schema 落地 | 路线图不应再把这些对象写成“待新增” |
| 编排层 | `TenantDiffStandaloneServiceImpl`、`TenantDiffStandaloneApplyServiceImpl`、`TenantDiffStandaloneRollbackServiceImpl` 已承接 warning、durable audit、freshness、drift、verify 逻辑 | Phase 1 ~ 2 的主改造已经完成，后续主要是补缺口而非重做主流程 |
| 错误码 | `DIFF_E_1005`、`2007`、`2008`、`2015`、`2016`、`3003`、`3004` 均已存在于 `ErrorCode` | 文档可以按现有错误码收口；`DIFF_E_3005` 仍未真正落地 |
| 测试资产 | demo 已有 `ApplyFreshnessIntegrationTest`、`ConcurrentApplyTest`、`ExternalDataSourceApplyAuditTest`、`RollbackEndToEndTest`；standalone 已有 selection / registry / service 单测 | Phase 1 / 2 已具备稳定回归资产 |
| 覆盖率 | `tenant-diff-standalone` 已配置 30% line coverage 门禁，当前报告为 `30.97%` | Phase 1 门槛达成，Phase 3 可继续抬到 40% |
| 仍存漂移 | 任务索引和部分设计卡仍是“未开始”口径，与当前代码不符 | 文档应改为“已完成 / 部分完成 / 待推进”的现状表达 |

---

## 3. Phase 1 -- 功能扎实（0.2.0，已完成）

> 目标：**把“能跑通”收口为“能交付、能排障、能证明正确”**
> 预估周期：2-3 周
> 本期原则：优先做加列、补校验、补测试，不引入新工作流和新部署组件。

### 3.1 Intent / Constraints / Acceptance

| 支柱 | 内容 |
|------|------|
| Status | **已完成**。当前代码、DDL、错误码、测试和覆盖率已满足本阶段目标。 |
| Intent | 让 Compare / Apply / Rollback 这条主链路从“功能存在”升级为“输出完整、失败可查、结果可证”。 |
| Constraints | 不改现有 REST path；优先做 additive schema；不引入异步 compare、不实现外部数据源 rollback、不引入分布式锁。 |
| Acceptance Criteria | Compare partial warning 在 session 汇总中可见；Apply 失败后 `apply_record` 可查；Rollback 对 snapshot 缺失 fail-fast 且回传验证摘要；previewToken 有 TTL；standalone JaCoCo >= 30%。 |

### 3.2 事项设计

| # | 事项 | 工时 | 设计收口 | 状态 |
|---|------|------|----------|------|
| F-01 | **Compare warnings 结构化持久化与汇总暴露** | 1.5d | 将 `StandaloneTenantModelBuilder.BuildResult` 的 warning 从自由文本收口为结构化对象；新增 `xai_tenant_diff_session.warning_json`，`GET /session/get` 返回 `warningCount` + `warnings[]`，明确告诉调用方“本次 compare 结果不完整但已落库”。 | 已完成 |
| F-02 | **Compare 重跑状态契约** | 0.5d | `runCompare()` 仅允许 `CREATED/FAILED/SUCCESS`；显式拒绝 `APPLYING/ROLLING_BACK`。同时在设计文档中固定“scope 仅来自 session.scopeJson，既有 session 不支持改 scope”的语义。 | 已完成 |
| F-03 | **Apply 行数阈值语义收口** | 0.5d | 保留现有 `PlanBuilder` + `ApplyExecutorCore` 的 `estimatedAffectedRows` 双重校验，不再新增第三套阈值逻辑；补 `rows > 1` 视为异常的执行断言、错误码与回归测试。 | 已完成 |
| F-04 | **Apply 审计独立事务化** | 3d | 引入 committed audit service，`apply_record` 的创建/状态更新在独立事务提交；失败时业务数据回滚，但 `apply_record` 保留 `FAILED` + `failureStage` + `diagnosticsJson`。同时补齐 `targetTenantId` / `targetDataSourceKey` 元数据，为 Phase 2 铺路。 | 已完成 |
| F-05 | **Apply 重试语义收口** | 1d | 明确“同 session 只能有一个 SUCCESS/ROLLED_BACK；FAILED 可重试但历史记录必须保留”。`clientRequestId` 仍只做日志追踪，本期不做数据库级幂等。 | 已完成 |
| F-06 | **Rollback snapshot 覆盖校验** | 1.5d | 回滚前不再只检查“快照存在”，而是校验计划涉及的每个 `(businessType, businessKey)` 都有对应 snapshot；缺失时返回缺口列表并拒绝执行。 | 已完成 |
| F-07 | **Rollback 后验证摘要** | 1.5d | 回滚执行后重做 `snapshot vs current target` compare；将验证结果写入 `apply_record.verify_status / verify_json`，并回传到 rollback 响应。 | 已完成 |
| F-08 | **previewToken TTL** | 1d | token 升级为 `pt_v2_<epochSec>_<hash>`；在 Standalone Service 层做 TTL 校验，默认 `30m`，配置项新增 `tenant-diff.apply.preview-token-ttl`。 | 已完成 |
| F-09 | **standalone JaCoCo 门禁提升到 30%** | 3d | 把 standalone 模块门禁从 `3%` 提到 `30%`，重点覆盖 compare/apply/rollback 三个 service 的成功路径与主要拒绝路径。 | 已完成 |
| F-10 | **异常路径测试矩阵** | 2d | 覆盖 compare partial warning、apply failed audit retained、`rows > 1` 断言、rollback snapshot 缺失、preview token 过期。 | 已完成 |

### 3.3 合约、DDL 与失败语义

#### 3.3.1 Phase 1 合约收口

- `GET /api/tenantDiff/standalone/session/get`
  - 响应新增 `warningCount`、`warnings[]`
  - `warnings[]` 单项至少包含 `side`、`businessType`、`businessKey`、`message`
- `POST /api/tenantDiff/standalone/apply/rollback`
  - 响应新增 `verification`，至少包含 `success`、`remainingDiffCount`、`summary`
- `previewToken`
  - 同时兼容 `pt_v1_` 与 `pt_v2_` 一个 0.x 小版本，避免滚动升级期间前后端错配

#### 3.3.2 Phase 1 数据变更

| 对象 | 变更 | 用途 |
|------|------|------|
| `xai_tenant_diff_session` | 新增 `warning_json` | 持久化 compare partial warning 汇总 |
| `xai_tenant_diff_apply_record` | 新增 `target_tenant_id`、`target_data_source_key`、`failure_stage`、`failure_action_id`、`diagnostics_json`、`verify_status`、`verify_json` | 失败审计、目标定位、回滚验证、后续 Phase 2 一致性与互斥的基础字段 |

#### 3.3.3 Phase 1 错误矩阵

| 场景 | 错误码 | HTTP | 处理策略 |
|------|--------|------|---------|
| Compare 在 `APPLYING/ROLLING_BACK` 期间被重跑 | `DIFF_E_1005` | 409 | 拒绝重跑，保持 session 状态单向推进 |
| 单个 Apply action 影响行数 > 1 | `DIFF_E_2007` | 422 | 视为框架保护命中，整次 Apply 失败并保留 FAILED 审计 |
| `previewToken` 超过 TTL | `DIFF_E_2015` | 422 | 要求重新 preview 后再 execute |
| Rollback snapshot 覆盖不完整 | `DIFF_E_3003` | 422 | 返回缺失 `(businessType,businessKey)` 列表，不执行 rollback |

#### 3.3.4 可观测性

- Apply/rollback 关键日志字段统一为：`sessionId`、`applyId`、`targetTenantId`、`targetDataSourceKey`、`failureStage`、`failureActionId`、`verifyStatus`
- Compare warning 至少在 Session 汇总和日志中同时可见，避免“API 成功但日志有异常”这种隐性分叉
- `diagnostics_json` 只保存结构化排障信息，不重复堆栈；堆栈仍留在应用日志

### 3.4 实施路线图（M4）

#### 3.4.1 文件清单

| 模块 | 新增 / 修改文件 |
|------|----------------|
| `tenant-diff-core` | `.../domain/exception/ErrorCode.java`、`.../apply/PlanBuilder.java`（仅保留 token/hash 责任） |
| `tenant-diff-standalone` | `.../model/StandaloneTenantModelBuilder.java`、`.../service/impl/TenantDiffStandaloneServiceImpl.java`、`.../service/impl/TenantDiffStandaloneApplyServiceImpl.java`、`.../service/impl/TenantDiffStandaloneRollbackServiceImpl.java`、`.../snapshot/StandaloneSnapshotBuilder.java`、`.../persistence/entity/TenantDiffSessionPo.java`、`.../persistence/entity/TenantDiffApplyRecordPo.java`、`.../web/dto/response/DiffSessionSummaryResponse.java`、`.../web/dto/response/TenantDiffRollbackResponse.java`、`.../web/handler/TenantDiffStandaloneExceptionHandler.java`、`schema-h2.sql`、`schema-mysql.sql` |
| `tenant-diff-demo` | `schema.sql`、Session/Apply/Rollback 相关集成测试 |
| 测试 / 构建 | `tenant-diff-standalone/pom.xml`、standalone/demo 测试文件 |

#### 3.4.2 DoD / 验证标准

- [x] `./mvnw -pl tenant-diff-standalone -am test` 通过
- [x] `./mvnw -pl tenant-diff-demo -am test -Dtest=DemoSessionApiIntegrationTests,ApplyTransactionBoundaryTest,RollbackEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false` 通过
- [x] 创建带坏数据的 compare 用例后，`GET /session/get` 能返回 `warningCount > 0`
- [x] 伪造失败 Apply 后，数据库中存在 `FAILED` 状态的 `apply_record`
- [x] 缺 snapshot 的 rollback 返回 `422 + DIFF_E_3003`
- [x] 使用过期 `previewToken` 的 PARTIAL execute 返回 `422 + DIFF_E_2015`
- [x] standalone 模块 JaCoCo line coverage `>= 30%`（当前报告 `30.97%`）

#### 3.4.3 任务卡映射

- `docs/tasks/evolution-roadmap/TASK-ER-01.md`：Compare warning 与 session 合约
- `docs/tasks/evolution-roadmap/TASK-ER-02.md`：Apply durable audit、阈值语义、preview TTL
- `docs/tasks/evolution-roadmap/TASK-ER-03.md`：Rollback 覆盖校验与验证摘要
- `docs/tasks/evolution-roadmap/TASK-ER-06.md`：Phase 1 / 2 测试矩阵与门禁

### Phase 1 总投入：~16 人天

### Phase 1 验收标准

1. Compare partial failure 不再只存在日志里，`GET /session/get` 可见 warning 摘要。
2. `runCompare()` 对写入态 session 明确拒绝，状态语义无歧义。
3. Apply 失败后 DB 中可查到 `FAILED` 状态的 `apply_record`，且包含目标定位与失败阶段。
4. `rows > 1` 的异常写入会被拒绝并进入 FAILED 审计，不会静默吞掉。
5. Rollback 前 snapshot 缺失时明确报错；Rollback 后有可查询的验证摘要。
6. 过期 `previewToken` 不能再执行 PARTIAL apply。
7. standalone 模块 JaCoCo 覆盖率 `>= 30%`，异常路径测试通过。

---

## 4. Phase 2 -- 数据安全（0.3.0，已基本完成）

> 目标：**把“有审计”升级为“失败、漂移、并发下仍然数据正确”**
> 预估周期：2-3 周
> 前置：Phase 1 完成
>
> 本阶段所有“安全”均指数据层面：失败一致、回滚精确、互斥正确、漂移可见。

### 4.1 Intent / Constraints / Acceptance

| 支柱 | 内容 |
|------|------|
| Status | **已基本完成**。主流程的数据安全能力已经落地，剩余主要缺口是外部数据源 rollback 与更细粒度的 snapshot 结构化错误语义。 |
| Intent | 在 Apply 失败、目标租户被外部修改、多个 session 并发写入时，系统仍能给出正确拒绝或明确诊断。 |
| Constraints | 不引入 Redis/分布式锁；仍不支持外部数据源 rollback；优先复用 Phase 1 的 durable audit 字段；Controller 继续保持薄层。 |
| Acceptance Criteria | 外部数据源 crash 后有可见 RUNNING/FAILED 审计；Rollback 漂移需要显式确认；同目标租户并发 Apply 由租约拒绝；Compare 结果过旧被拒绝执行。 |

### 4.2 事项设计

| # | 事项 | 工时 | 设计收口 | 状态 |
|---|------|------|----------|------|
| D-01 | **Apply 失败断点诊断** | 2d | 在 durable audit 上补齐 `failureStage`、`failureActionId`、`diagnosticsJson` 的写入约定。失败时日志与 DB 都能定位到最后一个 action。 | 已完成 |
| D-02 | **外部数据源 Apply 一致性补偿** | 3d | 对外部数据源执行沿用 Phase 1 的独立审计事务：先提交 `RUNNING` 审计，再执行外部数据源事务，最后独立更新为 `SUCCESS/FAILED`。应用崩溃时，残留 `RUNNING` 审计即为人工补偿入口。 | 已完成 |
| D-03 | **Apply 前后校验摘要** | 2d | 复用 snapshot/model builder，对本次 scope 生成 before/after 校验摘要；若 after 校验仍有剩余 diff，则把 warning 写入 `diagnostics_json`，避免“成功但无证据”。 | 已完成 |
| D-04 | **Rollback 漂移检测 + 明确确认** | 2.5d | `ApplyRollbackRequest` 新增 `acknowledgeDrift=false`。若检测到 Apply 后目标数据被外部修改，则第一次调用返回 `409 + DIFF_E_3004`；调用方显式确认后才能继续。 | 已完成 |
| D-05 | **Rollback 强校验** | 1.5d | 在 Phase 1 的 business 级覆盖校验之上，进一步解析 snapshot business model，确认计划涉及的表/记录键在 snapshot 内部可定位；损坏 snapshot 直接拒绝回滚。 | 部分完成 |
| D-06 | **跨 session 同目标租约互斥** | 3d | 新增 `xai_tenant_diff_apply_lease` 表；Apply 前在独立事务获取 `(targetTenantId, normalizedTargetDataSourceKey)` 租约，结束后释放。租约带 `expiresAt`，崩溃后可自动恢复。 | 已完成 |
| D-07 | **Plugin 线程安全契约** | 1d | 在 `StandaloneBusinessTypePlugin` 的 Javadoc 中明确“Spring 单例 + 必须无状态或自证线程安全”；`StandalonePluginRegistry` 启动时对可疑可变字段打印 WARN。 | 已完成 |
| D-08 | **Compare 结果时效保护** | 1d | Apply 时检查 `session.finishedAt` 是否超过 `tenant-diff.apply.max-compare-age`；过旧的 compare 结果直接拒绝执行。 | 已完成 |
| D-09 | **失败一致性集成测试** | 2d | 覆盖：主库失败 retain audit、外部数据源 crash retain running audit、断点位置持久化。 | 已完成 |
| D-10 | **回滚精确性集成测试** | 2d | 覆盖：drift 检测二次确认、snapshot 损坏拒绝、rollback verify summary。 | 已完成 |
| D-11 | **并发正确性集成测试** | 2d | 覆盖：同一目标租户跨 session 租约冲突、同一 apply 并发 rollback、compare 过旧拒绝。 | 已完成 |

### 4.3 合约、DDL 与失败语义

#### 4.3.1 Phase 2 合约收口

- `POST /api/tenantDiff/standalone/apply/rollback`
  - 请求新增 `acknowledgeDrift`，默认 `false`
  - 响应新增 `driftDetected`、`verification`、`diagnostics`
- `POST /api/tenantDiff/standalone/apply/execute`
  - 不新增 path；失败/冲突继续走统一 `TenantDiffException`
- Apply 租约 key 统一使用 `(targetTenantId, normalizedTargetDataSourceKey)`，其中主库一律写成 `"primary"`

#### 4.3.2 Phase 2 数据变更

| 对象 | 变更 | 用途 |
|------|------|------|
| `xai_tenant_diff_apply_lease` | 新表：`target_tenant_id`、`target_data_source_key`、`session_id`、`apply_id`、`lease_token`、`leased_at`、`expires_at` | 跨 session、跨事务可见的目标租户写入租约 |
| `xai_tenant_diff_apply_record` | 继续复用 Phase 1 字段，不再新增第二套审计表 | 统一承载失败诊断、校验摘要、外部数据源补偿入口 |

#### 4.3.3 Phase 2 错误矩阵

| 场景 | 错误码 | HTTP | 处理策略 |
|------|--------|------|---------|
| 同目标租户已有活动租约 | `DIFF_E_2008` | 409 | 拒绝当前 Apply，提示稍后重试或检查卡住的租约 |
| Compare 结果超过可接受时窗 | `DIFF_E_2016` | 409 | 要求重新创建/执行 compare |
| Rollback 检测到目标数据漂移且未确认 | `DIFF_E_3004` | 409 | 返回 drift 摘要，要求调用方显式确认 |
| Snapshot 覆盖不完整或结构无法支持当前计划 | 当前统一返回 `DIFF_E_3003` | 422 | 已 fail-fast；独立 `DIFF_E_3005` 仍未落地 |

#### 4.3.4 可观测性

- 审计/租约共用排障主键：`applyId`、`sessionId`、`targetTenantId`、`targetDataSourceKey`
- 新增日志字段：`leaseToken`、`leaseExpiresAt`、`driftDetected`、`compareAgeSeconds`
- `RUNNING` 审计与租约都必须有 `startedAt/leasedAt`，便于区分“仍在执行”与“僵尸记录”

### 4.4 实施路线图（M4）

#### 4.4.1 文件清单

| 模块 | 新增 / 修改文件 |
|------|----------------|
| `tenant-diff-core` | `.../domain/exception/ErrorCode.java`、必要时新增校验摘要/诊断小模型 |
| `tenant-diff-standalone` | `.../service/impl/TenantDiffStandaloneApplyServiceImpl.java`、`.../service/impl/TenantDiffStandaloneRollbackServiceImpl.java`、`.../apply/SessionBasedApplyExecutor.java`、`.../plugin/StandaloneBusinessTypePlugin.java`、`.../plugin/StandalonePluginRegistry.java`、`.../web/dto/request/ApplyRollbackRequest.java`、`.../web/dto/response/TenantDiffRollbackResponse.java`、新的 lease entity/mapper/service、H2/MySQL schema |
| `tenant-diff-demo` | 并发/漂移/外部数据源补偿相关集成测试 |
| 测试 / 构建 | demo/standalone 测试文件、必要的 test fixture |

#### 4.4.2 DoD / 验证标准

- [x] `./mvnw -pl tenant-diff-standalone -am test` 通过
- [x] `./mvnw -pl tenant-diff-demo -am test -Dtest=ConcurrentApplyTest,ApplyTransactionBoundaryTest,RollbackEndToEndTest,ServiceLayerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` 通过
- [x] 同一目标租户、不同 session 并发 Apply 时，后到请求返回 `409 + DIFF_E_2008`
- [x] 外部数据源 Apply 人为制造中断后，数据库中存在 `RUNNING` 或 `FAILED` 的 `apply_record`
- [x] Rollback 漂移检测第一次返回 `409 + DIFF_E_3004`，二次携带 `acknowledgeDrift=true` 后才允许继续
- [x] Compare 超时窗后执行 Apply 返回 `409 + DIFF_E_2016`
- [x] rollback 验证摘要能告诉调用方剩余 diff 是否为零

#### 4.4.3 任务卡映射

- `docs/tasks/evolution-roadmap/TASK-ER-04.md`：Apply 一致性、外部数据源补偿与断点诊断
- `docs/tasks/evolution-roadmap/TASK-ER-05.md`：Rollback 漂移检测、目标租约、compare 时效保护
- `docs/tasks/evolution-roadmap/TASK-ER-06.md`：Phase 1 / 2 测试矩阵与门禁

### 4.5 质量门槛（M4 -> M5 唯一门禁）

| 维度 | 证据 | 已知不足 |
|------|------|----------|
| 合约明确 | `session/get`、`apply/rollback` 的增量字段与错误码已写死；配置 key 明确到 `tenant-diff.apply.*` | 仍没有单独的 `GET /apply/{id}` 查询接口，历史诊断主要靠 DB 与日志 |
| 失败覆盖 | Compare partial warning、Apply failed audit、外部数据源 crash、Rollback drift、租约冲突均有明确语义 | 外部数据源 rollback 仍不支持，需 Phase 4 解决 |
| 可验收 | 每个阶段都给出可复制命令与 HTTP 断言；任务卡进一步拆到文件级 | 尚未把这些命令接入 CI workflow，需实现阶段同步补齐 |
| 范围清晰 | Phase 1/2 明确不做异步 compare、分布式锁、性能优化 | 若未来部署为多实例高频写入，数据库租约可能需要升级到更强协调机制 |
| 架构一致 | Controller 继续薄层；新增 audit/lease 只落在基础设施服务，不向 plugin 泄露事务责任 | `tenant-diff.apply.*` 配置仍是局部键，Phase 3 可再统一收敛到 `TenantDiffProperties` |

**已知不足**

- **What**：外部数据源 rollback 仍然缺席，租约也仅覆盖 Standalone Apply，不覆盖未来异步 worker。
- **So what**：Phase 2 解决的是“写前互斥 + 写后诊断”，不是“跨数据源完全可恢复”。
- **Next**：Phase 4 引入外部数据源 rollback 与长期治理能力；若部署拓扑升级，再评估更强锁模型。

### Phase 2 总投入：~20 人天

### Phase 2 验收标准

1. Apply 执行到中途失败时，业务数据回到 Apply 前状态，且 `apply_record` 中保留断点诊断。
2. 外部数据源 Apply 中途崩溃后，可通过 `RUNNING/FAILED` 审计记录定位不一致窗口。
3. Rollback 在检测到外部漂移时不会静默覆盖，必须显式确认。
4. Rollback 后返回验证摘要，调用方可以知道是否仍存在残余 diff。
5. 两个不同 session 并发 Apply 到同一目标租户时，后到请求会被租约拒绝。
6. Compare 结果过旧时不能继续 Apply。
7. 所有数据安全测试通过。

---

## 5. Phase 3 -- 工程规范（0.4.0）

> 目标：**建立可持续维护的工程基线，为开源发布做准备**
> 预估周期：2-3 周
> 前置：Phase 2 完成

### 5.1 CI/CD 加固

| # | 事项 | 工时 | 说明 |
|---|------|------|------|
| E-01 | **启用 Dependabot** | 0.5d | 添加 `.github/dependabot.yml`，自动发现依赖安全漏洞 |
| E-02 | **添加 Spotless 自动格式化** | 0.5d | 配置到 CI，`mvn spotless:check` 失败则构建失败 |
| E-03 | **standalone JaCoCo 门禁提升到 40%** | 2d | 在 Phase 1 的 30% 基础上继续补测试，覆盖更多边界条件 |

### 5.2 开箱即用

| # | 事项 | 工时 | 说明 |
|---|------|------|------|
| E-04 | **Dockerfile + docker-compose** | 1d | Demo 模块打包为镜像，`docker compose up` 即可体验。包含 H2 内存库 + 预置种子数据 |
| E-05 | **OpenAPI / Swagger 集成** | 2d | 添加 `springdoc-openapi-starter`，自动生成 API 文档。Demo 启动后访问 `/swagger-ui.html` 即可交互 |

### 5.3 发布准备

| # | 事项 | 工时 | 说明 |
|---|------|------|------|
| E-06 | **Maven Central 发布流水线** | 3d | 确认 groupId 归属（`com.diff` 或 `io.github.*`）、配置 GPG 签名、Sonatype OSSRH、GitHub Actions release workflow |
| E-07 | **SPI 稳定性标记** | 1d | 在 `StandaloneBusinessTypePlugin`、`BusinessApplySupport` 等公共 SPI 上添加 `@Stable` / `@Evolving` 注解，明确哪些接口承诺兼容 |

### 5.4 文档补全

| # | 事项 | 工时 | 说明 |
|---|------|------|------|
| E-08 | **竞品对标文档** | 1d | 对比 Flyway（Schema 级）、自研脚本、平台内建同步、手工导出导入，说明 Tenant Diff 的差异化价值 |
| E-09 | **Migration Guide 模板** | 0.5d | 为未来版本升级提供标准化迁移指南模板 |

### Phase 3 总投入：~12 人天

### Phase 3 验收标准

1. `docker compose up` 后可通过 Swagger UI 完成完整流程演示
2. CI 包含格式化检查 + 依赖安全扫描 + 覆盖率门禁（standalone >= 40%）
3. `mvn deploy` 可发布到 Maven Central 或 Staging
4. 所有公共 SPI 有 `@Stable` / `@Evolving` 标记

---

## 6. Phase 4 -- 生产就绪（0.5.0）

> 目标：**具备真实生产环境部署条件**
> 预估周期：3-4 周
> 前置：Phase 3 完成

### 6.1 可观测性

| # | 事项 | 工时 | 说明 |
|---|------|------|------|
| P-01 | **Micrometer Metrics 集成** | 3d | 核心指标：`tenant_diff_session_created_total`、`tenant_diff_apply_duration_seconds`、`tenant_diff_apply_total{status=success/failure}`、`tenant_diff_rollback_total` |
| P-02 | **Actuator 健康检查端点** | 1d | 实现 `HealthIndicator`，检查 Plugin 注册表非空 + 主数据源可用 |

### 6.2 生产治理

| # | 事项 | 工时 | 说明 |
|---|------|------|------|
| P-03 | **内建数据清理调度** | 5d | `@Scheduled` 定期清理过期 session / result / snapshot。可配置保留窗口（默认 session 永久、result 90d、snapshot 30d）。清理前检查回滚窗口是否已关闭 |
| P-04 | **请求限流** | 2d | 对 `session/create` 和 `apply/execute` 可配置令牌桶限流。默认关闭，`tenant-diff.rate-limit.enabled=true` 启用 |

### 6.3 功能增强

| # | 事项 | 工时 | 说明 |
|---|------|------|------|
| P-05 | **多数据源回滚** | 5d | 在 `apply_record` 中持久化 `targetDataSourceKey`，回滚时路由到对应数据源。消除 v1 最大功能限制 |
| P-06 | **子表联动选择（Selection V2）** | 5d | `selectionMode=PARTIAL` 选择主表动作时，自动关联同一 businessKey 下的子表动作。消除"仅主表"限制 |

### Phase 4 总投入：~21 人天

### Phase 4 验收标准

1. Grafana 可对接基础监控面板，核心指标可观测
2. `/actuator/health/tenantDiff` 端点返回正常
3. 配置数据清理后，过期数据被自动清理且不影响活跃回滚窗口
4. 外部数据源方向的 Apply 可以被回滚
5. PARTIAL 模式可选择主表并自动关联子表

---

## 7. Phase 5 -- 标准化工具（1.0.0）

> 目标：**正式作为"多租户 SaaS 领域标准化配置同步工具"对外定位**
> 预估周期：2-3 月
> 前置：Phase 4 完成

| # | 事项 | 工时 | 说明 |
|---|------|------|------|
| G-01 | **API / SPI 兼容性承诺** | 3d | SPI breaking change 需经过一个 minor 版本的 deprecation 周期 |
| G-02 | **官方文档站点** | 5d | GitHub Pages / Docusaurus，含搜索、版本切换 |
| G-03 | **Plugin 模板库** | 持续 | 提供常见场景的 Plugin 模板（权限配置、审批流程、API 模板、枚举值等） |
| G-04 | **OpenTelemetry Tracing** | 3d | 每个 session 生命周期作为一个 trace |
| G-05 | **至少 2 个外部使用案例** | -- | 非本团队的外部使用者在生产环境运行 |

### 1.0.0 验收标准

1. 兼容性承诺生效
2. 外部用户可通过 Maven Central 引用 + Docker 体验 + 文档站点学习
3. 有非本团队的生产使用案例

---

## 8. 暂不排期项（性能 / 规模化）

以下事项在当前场景下 ROI 不高，记录但不排入近期计划。当出现以下触发条件时再评估：

| 事项 | 触发条件 | 说明 |
|------|----------|------|
| Batch INSERT 优化 | 单次 Apply > 1000 条 INSERT 且耗时影响用户体验 | JDBC Batch 替代逐条 INSERT |
| 增量对比 | 单次 Compare 涉及 > 10000 条记录且超时 | 基于 `data_modify_time` 增量加载 |
| 异步对比 | Compare 超时成为阻塞问题 | `session/create` 异步 + 状态轮询 |
| 分布式锁 | 实际部署多实例且 D-06 的 DB 查询方案不够用 | Redis / 数据库行锁 |
| 指纹算法升级 | MD5 计算成为 CPU 瓶颈（概率极低） | 切换为 xxHash / Murmur |
| 性能基准测试 | 需要对外公布性能数据 | JMH + Gatling |

**原则**：不为假想的性能问题提前投资。当真实用户反馈性能瓶颈时，用数据定位后再针对性优化。

---

## 附录：Phase 间依赖关系

```
Phase 1（功能扎实）
  |
  v
Phase 2（数据安全）
  |
  v
Phase 3（工程规范）──── 可开始接受外部贡献
  |
  v
Phase 4（生产就绪）──── 可进入生产环境
  |
  v
Phase 5（标准化工具）── 1.0.0 发布
```

每个 Phase 有明确的验收标准。只有通过验收才推进到下一阶段。不跳阶段，不并行多 Phase。

## 附录：全量事项速查表

| Phase | 编号 | 事项 | 工时 | 分类 |
|-------|------|------|------|------|
| 1 | F-01 | Compare warnings 结构化持久化与汇总暴露 | 1.5d | 功能 |
| 1 | F-02 | Compare 重跑状态契约 | 0.5d | 功能 |
| 1 | F-03 | Apply 行数阈值语义收口 | 0.5d | 功能 |
| 1 | F-04 | Apply 失败审计不丢失 | 3d | 功能 |
| 1 | F-05 | Apply 重试语义收口 | 1d | 功能 |
| 1 | F-06 | Rollback snapshot 覆盖校验 | 1.5d | 功能 |
| 1 | F-07 | Rollback 后验证摘要 | 1.5d | 功能 |
| 1 | F-08 | previewToken 过期时间 | 1d | 功能 |
| 1 | F-09 | standalone JaCoCo >= 30% | 3d | 测试 |
| 1 | F-10 | 异常路径测试 | 2d | 测试 |
| 2 | D-01 | Apply 失败断点诊断 | 2d | 失败一致性 |
| 2 | D-02 | 外部数据源 Apply 一致性补偿 | 3d | 失败一致性 |
| 2 | D-03 | Apply 前后校验摘要 | 2d | 失败一致性 |
| 2 | D-04 | Rollback 漂移检测 + 明确确认 | 2.5d | 回滚精确性 |
| 2 | D-05 | Rollback 强校验 | 1.5d | 回滚精确性 |
| 2 | D-06 | 跨 session 同目标租约互斥 | 3d | 并发正确性 |
| 2 | D-07 | Plugin 线程安全契约 | 1d | 并发正确性 |
| 2 | D-08 | Compare 结果时效保护 | 1d | 并发正确性 |
| 2 | D-09 | 失败一致性集成测试 | 2d | 测试 |
| 2 | D-10 | 回滚精确性集成测试 | 2d | 测试 |
| 2 | D-11 | 并发正确性集成测试 | 2d | 测试 |
| 3 | E-01 | Dependabot | 0.5d | 工程 |
| 3 | E-02 | Spotless 格式化 | 0.5d | 工程 |
| 3 | E-03 | standalone JaCoCo >= 40% | 2d | 测试 |
| 3 | E-04 | Dockerfile + docker-compose | 1d | 工程 |
| 3 | E-05 | OpenAPI / Swagger | 2d | 工程 |
| 3 | E-06 | Maven Central 发布 | 3d | 工程 |
| 3 | E-07 | SPI 稳定性标记 | 1d | 工程 |
| 3 | E-08 | 竞品对标文档 | 1d | 文档 |
| 3 | E-09 | Migration Guide 模板 | 0.5d | 文档 |
| 4 | P-01 | Micrometer Metrics | 3d | 可观测 |
| 4 | P-02 | Actuator 健康检查 | 1d | 可观测 |
| 4 | P-03 | 数据清理调度 | 5d | 治理 |
| 4 | P-04 | 请求限流 | 2d | 治理 |
| 4 | P-05 | 多数据源回滚 | 5d | 功能 |
| 4 | P-06 | 子表联动选择 V2 | 5d | 功能 |
| 5 | G-01 | API/SPI 兼容性承诺 | 3d | 治理 |
| 5 | G-02 | 官方文档站点 | 5d | 文档 |
| 5 | G-03 | Plugin 模板库 | 持续 | 生态 |
| 5 | G-04 | OpenTelemetry Tracing | 3d | 可观测 |
| 5 | G-05 | 外部使用案例 | -- | 生态 |
| -- | -- | Batch INSERT | 待定 | 性能（暂不排期） |
| -- | -- | 增量对比 | 待定 | 性能（暂不排期） |
| -- | -- | 异步对比 | 待定 | 规模化（暂不排期） |
| -- | -- | 分布式锁 | 待定 | 规模化（暂不排期） |
