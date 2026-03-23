# tenant-diff-demo 端到端测试报告

## 1. 概述

### 1.1 被测系统

**tenant-diff** 是一个多租户数据差异比对与同步框架，提供以下核心能力：

| 能力 | 描述 |
|------|------|
| **比对（Compare）** | 对比源租户与目标租户的业务数据差异 |
| **预览（Preview）** | 生成待执行的 Action 列表，不产生任何写入 |
| **决策（Decision）** | 对单条记录标记 SKIP/APPLY 决策 |
| **同步（Apply）** | 将差异写入目标租户（支持 INSERT/UPDATE/DELETE） |
| **回滚（Rollback）** | 基于快照将目标租户数据还原到同步前状态 |

### 1.2 测试目标

验证上述完整生命周期在 **H2 内存数据库** 和 **真实 MySQL** 两种环境下的正确性、安全性和并发一致性。

### 1.3 业务模型

测试使用两种业务模型覆盖从单表到多层级联的场景：

| 模型 | 表结构 | 层级 | 用途 |
|------|--------|------|------|
| **EXAMPLE_PRODUCT** | `example_product` | 单表 | 基础 CRUD、安全阈值、并发互斥 |
| **EXAMPLE_ORDER** | `example_order` → `example_order_item` → `example_order_item_detail` | 三层父子孙 | 外键替换、依赖排序、级联同步/回滚 |

### 1.4 种子数据

| 表 | 租户 1（源） | 租户 2（目标） | 预期差异 |
|----|-------------|---------------|---------|
| example_product | PROD-001, PROD-002, PROD-003 | PROD-001, PROD-002(价格不同) | PROD-001=NOOP, PROD-002=UPDATE, PROD-003=INSERT |
| example_order | ORD-001(2子项), ORD-002(1子项) | ORD-001(1子项,字段不同), ORD-DEL | ORD-001=UPDATE, ORD-002=INSERT, ORD-DEL=DELETE |

---

## 2. 测试架构

### 2.1 双环境策略

```
┌─────────────────────────────────────────────────────────────────────┐
│                        测试金字塔                                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────────────────────────────────────────────────┐       │
│  │  MySQL E2E Tests（6 个测试类，真实 MySQL）                  │       │
│  │  @ActiveProfiles({"test", "mysql-e2e"}) + 显式环境变量门禁   │       │
│  │  验证：SQL 方言、Trigger、事务隔离、InnoDB 锁、并发         │       │
│  └──────────────────────────────────────────────────────────┘       │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────┐       │
│  │  H2 Integration Tests（12 个测试类，H2 内存数据库）         │       │
│  │  @ActiveProfiles("test")                                  │       │
│  │  验证：Service 编排、API 契约、事务边界、状态机             │       │
│  └──────────────────────────────────────────────────────────┘       │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.2 环境配置

| 环境 | Profile | 数据库 | 配置文件 | 初始化脚本 |
|------|---------|--------|---------|-----------|
| H2 集成测试 | `test` | H2 (MODE=MYSQL) | `application.yml` | `schema.sql`（自动建表） |
| MySQL 端到端 | `test, mysql-e2e` | MySQL 5.7+ / 8.0 | `application-mysql-e2e.yml` | `schema-mysql-e2e.sql` |

### 2.3 测试隔离

所有测试类均使用 `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)` 保证每个测试方法后重建 Spring 上下文，避免跨测试数据污染。

---

## 3. 测试用例清单

### 3.1 H2 集成测试（12 个测试类）

#### 3.1.1 DemoApplicationTests — 上下文冒烟测试

| # | 测试方法 | 测什么 | 为什么 |
|---|---------|--------|--------|
| 1 | `contextLoads` | Spring 上下文能否正常启动 | 最基本的冒烟测试，确保配置、Bean 依赖无误 |

#### 3.1.2 ServiceLayerIntegrationTest — Service 层编排（16 个用例）

**测什么：** 直接调用 Service 接口验证 createSession/runCompare/apply/rollback 的编排逻辑和状态机转换。

**为什么：** 与 API 层测试互补——API 层验证 HTTP 契约，Service 层验证更细粒度的业务逻辑和边界场景。

| # | 分组 | 测试方法 | 验证点 |
|---|------|---------|--------|
| 1 | createSession | `validRequest_returnsSessionId` | 正常创建返回有效 ID |
| 2 | createSession | `afterCreate_statusIsCreated` | 创建后状态为 CREATED |
| 3 | createSession | `nullRequest_throws` | null 请求 → IllegalArgumentException |
| 4 | createSession | `nullSourceTenantId_throws` | null sourceTenantId → 异常 |
| 5 | createSession | `emptyBusinessTypes_throws` | 空 businessTypes → 异常 |
| 6 | runCompare | `afterCompare_statusIsSuccess` | 对比完成状态为 SUCCESS |
| 7 | runCompare | `compare_persistsResultsWithStatistics` | 结果持久化 + 统计正确 |
| 8 | runCompare | `rerunCompare_isIdempotent` | 重跑幂等不报错 |
| 9 | runCompare | `nullSessionId_throws` | null sessionId → 异常 |
| 10 | runCompare | `nonExistentSession_throws` | 不存在的 session → TenantDiffException |
| 11 | 查询 | `listBusiness_returnsPaginatedResults` | 分页查询结构正确 |
| 12 | 查询 | `listBusiness_filtersByBusinessType` | 按 businessType 过滤 |
| 13 | 查询 | `listBusiness_filtersByDiffType` | 按 diffType 过滤 |
| 14 | 查询 | `getDetail_returnsDiffJson` | 查看差异详情 |
| 15 | 查询 | `getDetail_nonExistentKey_returnsEmpty` | 不存在的 key → empty |
| 16 | 查询 | `getDetail_blankBusinessType_throws` | 空 businessType → 异常 |
| 17 | Apply | `buildPlan_loadsFromDb` | 从 DB 加载 diff 重建 Plan |
| 18 | Apply | `buildPlan_nonExistentSession_throws` | 不存在的 session → 异常 |
| 19 | Apply | `execute_success_persistsRecordAndSnapshot` | 成功后 record+snapshot 已保存 |
| 20 | Apply | `execute_writesTargetTenantData` | 目标租户数据已变更 |
| 21 | Rollback | `rollback_restoresTargetData` | 恢复到 apply 前状态 |
| 22 | Rollback | `rollback_nonExistentApplyId_throws` | 不存在的 applyId → 异常 |
| 23 | Rollback | `rollback_nullApplyId_throws` | null applyId → 异常 |
| 24 | 状态守卫 | `apply_beforeCompareComplete_shouldFail` | 未完成对比 → SESSION_NOT_READY |
| 25 | 状态守卫 | `apply_duplicateOnSameSession_shouldFail` | 重复 apply → SESSION_ALREADY_APPLIED |
| 26 | 状态守卫 | `rollback_failedApply_shouldFail` | FAILED 的 apply → APPLY_NOT_SUCCESS |
| 27 | 状态守卫 | `rollback_duplicate_shouldFail` | 重复回滚 → APPLY_ALREADY_ROLLED_BACK |
| 28 | 状态守卫 | `rollback_runningApply_shouldFail` | RUNNING 状态 → APPLY_NOT_SUCCESS |
| 29 | 状态守卫 | `apply_afterRollback_shouldFail` | 回滚后再 apply → SESSION_ALREADY_APPLIED |

#### 3.1.3 DemoSessionApiIntegrationTests — Session API 契约

| # | 测试方法 | 验证点 |
|---|---------|--------|
| 1 | `healthCheckReturnsStructuredSessionNotFound` | sessionId=0 → 404 + DIFF_E_1001 + 标准错误结构 |
| 2 | `createSessionAndQueryBusinessResults` | 创建→get→listBusiness→getBusinessDetail 完整查询链 |

#### 3.1.4 DemoApplyApiIntegrationTests — Apply API 契约

| # | 测试方法 | 验证点 |
|---|---------|--------|
| 1 | `previewShouldReturnStatisticsWithoutWriting` | 预览返回统计和 businessTypePreviews |
| 2 | `executeApplyAndRollbackThroughApi` | HTTP API 层面的 apply→rollback 完整链路 |

#### 3.1.5 DemoSessionWarningIntegrationTests — 告警结构化输出

| # | 测试方法 | 验证点 |
|---|---------|--------|
| 1 | `createSession_whenPluginPartiallyFails_exposesStructuredWarnings` | 插件部分失败 → warningCount=1 + 结构化告警字段 |

**测试手段：** 注入 `FaultInjectingJdbcTemplate` 使 PROD-003 加载时抛异常。

#### 3.1.6 ApplyExecutorIntegrationTest — 执行引擎单元级集成（9 个用例）

**测什么：** 直接调用 `ApplyExecutorCore` 验证 SQL 写库逻辑和安全约束。

| # | 分组 | 测试方法 | 验证点 |
|---|------|---------|--------|
| 1 | INSERT | `insert_should_write_data_and_record_id_mapping` | 写入数据 + 记录 IdMapping |
| 2 | UPDATE | `update_should_modify_fields` | 字段值变更正确 |
| 3 | DELETE | `delete_should_remove_record_when_allowed` | allowDelete=true 时删除成功 |
| 4 | DELETE | `delete_should_be_blocked_when_not_allowed` | allowDelete=false → 阻止 + 数据不变 |
| 5 | DRY_RUN | `dry_run_should_not_write_to_database` | DRY_RUN 模式不写库 |
| 6 | 安全阈值 | `should_throw_when_exceeding_max_affected_rows` | 超 maxAffectedRows → 异常 |
| 7 | 多表 INSERT | `insert_parent_child_should_replace_fk_via_id_mapping` | 父子表 INSERT + IdMapping 记录 |
| 8 | 多表 DELETE | `delete_should_remove_child_before_parent` | 先删子表再删主表（依赖排序） |
| 9 | SQL 失败 | `should_throw_apply_execution_exception_with_partial_result` | 失败时 partialResult 可用 |
| 10-13 | SqlBuilder | INSERT 过滤 id、UPDATE/DELETE 强制 tenantsid+id、空更新字段 → null | SQL 安全约束 |

#### 3.1.7 ApplyTransactionBoundaryTest — 事务边界验证

| # | 测试方法 | 验证点 |
|---|---------|--------|
| 1 | `apply_should_rollback_all_when_middle_action_fails` | 中间失败 → 业务数据回滚 + FAILED 审计保留 + snapshot 不残留 |
| 2 | `successful_apply_should_persist_record_and_snapshot` | 成功 → apply_record(SUCCESS) + snapshot + diagnostics 持久化 |

#### 3.1.8 ApplyFreshnessIntegrationTest — 比对时效保护

| # | 测试方法 | 验证点 |
|---|---------|--------|
| 1 | `execute_whenCompareTooOld_shouldReject` | session finished_at 过旧 → APPLY_COMPARE_TOO_OLD |

**配置：** `max-compare-age=PT1S`，篡改 `finished_at` 为 5 分钟前。

#### 3.1.9 ConcurrentApplyTest — 并发同步门禁（H2）

| # | 测试方法 | 验证点 |
|---|---------|--------|
| 1 | `concurrentApply_onlyOneSucceeds` | 同 session 2 线程 → 1 成功 + 1 APPLY_CONCURRENT_CONFLICT |
| 2 | `concurrentApply_onDifferentSessionsSameTarget_onlyOneSucceeds` | 不同 session 同目标 → 1 成功 + 1 APPLY_TARGET_BUSY |

#### 3.1.10 RollbackEndToEndTest — Rollback 完整验证（6 个用例）

| # | 测试方法 | 验证点 |
|---|---------|--------|
| 1 | `rollback_afterInsertAndUpdate_restoresFieldValues` | INSERT+UPDATE 回滚：字段级恢复 |
| 2 | `rollback_afterInsertOnly_removesInsertedRecords` | 仅 INSERT 回滚：新增被删除 |
| 3 | `rollback_afterDelete_restoresDeletedRecords` | DELETE 回滚：被删记录恢复 |
| 4 | `rollback_missingSnapshot_shouldFail` | 快照缺失 → ROLLBACK_SNAPSHOT_INCOMPLETE |
| 5 | `rollback_driftRequiresAcknowledgement` | 漂移检测 → 需 acknowledgeDrift 确认 |
| 6 | `rollback_thenRecompare_diffMatchesOriginal` | 回滚后重新比对 → diff 结果与初始一致 |
| 7 | `rollback_hasPositiveAffectedRows` | 回滚 affectedRows > 0 |

#### 3.1.11 ExternalDataSourceApplyAuditTest — 外部数据源审计

| # | 测试方法 | 验证点 |
|---|---------|--------|
| 1 | `externalDataSourceApplyFailure_keepsFailedAuditAndRollsBackExternalWrites` | 外部数据源失败：FAILED 审计保留 + 外部事务回滚 + targetDataSourceKey/failureStage 记录 |

#### 3.1.12 ExampleOrderPluginIntegrationTest — 多表+外键完整生命周期（7 个用例）

| # | 分组 | 测试方法 | 验证点 |
|---|------|---------|--------|
| 1 | 对比 | `compare_detectsInsertAndUpdate` | 多表结构正确识别 INSERT/UPDATE |
| 2 | 对比 | `compare_ord002IsInsert` | ORD-002 为 INSERT |
| 3 | 对比 | `compare_ord001HasUpdate` | ORD-001 有 UPDATE |
| 4 | Apply | `apply_writesOrderAndItems` | 主表+子表正确写入 + 外键替换 |
| 5 | Apply | `apply_fkReplacementCorrect` | 子表 order_id 指向新主表 ID |
| 6 | Rollback | `rollback_insertWithChildRecords_cleansUpAllRecords` | 回滚清理主表+子表 |
| 7 | 3层级联 | `threeLayerCascade_applyAndRollback_allCleanedUp` | 3层 INSERT → Rollback 全部清理 |
| 8 | 混合 | `mixedCompare_bothTypesDetected` | PRODUCT+ORDER 同时对比 |

---

### 3.2 MySQL E2E 测试（6 个测试类）

> 以下测试运行在**真实 MySQL** 上，验证 H2 无法覆盖的数据库特性。

#### 3.2.1 MysqlReleaseGateE2ETest — 发布门禁主测试（12 个用例）

**定位：** 覆盖核心功能路径在 MySQL InnoDB 上的完整正确性。

| # | 测试方法 | 验证点 | 关键断言 |
|---|---------|--------|---------|
| 1 | `preview_and_decision_api_should_not_write_and_should_filter_skipped_record` | 预览只读性 + SKIP 决策过滤 | 预览后 DB 无写入；SKIP 后仅同步 1 条 |
| 2 | `compare_query_chain_should_cover_analysis_flow_on_real_mysql` | session/get → listBusiness → getBusinessDetail 查询链 | 404→SUCCESS→3 条→UPDATE |
| 3 | `business_detail_should_return_not_found_on_real_mysql` | 查询不存在的 businessKey | 404 + DIFF_E_1002 |
| 4 | `business_detail_view_modes_should_filter_noop_and_strip_raw_fields_on_real_mysql` | FULL/FILTERED/COMPACT 视图模式 | NOOP 过滤 + 字段裁剪 |
| 5 | `malformed_json_should_return_bad_request_on_real_mysql` | 畸形 JSON 错误处理 | 400 + DIFF_E_0002 |
| 6 | `partial_selection_validation_should_cover_tampered_unknown_and_empty_cases` | PARTIAL 模式 3 种拒绝 | 篡改 token/未知 ID/空选择 |
| 7 | `partial_selection_should_reject_sub_table_actions_on_real_mysql` | 禁止选择子表 action | 400 + DIFF_E_0001 |
| 8 | `multi_table_parent_child_grandchild_should_cover_insert_update_delete_and_full_rollback` | 三层表 INSERT/UPDATE/DELETE + 全量回滚 | 数据精确还原 |
| 9 | `partial_execute_then_acknowledged_rollback_should_work_on_real_mysql` | 部分执行 + 漂移感知回滚 | 漂移检测 → acknowledge 强制回滚 |
| 10 | `apply_failure_should_keep_failed_audit_and_rollback_business_writes` | 同步失败事务回滚 + 审计保留 | Trigger 注入失败 → FAILED + 数据不变 |
| 11 | `rollback_should_reject_external_target_on_real_mysql` | 外部数据源回滚拒绝 | 422 + DIFF_E_3001 |
| 12 | `rollback_should_reject_missing_snapshot_on_real_mysql` | 快照缺失回滚拒绝 | 422 + DIFF_E_3003 |
| 13 | `concurrent_apply_on_same_target_should_return_target_busy` | 并发同步租约互斥（3 线程） | 1 成功 + 2 DIFF_E_2008 |

#### 3.2.2 MysqlPreviewLimitE2ETest — 预览容量保护（1 个用例）

**配置：** `preview-action-limit=1`

| # | 测试方法 | 验证点 | 关键断言 |
|---|---------|--------|---------|
| 1 | `preview_should_return_preview_too_large_when_action_limit_exceeded` | action 数量超限 → 拒绝 | 422 + DIFF_E_2014 |

#### 3.2.3 MysqlStandaloneDisabledE2ETest — 功能开关（1 个用例）

**配置：** `tenant-diff.standalone.enabled=false`

| # | 测试方法 | 验证点 | 关键断言 |
|---|---------|--------|---------|
| 1 | `standalone_endpoints_should_not_be_exposed_when_disabled` | 端点彻底不暴露 | 原生 404（无 success/code 字段） |

#### 3.2.4 MysqlAdversarialGuardrailE2ETest — 对抗性护栏（7 个用例）

**配置：** `preview-token-ttl=PT1S, max-compare-age=PT1S`

| # | 测试方法 | 验证点 | 关键断言 |
|---|---------|--------|---------|
| 1 | `partial_execute_should_reject_expired_preview_token_on_real_mysql` | 过期 previewToken 拒绝 | 422 + DIFF_E_2015 |
| 2 | `execute_should_reject_stale_compare_on_real_mysql` | 过时比对结果拒绝 | 409 + DIFF_E_2016 |
| 3 | `execute_should_reject_when_max_affected_rows_exceeded_on_real_mysql` | 超行数上限拒绝 | 422 + DIFF_E_2001 |
| 4 | `execute_should_reject_delete_when_allow_delete_is_false_on_real_mysql` | 禁止删除时拒绝 DELETE | 422 + DIFF_E_2002 |
| 5 | `same_session_should_reject_duplicate_apply_on_real_mysql` | 重复同步拒绝 | 409 + DIFF_E_1004 |
| 6 | `b_to_a_update_apply_and_rollback_should_work_on_real_mysql` | B→A 反向同步+回滚 | 数据精确还原 |
| 7 | `order_compare_should_ignore_orphan_child_rows_on_real_mysql` | 孤儿子行自动过滤 | 预览不含孤儿行 + 脏数据不被清理 |

#### 3.2.5 MysqlWarningDegradationE2ETest — 告警降级（1 个用例）

**测试手段：** 注入 `FaultInjectingJdbcTemplate` 使 PROD-003 加载时抛异常。

| # | 测试方法 | 验证点 | 关键断言 |
|---|---------|--------|---------|
| 1 | `compare_warning_should_degrade_to_partial_success_and_still_allow_apply_and_rollback_on_real_mysql` | 加载失败 → 告警降级 → 正常记录仍可同步/回滚 | warningCount=1 + affectedRows=1 + 回滚成功 |

#### 3.2.6 MysqlRollbackConcurrencyE2ETest — 回滚并发互斥（1 个用例）

**测试手段：** 注入 `SlowJdbcTemplate` 在 tenant 2 查询时增加 300ms 延迟，扩大竞争窗口。

| # | 测试方法 | 验证点 | 关键断言 |
|---|---------|--------|---------|
| 1 | `concurrent_rollback_on_same_apply_should_return_conflict_on_real_mysql` | 3 线程并发回滚 → 1 成功 + 2 冲突 | success=1 / DIFF_E_3002=2 + 数据正确还原 |

---

## 4. 覆盖矩阵

### 4.1 功能维度

| 功能点 | H2 测试 | MySQL E2E | 合计 |
|--------|---------|-----------|------|
| Session 创建 | 7 | 1 | 8 |
| Compare 对比 | 6 | 3 | 9 |
| Query 查询链 | 6 | 3 | 9 |
| Preview 预览 | 1 | 3 | 4 |
| Decision 决策 | 0 | 1 | 1 |
| Apply 同步 | 15 | 11 | 26 |
| Rollback 回滚 | 12 | 7 | 19 |
| **合计** | **47** | **29** | **76** |

### 4.2 安全护栏覆盖

| 护栏 | 错误码 | H2 | MySQL |
|------|--------|-----|-------|
| Session 不存在 | DIFF_E_1001 | ✅ | ✅ |
| 业务详情不存在 | DIFF_E_1002 | ✅ | ✅ |
| 执行异常 | DIFF_E_0003 | ✅ | ✅ |
| JSON 解析异常 | DIFF_E_0002 | — | ✅ |
| 超行数上限 | DIFF_E_2001 | ✅ | ✅ |
| 禁止 DELETE | DIFF_E_2002 | ✅ | ✅ |
| 并发租约冲突 | DIFF_E_2008 | ✅ | ✅ |
| 空 actionIds | DIFF_E_2010 | — | ✅ |
| 未知 actionId | DIFF_E_2011 | — | ✅ |
| 篡改 previewToken | DIFF_E_2012 | — | ✅ |
| 预览容量超限 | DIFF_E_2014 | — | ✅ |
| 过期 previewToken | DIFF_E_2015 | — | ✅ |
| 比对结果过时 | DIFF_E_2016 | ✅ | ✅ |
| 外部数据源回滚拒绝 | DIFF_E_3001 | — | ✅ |
| 回滚并发冲突 | DIFF_E_3002 | — | ✅ |
| 快照缺失 | DIFF_E_3003 | ✅ | ✅ |
| 漂移检测 | DIFF_E_3004 | ✅ | ✅ |
| 重复 apply | DIFF_E_1004 | ✅ | ✅ |
| Session 未就绪 | SESSION_NOT_READY | ✅ | — |
| Apply 状态非 SUCCESS | APPLY_NOT_SUCCESS | ✅ | — |
| 已回滚不能重复 | APPLY_ALREADY_ROLLED_BACK | ✅ | — |

### 4.3 并发场景覆盖

| 并发场景 | 环境 | 线程数 | 预期行为 |
|---------|------|--------|---------|
| 同 session 并发 apply | H2 | 2 | 1 成功 + 1 CONCURRENT_CONFLICT |
| 不同 session 同目标 apply | H2 | 2 | 1 成功 + 1 TARGET_BUSY |
| 同目标并发 apply | MySQL | 3 | 1 成功 + 2 TARGET_BUSY |
| 同 applyId 并发 rollback | MySQL | 3 | 1 成功 + 2 冲突 |

### 4.4 数据模型覆盖

| 模型 | 操作类型 | H2 | MySQL |
|------|---------|-----|-------|
| 单表 | INSERT | ✅ | ✅ |
| 单表 | UPDATE | ✅ | ✅ |
| 单表 | DELETE | ✅ | ✅ |
| 单表 | DRY_RUN | ✅ | — |
| 2层父子 | INSERT (FK替换) | ✅ | ✅ |
| 2层父子 | DELETE (依赖排序) | ✅ | ✅ |
| 3层父子孙 | INSERT+UPDATE+DELETE | ✅ | ✅ |
| 3层父子孙 | 全量回滚 | ✅ | ✅ |
| 混合业务 | PRODUCT+ORDER 同时对比 | ✅ | — |
| 孤儿子行 | 过滤脏数据 | — | ✅ |

---

## 5. 运行指南

### 5.1 运行 H2 集成测试

H2 测试无需外部依赖，直接运行：

```bash
cd tenant-diff-demo
mvn test -Dtest='!Mysql*'
```

### 5.2 运行 MySQL E2E 测试

#### 前提条件

1. **MySQL 实例**：需要一个可访问的 MySQL 5.7+ 或 8.0 实例。
2. **权限要求**：测试用户需要 CREATE DATABASE、CREATE TABLE、CREATE TRIGGER 权限。
3. **数据库**：测试会自动创建两个数据库：
   - `xai_tenant_diff_e2e_primary`（主库）
   - `xai_tenant_diff_e2e_ext`（外部数据源库）

#### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `TENANT_DIFF_TEST_MYSQL_ENABLED` | `false` | 显式开启真实 MySQL E2E；未开启时测试类会被跳过 |
| `TENANT_DIFF_TEST_MYSQL_HOST` | `127.0.0.1` | MySQL 主机 |
| `TENANT_DIFF_TEST_MYSQL_PORT` | `3306` | MySQL 端口 |
| `TENANT_DIFF_TEST_MYSQL_USERNAME` | `root` | MySQL 用户名 |
| `TENANT_DIFF_TEST_MYSQL_PASSWORD` | `(empty)` | MySQL 密码，建议通过环境变量显式传入 |

#### 运行命令

```bash
# 使用默认连接参数（127.0.0.1:3306, root, 空密码），并显式开启 MySQL E2E
cd tenant-diff-demo
TENANT_DIFF_TEST_MYSQL_ENABLED=true mvn test -Dtest='Mysql*'

# 使用自定义配置
TENANT_DIFF_TEST_MYSQL_ENABLED=true \
TENANT_DIFF_TEST_MYSQL_HOST=192.168.1.100 \
TENANT_DIFF_TEST_MYSQL_PORT=3306 \
TENANT_DIFF_TEST_MYSQL_USERNAME=test_user \
TENANT_DIFF_TEST_MYSQL_PASSWORD=test_pass \
mvn test -Dtest='Mysql*'
```

#### Docker 快速启动 MySQL

```bash
docker run -d --name tenant-diff-mysql \
  -e MYSQL_ROOT_PASSWORD=change_me \
  -p 3306:3306 \
  mysql:8.0 \
  --character-set-server=utf8mb4 \
  --collation-server=utf8mb4_unicode_ci
```

### 5.3 运行全部测试

```bash
cd tenant-diff-demo
mvn test
```

> **注意：** 默认 `mvn test` 只保证 H2 集成测试可直接运行；真实 MySQL E2E 需要显式设置
> `TENANT_DIFF_TEST_MYSQL_ENABLED=true`，否则相关测试类会被跳过。

---

## 6. 测试技术详解

### 6.1 故障注入

| 技术 | 使用场景 | 实现方式 |
|------|---------|---------|
| MySQL Trigger | 模拟同步时数据库约束失败 | `MysqlReleaseGateE2ETest.apply_failure_*` |
| FaultInjectingJdbcTemplate | 模拟插件加载异常（告警降级） | `MysqlWarningDegradationE2ETest` |
| SlowJdbcTemplate | 扩大并发竞争窗口（300ms 延迟） | `MysqlRollbackConcurrencyE2ETest` |
| DB 数据篡改 | 模拟 token 过期、比对过时、快照丢失 | `MysqlAdversarialGuardrailE2ETest` |
| 注入非法 Action | 制造 apply 中间失败 | `ApplyTransactionBoundaryTest` |

### 6.2 并发测试模型

所有并发测试使用统一的 `CountDownLatch` 栅栏模型：

```
Thread-1  ──┐
             ├── readyLatch.countDown() ── 等待 ──┐
Thread-2  ──┤                                     ├── startLatch.countDown() ── 同时执行
             ├── readyLatch.countDown() ── 等待 ──┤
Thread-3  ──┘                                     └────────────────────────
```

- `readyLatch`：确保所有线程就绪后再放行。
- `startLatch`：主线程倒计后所有工作线程同时起跑，最大化竞争窗口。

### 6.3 Bean 覆盖策略

通过 `@TestConfiguration` + `@Primary` + `@Bean` 覆盖默认 Plugin Bean，实现故障注入而不修改业务代码。需要设置 `spring.main.allow-bean-definition-overriding=true`。

---

## 7. MySQL vs H2 测试必要性分析

### 7.1 为什么 H2 不够

| 差异点 | H2 | MySQL | 影响 |
|--------|-----|-------|------|
| Trigger 支持 | 不支持 | 支持 | 无法模拟 DB 级约束失败 |
| 行级锁 (InnoDB) | 表级锁 | 行级锁 | 并发行为不同 |
| AUTO_INCREMENT | 不同实现 | InnoDB 特有语义 | IdMapping 可能表现不同 |
| SQL 方言 | H2 MODE=MYSQL 近似 | 原生 MySQL | 部分 SQL 语法差异 |
| 事务隔离级别 | 默认不同 | REPEATABLE-READ | 并发读写行为不同 |
| `SELECT ... FOR UPDATE` | 近似模拟 | InnoDB 间隙锁 | 租约互斥行为不同 |

### 7.2 互补关系

```
H2 测试（快速反馈循环）
  ├── Service 编排逻辑
  ├── 状态机转换
  ├── API 契约
  ├── 参数校验
  └── 基础 CRUD 正确性

MySQL E2E（生产保障）
  ├── 真实 SQL 方言
  ├── InnoDB 事务语义
  ├── 并发锁行为
  ├── Trigger 故障注入
  └── 跨库数据源
```

---

## 8. 错误码速查表

| 错误码 | HTTP 状态 | 含义 | 触发条件 |
|--------|----------|------|---------|
| DIFF_E_0001 | 400 | 非法请求参数 | 选择子表 action 做 PARTIAL 同步 |
| DIFF_E_0002 | 400 | JSON 解析失败 | 请求体非法 JSON |
| DIFF_E_0003 | 500 | 内部执行异常 | 同步过程数据库异常 |
| DIFF_E_1001 | 404 | Session 不存在 | 查询不存在的 sessionId |
| DIFF_E_1002 | 404 | 业务详情不存在 | 查询不存在的 businessKey |
| DIFF_E_1004 | 409 | Session 已被同步 | 重复 apply 同一 session |
| DIFF_E_2001 | 422 | 超行数上限 | affectedRows > maxAffectedRows |
| DIFF_E_2002 | 422 | 禁止 DELETE | allowDelete=false 但含 DELETE |
| DIFF_E_2008 | — | 目标忙 | 并发同步同一目标 |
| DIFF_E_2010 | 422 | 空 actionIds | PARTIAL 模式未选择 action |
| DIFF_E_2011 | 422 | 未知 actionId | PARTIAL 模式传入非法 ID |
| DIFF_E_2012 | 422 | Token 签名不匹配 | previewToken 被篡改 |
| DIFF_E_2014 | 422 | 预览过大 | action 数量超过上限 |
| DIFF_E_2015 | 422 | Token 已过期 | previewToken 超过 TTL |
| DIFF_E_2016 | 409 | 比对结果过时 | session 超过 max-compare-age |
| DIFF_E_3001 | 422 | 外部数据源不支持回滚 | 对外部数据源执行回滚 |
| DIFF_E_3002 | — | 回滚并发冲突 | 并发回滚同一 applyId |
| DIFF_E_3003 | 422 | 快照不完整 | 回滚时快照数据缺失 |
| DIFF_E_3004 | 409 | 检测到数据漂移 | 目标数据已被修改 |

---

## 9. 统计总览

| 指标 | 数值 |
|------|------|
| 测试类总数 | 18 |
| H2 集成测试类 | 12 |
| MySQL E2E 测试类 | 6 |
| 测试方法总数（约） | 76 |
| 覆盖错误码数 | 21 |
| 覆盖并发场景 | 4 |
| 故障注入技术 | 5 种 |
| 业务模型 | 2 种（单表 + 三层级联） |
| 同步方向 | A→B / B→A 双向 |
