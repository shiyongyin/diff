package com.diff.demo;

import com.diff.core.domain.apply.*;
import com.diff.core.domain.diff.BusinessDiff;
import com.diff.core.domain.diff.DiffType;
import com.diff.core.domain.diff.SessionStatus;
import com.diff.core.domain.exception.ErrorCode;
import com.diff.core.domain.exception.TenantDiffException;
import com.diff.standalone.service.TenantDiffStandaloneApplyService;
import com.diff.standalone.service.TenantDiffStandaloneRollbackService;
import com.diff.standalone.service.TenantDiffStandaloneService;
import com.diff.standalone.web.dto.request.CreateDiffSessionRequest;
import com.diff.standalone.web.dto.response.DiffSessionSummaryResponse;
import com.diff.standalone.web.dto.response.PageResult;
import com.diff.standalone.web.dto.response.TenantDiffApplyExecuteResponse;
import com.diff.standalone.web.dto.response.TenantDiffBusinessSummary;
import com.diff.standalone.web.dto.response.TenantDiffRollbackResponse;
import com.diff.core.domain.scope.TenantModelScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Service 层集成测试：验证 createSession/runCompare/apply/rollback 的编排逻辑。
 *
 * <p>与 API 层测试（DemoSessionApiIntegrationTests、DemoApplyApiIntegrationTests）不同，
 * 这里直接调用 Service 接口，测试更细粒度的行为和边界场景。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("Service 层集成测试")
class ServiceLayerIntegrationTest {

    @Autowired
    private TenantDiffStandaloneService diffService;

    @Autowired
    private TenantDiffStandaloneApplyService applyService;

    @Autowired
    private TenantDiffStandaloneRollbackService rollbackService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---- helpers ----

    private CreateDiffSessionRequest validRequest() {
        return CreateDiffSessionRequest.builder()
            .sourceTenantId(1L)
            .targetTenantId(2L)
            .scope(TenantModelScope.builder()
                .businessTypes(List.of("EXAMPLE_PRODUCT"))
                .build())
            .build();
    }

    private Long createAndCompare() {
        Long sessionId = diffService.createSession(validRequest());
        diffService.runCompare(sessionId);
        return sessionId;
    }

    // ================================================================
    // createSession 测试
    // ================================================================

    @Nested
    @DisplayName("createSession")
    class CreateSessionTests {

        @Test
        @DisplayName("正常创建返回有效 sessionId")
        void validRequest_returnsSessionId() {
            Long sessionId = diffService.createSession(validRequest());
            assertNotNull(sessionId);
            assertTrue(sessionId > 0);
        }

        @Test
        @DisplayName("创建后 session 状态为 CREATED")
        void afterCreate_statusIsCreated() {
            Long sessionId = diffService.createSession(validRequest());
            DiffSessionSummaryResponse summary = diffService.getSessionSummary(sessionId);
            assertEquals(SessionStatus.CREATED, summary.getStatus());
        }

        @Test
        @DisplayName("null request → 抛 IllegalArgumentException")
        void nullRequest_throws() {
            assertThrows(IllegalArgumentException.class,
                () -> diffService.createSession(null));
        }

        @Test
        @DisplayName("null sourceTenantId → 抛异常")
        void nullSourceTenantId_throws() {
            CreateDiffSessionRequest request = CreateDiffSessionRequest.builder()
                .sourceTenantId(null)
                .targetTenantId(2L)
                .scope(TenantModelScope.builder().businessTypes(List.of("T")).build())
                .build();
            assertThrows(IllegalArgumentException.class,
                () -> diffService.createSession(request));
        }

        @Test
        @DisplayName("空 businessTypes → 抛异常")
        void emptyBusinessTypes_throws() {
            CreateDiffSessionRequest request = CreateDiffSessionRequest.builder()
                .sourceTenantId(1L)
                .targetTenantId(2L)
                .scope(TenantModelScope.builder().businessTypes(List.of()).build())
                .build();
            assertThrows(IllegalArgumentException.class,
                () -> diffService.createSession(request));
        }
    }

    // ================================================================
    // runCompare 测试
    // ================================================================

    @Nested
    @DisplayName("runCompare")
    class RunCompareTests {

        @Test
        @DisplayName("对比完成后 session 状态为 SUCCESS")
        void afterCompare_statusIsSuccess() {
            Long sessionId = createAndCompare();
            DiffSessionSummaryResponse summary = diffService.getSessionSummary(sessionId);
            assertEquals(SessionStatus.SUCCESS, summary.getStatus());
            assertNotNull(summary.getFinishedAt());
        }

        @Test
        @DisplayName("对比结果持久化 + 统计正确")
        void compare_persistsResultsWithStatistics() {
            Long sessionId = createAndCompare();
            DiffSessionSummaryResponse summary = diffService.getSessionSummary(sessionId);

            // EXAMPLE_PRODUCT 场景：tenant1 有 PROD-001/002/003，tenant2 有 PROD-001/002
            // PROD-001 相同 → NOOP，PROD-002 价格不同 → UPDATE，PROD-003 只在 tenant1 → INSERT
            assertNotNull(summary.getStatistics());
            assertTrue(summary.getStatistics().getTotalBusinesses() >= 1);
        }

        @Test
        @DisplayName("可重跑/幂等：同一 session 重跑 compare 不报错")
        void rerunCompare_isIdempotent() {
            Long sessionId = createAndCompare();

            // 重跑
            assertDoesNotThrow(() -> diffService.runCompare(sessionId));

            DiffSessionSummaryResponse summary = diffService.getSessionSummary(sessionId);
            assertEquals(SessionStatus.SUCCESS, summary.getStatus());
        }

        @Test
        @DisplayName("null sessionId → 抛异常")
        void nullSessionId_throws() {
            assertThrows(IllegalArgumentException.class,
                () -> diffService.runCompare(null));
        }

        @Test
        @DisplayName("不存在的 sessionId → 抛 TenantDiffException")
        void nonExistentSession_throws() {
            assertThrows(TenantDiffException.class,
                () -> diffService.runCompare(999999L));
        }
    }

    // ================================================================
    // 查询接口测试
    // ================================================================

    @Nested
    @DisplayName("查询接口")
    class QueryTests {

        @Test
        @DisplayName("listBusinessSummaries 分页返回正确结构")
        void listBusiness_returnsPaginatedResults() {
            Long sessionId = createAndCompare();

            PageResult<TenantDiffBusinessSummary> page =
                diffService.listBusinessSummaries(sessionId, null, null, 1, 10);

            assertNotNull(page);
            assertTrue(page.getTotal() >= 1);
            assertEquals(1, page.getPageNo());
            assertFalse(page.getItems().isEmpty());

            TenantDiffBusinessSummary first = page.getItems().get(0);
            assertNotNull(first.getBusinessType());
            assertNotNull(first.getBusinessKey());
        }

        @Test
        @DisplayName("listBusinessSummaries 按 businessType 过滤")
        void listBusiness_filtersByBusinessType() {
            Long sessionId = createAndCompare();

            PageResult<TenantDiffBusinessSummary> page =
                diffService.listBusinessSummaries(sessionId, "EXAMPLE_PRODUCT", null, 1, 10);
            assertTrue(page.getTotal() >= 1);
            page.getItems().forEach(item ->
                assertEquals("EXAMPLE_PRODUCT", item.getBusinessType()));

            // 不存在的 businessType → 空结果
            PageResult<TenantDiffBusinessSummary> empty =
                diffService.listBusinessSummaries(sessionId, "NON_EXISTENT", null, 1, 10);
            assertEquals(0, empty.getTotal());
        }

        @Test
        @DisplayName("listBusinessSummaries 按 diffType 过滤")
        void listBusiness_filtersByDiffType() {
            Long sessionId = createAndCompare();

            PageResult<TenantDiffBusinessSummary> insertPage =
                diffService.listBusinessSummaries(sessionId, null, DiffType.INSERT, 1, 10);
            insertPage.getItems().forEach(item ->
                assertEquals(DiffType.INSERT, item.getDiffType()));
        }

        @Test
        @DisplayName("getBusinessDetail 返回 diff 详情")
        void getDetail_returnsDiffJson() {
            Long sessionId = createAndCompare();

            Optional<BusinessDiff> detail =
                diffService.getBusinessDetail(sessionId, "EXAMPLE_PRODUCT", "PROD-002");
            assertTrue(detail.isPresent());
            assertEquals("PROD-002", detail.get().getBusinessKey());
            assertNotNull(detail.get().getTableDiffs());
            assertFalse(detail.get().getTableDiffs().isEmpty());
        }

        @Test
        @DisplayName("getBusinessDetail 不存在的 key → empty")
        void getDetail_nonExistentKey_returnsEmpty() {
            Long sessionId = createAndCompare();

            Optional<BusinessDiff> detail =
                diffService.getBusinessDetail(sessionId, "EXAMPLE_PRODUCT", "NONEXISTENT");
            assertTrue(detail.isEmpty());
        }

        @Test
        @DisplayName("getBusinessDetail blank businessType → 抛异常")
        void getDetail_blankBusinessType_throws() {
            Long sessionId = createAndCompare();
            assertThrows(IllegalArgumentException.class,
                () -> diffService.getBusinessDetail(sessionId, "", "key"));
        }
    }

    // ================================================================
    // Apply 编排测试
    // ================================================================

    @Nested
    @DisplayName("Apply 编排")
    class ApplyOrchestrationTests {

        @Test
        @DisplayName("buildPlan 从 DB 加载 diff 重建 Plan")
        void buildPlan_loadsFromDb() {
            Long sessionId = createAndCompare();

            ApplyPlan plan = applyService.buildPlan(
                sessionId, ApplyDirection.A_TO_B,
                ApplyOptions.builder()
                    .businessTypes(List.of("EXAMPLE_PRODUCT"))
                    .diffTypes(List.of(DiffType.INSERT, DiffType.UPDATE))
                    .allowDelete(false)
                    .build()
            );

            assertNotNull(plan);
            assertEquals(sessionId, plan.getSessionId());
            assertEquals(ApplyDirection.A_TO_B, plan.getDirection());
            assertFalse(plan.getActions().isEmpty());
        }

        @Test
        @DisplayName("buildPlan 不存在的 session → 抛 TenantDiffException")
        void buildPlan_nonExistentSession_throws() {
            assertThrows(TenantDiffException.class,
                () -> applyService.buildPlan(999999L, ApplyDirection.A_TO_B, null));
        }

        @Test
        @DisplayName("execute 成功后 apply_record 状态为 SUCCESS + snapshot 已保存")
        void execute_success_persistsRecordAndSnapshot() {
            Long sessionId = createAndCompare();

            ApplyPlan plan = applyService.buildPlan(
                sessionId, ApplyDirection.A_TO_B,
                ApplyOptions.builder()
                    .mode(ApplyMode.EXECUTE)
                    .businessTypes(List.of("EXAMPLE_PRODUCT"))
                    .diffTypes(List.of(DiffType.INSERT, DiffType.UPDATE))
                    .allowDelete(false)
                    .build()
            );

            TenantDiffApplyExecuteResponse response = applyService.execute(plan);

            assertEquals(ApplyRecordStatus.SUCCESS, response.getStatus());
            assertNotNull(response.getApplyId());
            assertNotNull(response.getStartedAt());
            assertNotNull(response.getFinishedAt());
            assertTrue(response.getApplyResult().isSuccess());
            assertTrue(response.getApplyResult().getAffectedRows() > 0);

            // 验证 apply_record 已持久化
            int recordCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM xai_tenant_diff_apply_record WHERE session_id = ? AND status = 'SUCCESS'",
                Integer.class, sessionId);
            assertEquals(1, recordCount);

            // 验证 snapshot 已保存
            int snapshotCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM xai_tenant_diff_snapshot WHERE apply_id = ?",
                Integer.class, response.getApplyId());
            assertTrue(snapshotCount > 0);
        }

        @Test
        @DisplayName("execute 后目标租户数据已变更")
        void execute_writesTargetTenantData() {
            Long sessionId = createAndCompare();

            // apply 前：tenant2 没有 PROD-003
            int beforeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_product WHERE tenantsid = 2 AND product_code = 'PROD-003'",
                Integer.class);
            assertEquals(0, beforeCount);

            ApplyPlan plan = applyService.buildPlan(
                sessionId, ApplyDirection.A_TO_B,
                ApplyOptions.builder()
                    .mode(ApplyMode.EXECUTE)
                    .businessTypes(List.of("EXAMPLE_PRODUCT"))
                    .diffTypes(List.of(DiffType.INSERT, DiffType.UPDATE))
                    .allowDelete(false)
                    .build()
            );
            applyService.execute(plan);

            // apply 后：tenant2 有 PROD-003
            int afterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_product WHERE tenantsid = 2 AND product_code = 'PROD-003'",
                Integer.class);
            assertEquals(1, afterCount);
        }
    }

    // ================================================================
    // Rollback 编排测试
    // ================================================================

    @Nested
    @DisplayName("Rollback 编排")
    class RollbackOrchestrationTests {

        @Test
        @DisplayName("rollback 恢复目标租户到 apply 前状态")
        void rollback_restoresTargetData() {
            Long sessionId = createAndCompare();

            // 记录 apply 前状态
            int productCountBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_product WHERE tenantsid = 2", Integer.class);
            BigDecimal priceBefore = jdbcTemplate.queryForObject(
                "SELECT price FROM example_product WHERE tenantsid = 2 AND product_code = 'PROD-002'",
                BigDecimal.class);

            // Apply
            ApplyPlan plan = applyService.buildPlan(
                sessionId, ApplyDirection.A_TO_B,
                ApplyOptions.builder()
                    .mode(ApplyMode.EXECUTE)
                    .businessTypes(List.of("EXAMPLE_PRODUCT"))
                    .diffTypes(List.of(DiffType.INSERT, DiffType.UPDATE))
                    .allowDelete(false)
                    .build()
            );
            TenantDiffApplyExecuteResponse applyResponse = applyService.execute(plan);
            assertEquals(ApplyRecordStatus.SUCCESS, applyResponse.getStatus());

            // 确认 apply 已变更数据
            int productCountAfterApply = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_product WHERE tenantsid = 2", Integer.class);
            assertTrue(productCountAfterApply > productCountBefore,
                "Apply 应增加数据（INSERT PROD-003）");

            // Rollback
            TenantDiffRollbackResponse rollbackResponse = rollbackService.rollback(applyResponse.getApplyId());
            assertNotNull(rollbackResponse);
            assertTrue(rollbackResponse.getApplyResult().isSuccess());

            // 验证恢复
            int productCountAfterRollback = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_product WHERE tenantsid = 2", Integer.class);
            assertEquals(productCountBefore, productCountAfterRollback,
                "Rollback 后数据量应恢复到 apply 前");

            BigDecimal priceAfterRollback = jdbcTemplate.queryForObject(
                "SELECT price FROM example_product WHERE tenantsid = 2 AND product_code = 'PROD-002'",
                BigDecimal.class);
            assertEquals(0, priceBefore.compareTo(priceAfterRollback),
                "Rollback 后 PROD-002 价格应恢复到 apply 前");
        }

        @Test
        @DisplayName("不存在的 applyId → 抛 TenantDiffException")
        void rollback_nonExistentApplyId_throws() {
            assertThrows(TenantDiffException.class,
                () -> rollbackService.rollback(999999L));
        }

        @Test
        @DisplayName("null applyId → 抛 IllegalArgumentException")
        void rollback_nullApplyId_throws() {
            assertThrows(IllegalArgumentException.class,
                () -> rollbackService.rollback(null));
        }
    }

    // ================================================================
    // 并发安全：状态检查防重复操作
    // ================================================================

    @Nested
    @DisplayName("状态检查防重复操作")
    class StateGuardTests {

        @Test
        @DisplayName("session 未完成对比时 apply 应报错 SESSION_NOT_READY")
        void apply_beforeCompareComplete_shouldFail() {
            Long sessionId = diffService.createSession(validRequest());
            // session 状态为 CREATED，未执行 runCompare

            ApplyPlan plan = ApplyPlan.builder()
                .sessionId(sessionId)
                .direction(ApplyDirection.A_TO_B)
                .options(ApplyOptions.builder()
                    .mode(ApplyMode.EXECUTE)
                    .allowDelete(false)
                    .build())
                .actions(List.of())
                .build();

            TenantDiffException ex = assertThrows(TenantDiffException.class,
                () -> applyService.execute(plan));
            assertEquals(ErrorCode.SESSION_NOT_READY, ex.getErrorCode());
        }

        @Test
        @DisplayName("同一 session 重复 apply 应报错 SESSION_ALREADY_APPLIED")
        void apply_duplicateOnSameSession_shouldFail() {
            Long sessionId = createAndCompare();

            ApplyPlan plan = applyService.buildPlan(
                sessionId, ApplyDirection.A_TO_B,
                ApplyOptions.builder()
                    .mode(ApplyMode.EXECUTE)
                    .businessTypes(List.of("EXAMPLE_PRODUCT"))
                    .diffTypes(List.of(DiffType.INSERT, DiffType.UPDATE))
                    .allowDelete(false)
                    .build()
            );

            // 第一次 apply 成功
            TenantDiffApplyExecuteResponse response = applyService.execute(plan);
            assertEquals(ApplyRecordStatus.SUCCESS, response.getStatus());

            // 第二次 apply 应被拦截
            TenantDiffException ex = assertThrows(TenantDiffException.class,
                () -> applyService.execute(plan));
            assertEquals(ErrorCode.SESSION_ALREADY_APPLIED, ex.getErrorCode());
        }

        @Test
        @DisplayName("FAILED 的 apply 不能回滚")
        void rollback_failedApply_shouldFail() {
            Long sessionId = createAndCompare();

            // 插入一条 FAILED 状态的 apply_record
            jdbcTemplate.update(
                "INSERT INTO xai_tenant_diff_apply_record (apply_key, session_id, direction, plan_json, status, started_at) " +
                "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                "test-failed-key", sessionId, "A_TO_B", "{}", "FAILED");
            Long failedApplyId = jdbcTemplate.queryForObject(
                "SELECT id FROM xai_tenant_diff_apply_record WHERE apply_key = 'test-failed-key'", Long.class);

            TenantDiffException ex = assertThrows(TenantDiffException.class,
                () -> rollbackService.rollback(failedApplyId));
            assertEquals(ErrorCode.APPLY_NOT_SUCCESS, ex.getErrorCode());
        }

        @Test
        @DisplayName("已 ROLLED_BACK 的 apply 不能重复回滚")
        void rollback_duplicate_shouldFail() {
            Long sessionId = createAndCompare();

            ApplyPlan plan = applyService.buildPlan(
                sessionId, ApplyDirection.A_TO_B,
                ApplyOptions.builder()
                    .mode(ApplyMode.EXECUTE)
                    .businessTypes(List.of("EXAMPLE_PRODUCT"))
                    .diffTypes(List.of(DiffType.INSERT, DiffType.UPDATE))
                    .allowDelete(false)
                    .build()
            );

            TenantDiffApplyExecuteResponse applyResponse = applyService.execute(plan);
            assertEquals(ApplyRecordStatus.SUCCESS, applyResponse.getStatus());

            // 第一次回滚成功
            rollbackService.rollback(applyResponse.getApplyId());

            // 验证 apply_record 状态已更新为 ROLLED_BACK
            String status = jdbcTemplate.queryForObject(
                "SELECT status FROM xai_tenant_diff_apply_record WHERE id = ?",
                String.class, applyResponse.getApplyId());
            assertEquals(ApplyRecordStatus.ROLLED_BACK.name(), status);

            // 第二次回滚应被精确拦截为 APPLY_ALREADY_ROLLED_BACK
            TenantDiffException ex = assertThrows(TenantDiffException.class,
                () -> rollbackService.rollback(applyResponse.getApplyId()));
            assertEquals(ErrorCode.APPLY_ALREADY_ROLLED_BACK, ex.getErrorCode());
        }

        @Test
        @DisplayName("RUNNING 状态的 apply 不能回滚")
        void rollback_runningApply_shouldFail() {
            Long sessionId = createAndCompare();

            // 插入一条 RUNNING 状态的 apply_record
            jdbcTemplate.update(
                "INSERT INTO xai_tenant_diff_apply_record (apply_key, session_id, direction, plan_json, status, started_at) " +
                "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                "test-running-key", sessionId, "A_TO_B", "{}", "RUNNING");
            Long runningApplyId = jdbcTemplate.queryForObject(
                "SELECT id FROM xai_tenant_diff_apply_record WHERE apply_key = 'test-running-key'", Long.class);

            TenantDiffException ex = assertThrows(TenantDiffException.class,
                () -> rollbackService.rollback(runningApplyId));
            assertEquals(ErrorCode.APPLY_NOT_SUCCESS, ex.getErrorCode());
        }

        @Test
        @DisplayName("已回滚的 session 不能重新 apply")
        void apply_afterRollback_shouldFail() {
            Long sessionId = createAndCompare();

            ApplyPlan plan = applyService.buildPlan(
                sessionId, ApplyDirection.A_TO_B,
                ApplyOptions.builder()
                    .mode(ApplyMode.EXECUTE)
                    .businessTypes(List.of("EXAMPLE_PRODUCT"))
                    .diffTypes(List.of(DiffType.INSERT, DiffType.UPDATE))
                    .allowDelete(false)
                    .build()
            );

            TenantDiffApplyExecuteResponse applyResponse = applyService.execute(plan);
            assertEquals(ApplyRecordStatus.SUCCESS, applyResponse.getStatus());

            // 回滚
            rollbackService.rollback(applyResponse.getApplyId());

            // 回滚后再次 apply 同一 session 应被拦截
            TenantDiffException ex = assertThrows(TenantDiffException.class,
                () -> applyService.execute(plan));
            assertEquals(ErrorCode.SESSION_ALREADY_APPLIED, ex.getErrorCode());
        }
    }
}
