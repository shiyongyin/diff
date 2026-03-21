package com.diff.demo;

import com.diff.core.domain.apply.*;
import com.diff.core.domain.diff.BusinessDiff;
import com.diff.core.domain.diff.DiffType;
import com.diff.standalone.service.TenantDiffStandaloneApplyService;
import com.diff.standalone.service.TenantDiffStandaloneRollbackService;
import com.diff.standalone.service.TenantDiffStandaloneService;
import com.diff.standalone.web.dto.request.CreateDiffSessionRequest;
import com.diff.standalone.web.dto.response.DiffSessionSummaryResponse;
import com.diff.standalone.web.dto.response.TenantDiffApplyExecuteResponse;
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
 * ExampleOrderPlugin 集成测试：多表 + 外键场景的完整生命周期验证。
 *
 * <p>
 * 种子数据：
 * <ul>
 *     <li>租户 1（源）：ORD-001（2 个子项）+ ORD-002（1 个子项）</li>
 *     <li>租户 2（目标）：ORD-001（1 个子项，order_name 和 total_amount 不同）</li>
 *     <li>预期 diff：ORD-001 → UPDATE（主表字段变更 + 子项差异），ORD-002 → INSERT</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("ExampleOrderPlugin 多表+外键集成测试")
class ExampleOrderPluginIntegrationTest {

    @Autowired
    private TenantDiffStandaloneService diffService;

    @Autowired
    private TenantDiffStandaloneApplyService applyService;

    @Autowired
    private TenantDiffStandaloneRollbackService rollbackService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---- helpers ----

    private Long createAndCompare() {
        CreateDiffSessionRequest request = CreateDiffSessionRequest.builder()
            .sourceTenantId(1L)
            .targetTenantId(2L)
            .scope(TenantModelScope.builder()
                .businessTypes(List.of("EXAMPLE_ORDER"))
                .build())
            .build();
        Long sessionId = diffService.createSession(request);
        diffService.runCompare(sessionId);
        return sessionId;
    }

    // ================================================================
    // 对比验证
    // ================================================================

    @Nested
    @DisplayName("对比")
    class CompareTests {

        @Test
        @DisplayName("对比成功：多表结构正确识别 INSERT 和 UPDATE")
        void compare_detectsInsertAndUpdate() {
            Long sessionId = createAndCompare();

            DiffSessionSummaryResponse summary = diffService.getSessionSummary(sessionId);
            assertEquals("SUCCESS", summary.getStatus().name());
            assertTrue(summary.getStatistics().getTotalBusinesses() >= 2,
                "应至少检测到 ORD-001（UPDATE）和 ORD-002（INSERT）");
        }

        @Test
        @DisplayName("ORD-002 为 INSERT（只在源租户存在）")
        void compare_ord002IsInsert() {
            Long sessionId = createAndCompare();

            Optional<BusinessDiff> detail =
                diffService.getBusinessDetail(sessionId, "EXAMPLE_ORDER", "ORD-002");
            assertTrue(detail.isPresent());

            // ORD-002 只在 tenant1，应有 INSERT 类型的 recordDiff
            boolean hasInsert = detail.get().getTableDiffs().stream()
                .flatMap(t -> t.getRecordDiffs().stream())
                .anyMatch(r -> r.getDiffType() == DiffType.INSERT);
            assertTrue(hasInsert, "ORD-002 应有 INSERT recordDiff");
        }

        @Test
        @DisplayName("ORD-001 有 UPDATE（主表字段差异）")
        void compare_ord001HasUpdate() {
            Long sessionId = createAndCompare();

            Optional<BusinessDiff> detail =
                diffService.getBusinessDetail(sessionId, "EXAMPLE_ORDER", "ORD-001");
            assertTrue(detail.isPresent());

            // ORD-001 主表 order_name/total_amount/status 不同 → UPDATE
            boolean hasUpdate = detail.get().getTableDiffs().stream()
                .filter(t -> "example_order".equals(t.getTableName()))
                .flatMap(t -> t.getRecordDiffs().stream())
                .anyMatch(r -> r.getDiffType() == DiffType.UPDATE);
            assertTrue(hasUpdate, "ORD-001 主表应有 UPDATE recordDiff");
        }
    }

    // ================================================================
    // Apply 验证
    // ================================================================

    @Nested
    @DisplayName("Apply")
    class ApplyTests {

        @Test
        @DisplayName("Apply INSERT+UPDATE：主表和子表数据正确写入")
        void apply_writesOrderAndItems() {
            Long sessionId = createAndCompare();

            // Apply 前：tenant2 没有 ORD-002
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order WHERE tenantsid = 2 AND order_code = 'ORD-002'",
                Integer.class));

            ApplyPlan plan = applyService.buildPlan(
                sessionId, ApplyDirection.A_TO_B,
                ApplyOptions.builder()
                    .mode(ApplyMode.EXECUTE)
                    .businessTypes(List.of("EXAMPLE_ORDER"))
                    .diffTypes(List.of(DiffType.INSERT, DiffType.UPDATE))
                    .allowDelete(false)
                    .build()
            );

            TenantDiffApplyExecuteResponse response = applyService.execute(plan);
            assertEquals(ApplyRecordStatus.SUCCESS, response.getStatus());

            // Apply 后：ORD-002 主表已插入
            assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order WHERE tenantsid = 2 AND order_code = 'ORD-002'",
                Integer.class), "ORD-002 主表应已插入");

            // Apply 后：ORD-002 的子项已插入
            Long newOrderId = jdbcTemplate.queryForObject(
                "SELECT id FROM example_order WHERE tenantsid = 2 AND order_code = 'ORD-002'",
                Long.class);
            int itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order_item WHERE tenantsid = 2 AND order_id = ?",
                Integer.class, newOrderId);
            assertEquals(1, itemCount,
                "ORD-002 应有 1 个子项（ITEM-003），且 order_id 已通过 IdMapping 替换");
        }

        @Test
        @DisplayName("Apply 后子表外键正确指向新主表 ID")
        void apply_fkReplacementCorrect() {
            Long sessionId = createAndCompare();

            ApplyPlan plan = applyService.buildPlan(
                sessionId, ApplyDirection.A_TO_B,
                ApplyOptions.builder()
                    .mode(ApplyMode.EXECUTE)
                    .businessTypes(List.of("EXAMPLE_ORDER"))
                    .businessKeys(List.of("ORD-002"))
                    .diffTypes(List.of(DiffType.INSERT))
                    .allowDelete(false)
                    .build()
            );

            applyService.execute(plan);

            // 验证子表 order_id 指向 tenant2 新生成的主表 ID（而非 tenant1 的 ID）
            Long tenant2OrderId = jdbcTemplate.queryForObject(
                "SELECT id FROM example_order WHERE tenantsid = 2 AND order_code = 'ORD-002'",
                Long.class);

            List<Long> itemOrderIds = jdbcTemplate.queryForList(
                "SELECT order_id FROM example_order_item WHERE tenantsid = 2 AND item_code = 'ITEM-003'",
                Long.class);
            assertFalse(itemOrderIds.isEmpty(), "ITEM-003 应已插入");
            assertEquals(tenant2OrderId, itemOrderIds.get(0),
                "子表 order_id 应指向 tenant2 的新订单 ID");
        }
    }

    // ================================================================
    // Rollback 验证
    // ================================================================

    @Nested
    @DisplayName("Rollback")
    class RollbackTests {

        @Test
        @DisplayName("Rollback INSERT 场景：子表新增记录也被清理")
        void rollback_insertWithChildRecords_cleansUpAllRecords() {
            Long sessionId = createAndCompare();

            // 只 Apply ORD-002（纯 INSERT：主表+子表均为新增）
            ApplyPlan plan = applyService.buildPlan(
                sessionId, ApplyDirection.A_TO_B,
                ApplyOptions.builder()
                    .mode(ApplyMode.EXECUTE)
                    .businessTypes(List.of("EXAMPLE_ORDER"))
                    .businessKeys(List.of("ORD-002"))
                    .diffTypes(List.of(DiffType.INSERT))
                    .allowDelete(false)
                    .build()
            );
            TenantDiffApplyExecuteResponse response = applyService.execute(plan);
            assertEquals(ApplyRecordStatus.SUCCESS, response.getStatus());

            // 确认 apply 已写入：主表 + 子表
            assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order WHERE tenantsid = 2 AND order_code = 'ORD-002'",
                Integer.class), "ORD-002 主表应已插入");
            Long newOrderId = jdbcTemplate.queryForObject(
                "SELECT id FROM example_order WHERE tenantsid = 2 AND order_code = 'ORD-002'",
                Long.class);
            int itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order_item WHERE tenantsid = 2 AND order_id = ?",
                Integer.class, newOrderId);
            assertTrue(itemCount > 0, "ORD-002 子表应有记录");

            // Rollback
            TenantDiffRollbackResponse rollbackResponse = rollbackService.rollback(response.getApplyId());
            assertTrue(rollbackResponse.getApplyResult().isSuccess());

            // 验证主表被删除
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order WHERE tenantsid = 2 AND order_code = 'ORD-002'",
                Integer.class), "Rollback 后 ORD-002 主表应被删除");

            // 验证子表也被清理
            int itemCountAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order_item WHERE tenantsid = 2 AND order_id = ?",
                Integer.class, newOrderId);
            assertEquals(0, itemCountAfter, "Rollback 后 ORD-002 子表记录也应被清理");
        }

        @Test
        @DisplayName("Rollback 纯 INSERT 场景：删除新增的主表和子表记录")
        void rollback_insertOnly_removesInsertedRecords() {
            Long sessionId = createAndCompare();

            // 只 Apply ORD-002（纯 INSERT：主表+子表均为新增）
            ApplyPlan plan = applyService.buildPlan(
                sessionId, ApplyDirection.A_TO_B,
                ApplyOptions.builder()
                    .mode(ApplyMode.EXECUTE)
                    .businessTypes(List.of("EXAMPLE_ORDER"))
                    .businessKeys(List.of("ORD-002"))
                    .diffTypes(List.of(DiffType.INSERT))
                    .allowDelete(false)
                    .build()
            );
            TenantDiffApplyExecuteResponse response = applyService.execute(plan);
            assertEquals(ApplyRecordStatus.SUCCESS, response.getStatus());

            // 确认 apply 已写入
            assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order WHERE tenantsid = 2 AND order_code = 'ORD-002'",
                Integer.class), "ORD-002 应已插入");

            // Rollback
            TenantDiffRollbackResponse rollbackResponse = rollbackService.rollback(response.getApplyId());
            assertTrue(rollbackResponse.getApplyResult().isSuccess());
            assertTrue(rollbackResponse.getApplyResult().getAffectedRows() > 0);

            // 验证 ORD-002 主表被删除
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order WHERE tenantsid = 2 AND order_code = 'ORD-002'",
                Integer.class), "Rollback 后 ORD-002 应被删除");
        }
    }

    // ================================================================
    // 3层级联测试
    // ================================================================

    @Nested
    @DisplayName("3层级联")
    class ThreeLayerCascadeTests {

        @Test
        @DisplayName("3层级联: Apply INSERT 全部3层 → Rollback → 无 FK 约束违反且全部清理")
        void threeLayerCascade_applyAndRollback_allCleanedUp() {
            Long sessionId = createAndCompare();

            // Apply ORD-002 INSERT（3层：order + order_item + order_item_detail）
            ApplyPlan plan = applyService.buildPlan(
                sessionId, ApplyDirection.A_TO_B,
                ApplyOptions.builder()
                    .mode(ApplyMode.EXECUTE)
                    .businessTypes(List.of("EXAMPLE_ORDER"))
                    .businessKeys(List.of("ORD-002"))
                    .diffTypes(List.of(DiffType.INSERT))
                    .allowDelete(false)
                    .build()
            );
            TenantDiffApplyExecuteResponse response = applyService.execute(plan);
            assertEquals(ApplyRecordStatus.SUCCESS, response.getStatus());

            // 验证3层都已写入
            assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order WHERE tenantsid = 2 AND order_code = 'ORD-002'",
                Integer.class), "第1层: ORD-002 主表应已插入");

            Long newOrderId = jdbcTemplate.queryForObject(
                "SELECT id FROM example_order WHERE tenantsid = 2 AND order_code = 'ORD-002'",
                Long.class);
            int itemCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order_item WHERE tenantsid = 2 AND order_id = ?",
                Integer.class, newOrderId);
            assertTrue(itemCount > 0, "第2层: ORD-002 子表应有记录");

            Long newItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM example_order_item WHERE tenantsid = 2 AND order_id = ?",
                Long.class, newOrderId);
            int detailCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order_item_detail WHERE tenantsid = 2 AND order_item_id = ?",
                Integer.class, newItemId);
            assertTrue(detailCount > 0, "第3层: ORD-002 明细表应有记录");

            // Rollback
            TenantDiffRollbackResponse rollbackResponse = rollbackService.rollback(response.getApplyId());
            assertTrue(rollbackResponse.getApplyResult().isSuccess());

            // 验证3层全部清理
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order WHERE tenantsid = 2 AND order_code = 'ORD-002'",
                Integer.class), "Rollback 后第1层 ORD-002 应被删除");

            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order_item WHERE tenantsid = 2 AND order_id = ?",
                Integer.class, newOrderId), "Rollback 后第2层子表记录应被清理");

            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order_item_detail WHERE tenantsid = 2 AND order_item_id = ?",
                Integer.class, newItemId), "Rollback 后第3层明细记录应被清理");
        }
    }

    // ================================================================
    // 混合业务类型
    // ================================================================

    @Nested
    @DisplayName("混合业务类型")
    class MixedBusinessTypeTests {

        @Test
        @DisplayName("同时对比 EXAMPLE_PRODUCT + EXAMPLE_ORDER")
        void mixedCompare_bothTypesDetected() {
            CreateDiffSessionRequest request = CreateDiffSessionRequest.builder()
                .sourceTenantId(1L)
                .targetTenantId(2L)
                .scope(TenantModelScope.builder()
                    .businessTypes(List.of("EXAMPLE_PRODUCT", "EXAMPLE_ORDER"))
                    .build())
                .build();
            Long sessionId = diffService.createSession(request);
            diffService.runCompare(sessionId);

            DiffSessionSummaryResponse summary = diffService.getSessionSummary(sessionId);
            assertEquals("SUCCESS", summary.getStatus().name());
            // Product: PROD-002(UPDATE) + PROD-003(INSERT) = 2
            // Order: ORD-001(UPDATE) + ORD-002(INSERT) = 2
            assertTrue(summary.getStatistics().getTotalBusinesses() >= 4,
                "应至少检测到 4 个业务对象（2 产品 + 2 订单）");
        }
    }
}
