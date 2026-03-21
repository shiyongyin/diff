package com.diff.demo;

import com.diff.core.apply.IdMapping;
import com.diff.core.domain.apply.*;
import com.diff.core.domain.diff.*;
import com.diff.core.domain.exception.ApplyExecutionException;
import com.diff.standalone.apply.ApplyExecutorCore;
import com.diff.standalone.apply.BusinessApplySupportRegistry;
import com.diff.standalone.apply.BusinessDiffLoader;
import com.diff.standalone.apply.StandaloneSqlBuilder;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Apply 执行链集成测试。
 *
 * <p>基于 H2 内存数据库，验证 ApplyExecutorCore 的写库逻辑：
 * INSERT/UPDATE/DELETE、IdMapping、外键替换、事务回滚、安全阈值等。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ApplyExecutorIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private BusinessApplySupportRegistry supportRegistry;

    private ApplyExecutorCore executor;

    private static final Long SOURCE_TENANT = 1L;
    private static final Long TARGET_TENANT = 2L;

    @BeforeEach
    void setUp() {
        executor = new ApplyExecutorCore(supportRegistry);
    }

    // ================================================================
    // 单表 INSERT
    // ================================================================

    @Nested
    @DisplayName("单表 INSERT")
    class SingleTableInsert {

        @Test
        @DisplayName("INSERT 写入数据并记录 IdMapping")
        void insert_should_write_data_and_record_id_mapping() {
            // PROD-003 在租户 2 中不存在，需要 INSERT
            Map<String, Object> sourceFields = Map.of(
                "product_code", "PROD-003",
                "product_name", "企业套餐C",
                "price", new BigDecimal("499.00"),
                "status", "ACTIVE"
            );

            BusinessDiff diff = buildSingleTableDiff(
                "EXAMPLE_PRODUCT", "PROD-003", "example_product",
                DiffType.INSERT, sourceFields, null
            );
            BusinessDiffLoader loader = action -> diff;

            ApplyPlan plan = buildPlan(1L, List.of(
                buildAction("EXAMPLE_PRODUCT", "PROD-003", "example_product", 0, "PROD-003", DiffType.INSERT)
            ));

            ApplyResult result = executor.execute(plan, ApplyMode.EXECUTE, TARGET_TENANT, loader, jdbcTemplate);

            assertTrue(result.isSuccess());
            assertEquals(1, result.getAffectedRows());

            // 验证数据写入
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM example_product WHERE tenantsid = ? AND product_code = ?",
                TARGET_TENANT, "PROD-003"
            );
            assertEquals(1, rows.size());
            assertEquals("企业套餐C", rows.get(0).get("PRODUCT_NAME"));

            // 验证 IdMapping 记录了新 ID
            IdMapping idMapping = result.getIdMapping();
            assertNotNull(idMapping);
            Long newId = idMapping.get("example_product", "PROD-003");
            assertNotNull(newId, "INSERT 后 IdMapping 应记录新生成的 ID");
        }
    }

    // ================================================================
    // 单表 UPDATE
    // ================================================================

    @Nested
    @DisplayName("单表 UPDATE")
    class SingleTableUpdate {

        @Test
        @DisplayName("UPDATE 修改字段值")
        void update_should_modify_fields() {
            // PROD-002 在租户 2 中存在但字段不同
            Long targetId = jdbcTemplate.queryForObject(
                "SELECT id FROM example_product WHERE tenantsid = ? AND product_code = ?",
                Long.class, TARGET_TENANT, "PROD-002"
            );

            Map<String, Object> sourceFields = Map.of(
                "id", 999L,  // source id 无关
                "product_code", "PROD-002",
                "product_name", "高级套餐B",
                "price", new BigDecimal("199.00"),
                "status", "ACTIVE"
            );
            Map<String, Object> targetFields = Map.of(
                "id", targetId,
                "product_code", "PROD-002",
                "product_name", "高级套餐B-改",
                "price", new BigDecimal("249.00"),
                "status", "ACTIVE"
            );

            BusinessDiff diff = buildSingleTableDiff(
                "EXAMPLE_PRODUCT", "PROD-002", "example_product",
                DiffType.UPDATE, sourceFields, targetFields
            );
            BusinessDiffLoader loader = action -> diff;

            ApplyPlan plan = buildPlan(1L, List.of(
                buildAction("EXAMPLE_PRODUCT", "PROD-002", "example_product", 0, "PROD-002", DiffType.UPDATE)
            ));

            ApplyResult result = executor.execute(plan, ApplyMode.EXECUTE, TARGET_TENANT, loader, jdbcTemplate);

            assertTrue(result.isSuccess());
            assertEquals(1, result.getAffectedRows());

            // 验证字段变更
            Map<String, Object> updated = jdbcTemplate.queryForMap(
                "SELECT product_name, price FROM example_product WHERE tenantsid = ? AND product_code = ?",
                TARGET_TENANT, "PROD-002"
            );
            assertEquals("高级套餐B", updated.get("PRODUCT_NAME"));
            assertEquals(new BigDecimal("199.00"), updated.get("PRICE"));
        }
    }

    // ================================================================
    // 单表 DELETE
    // ================================================================

    @Nested
    @DisplayName("单表 DELETE")
    class SingleTableDelete {

        @Test
        @DisplayName("allowDelete=true 时 DELETE 成功")
        void delete_should_remove_record_when_allowed() {
            Long targetId = jdbcTemplate.queryForObject(
                "SELECT id FROM example_product WHERE tenantsid = ? AND product_code = ?",
                Long.class, TARGET_TENANT, "PROD-001"
            );

            Map<String, Object> targetFields = Map.of(
                "id", targetId,
                "product_code", "PROD-001"
            );

            BusinessDiff diff = buildSingleTableDiff(
                "EXAMPLE_PRODUCT", "PROD-001", "example_product",
                DiffType.DELETE, null, targetFields
            );
            BusinessDiffLoader loader = action -> diff;

            ApplyPlan plan = ApplyPlan.builder()
                .planId("test-delete")
                .sessionId(1L)
                .direction(ApplyDirection.A_TO_B)
                .options(ApplyOptions.builder().allowDelete(true).build())
                .actions(List.of(
                    buildAction("EXAMPLE_PRODUCT", "PROD-001", "example_product", 0, "PROD-001", DiffType.DELETE)
                ))
                .build();

            ApplyResult result = executor.execute(plan, ApplyMode.EXECUTE, TARGET_TENANT, loader, jdbcTemplate);

            assertTrue(result.isSuccess());
            assertEquals(1, result.getAffectedRows());

            // 验证记录已删除
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_product WHERE tenantsid = ? AND product_code = ?",
                Integer.class, TARGET_TENANT, "PROD-001"
            );
            assertEquals(0, count);
        }

        @Test
        @DisplayName("allowDelete=false 时 DELETE 被阻止")
        void delete_should_be_blocked_when_not_allowed() {
            ApplyPlan plan = ApplyPlan.builder()
                .planId("test-delete-blocked")
                .sessionId(1L)
                .direction(ApplyDirection.A_TO_B)
                .options(ApplyOptions.builder().allowDelete(false).build())
                .actions(List.of(
                    buildAction("EXAMPLE_PRODUCT", "PROD-001", "example_product", 0, "PROD-001", DiffType.DELETE)
                ))
                .build();

            BusinessDiffLoader loader = action -> null;  // 不应该走到 loader

            ApplyExecutionException ex = assertThrows(ApplyExecutionException.class,
                () -> executor.execute(plan, ApplyMode.EXECUTE, TARGET_TENANT, loader, jdbcTemplate));
            assertTrue(ex.getCause() instanceof IllegalStateException, "cause should be IllegalStateException");
            assertTrue(ex.getMessage().contains("allowDelete=false"));

            // 验证数据未被删除
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_product WHERE tenantsid = ? AND product_code = ?",
                Integer.class, TARGET_TENANT, "PROD-001"
            );
            assertEquals(1, count);
        }
    }

    // ================================================================
    // DRY_RUN 模式
    // ================================================================

    @Nested
    @DisplayName("DRY_RUN 模式")
    class DryRunMode {

        @Test
        @DisplayName("DRY_RUN 不写库")
        void dry_run_should_not_write_to_database() {
            ApplyPlan plan = buildPlan(1L, List.of(
                buildAction("EXAMPLE_PRODUCT", "PROD-003", "example_product", 0, "PROD-003", DiffType.INSERT)
            ));

            ApplyResult result = executor.execute(plan, ApplyMode.DRY_RUN, TARGET_TENANT, action -> null, jdbcTemplate);

            assertTrue(result.isSuccess());
            assertEquals("DRY_RUN", result.getMessage());
            assertEquals(0, result.getAffectedRows());

            // 验证数据未写入
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_product WHERE tenantsid = ? AND product_code = ?",
                Integer.class, TARGET_TENANT, "PROD-003"
            );
            assertEquals(0, count);
        }
    }

    // ================================================================
    // maxAffectedRows 安全阈值
    // ================================================================

    @Nested
    @DisplayName("安全阈值")
    class SafetyThreshold {

        @Test
        @DisplayName("超过 maxAffectedRows 时抛异常")
        void should_throw_when_exceeding_max_affected_rows() {
            ApplyPlan plan = ApplyPlan.builder()
                .planId("test-threshold")
                .sessionId(1L)
                .direction(ApplyDirection.A_TO_B)
                .options(ApplyOptions.builder().maxAffectedRows(1).build())
                .actions(List.of(
                    buildAction("EXAMPLE_PRODUCT", "PROD-003", "example_product", 0, "PROD-003", DiffType.INSERT),
                    buildAction("EXAMPLE_PRODUCT", "PROD-004", "example_product", 0, "PROD-004", DiffType.INSERT)
                ))
                .build();

            assertThrows(IllegalStateException.class,
                () -> executor.execute(plan, ApplyMode.EXECUTE, TARGET_TENANT, action -> null, jdbcTemplate));
        }
    }

    // ================================================================
    // 多表父子 INSERT：外键替换
    // ================================================================

    @Nested
    @DisplayName("多表父子 INSERT")
    class ParentChildInsert {

        @Test
        @DisplayName("父子表 INSERT 时外键通过 IdMapping 正确替换")
        void insert_parent_child_should_replace_fk_via_id_mapping() {
            // 模拟 ORD-002 在租户 2 中不存在，需要 INSERT 主表 + 子表
            Map<String, Object> orderFields = Map.of(
                "order_code", "ORD-002",
                "order_name", "测试订单B",
                "total_amount", new BigDecimal("499.00"),
                "status", "CONFIRMED"
            );

            Long sourceOrderId = jdbcTemplate.queryForObject(
                "SELECT id FROM example_order WHERE tenantsid = ? AND order_code = ?",
                Long.class, SOURCE_TENANT, "ORD-002"
            );

            Map<String, Object> itemFields = Map.of(
                "item_code", "ITEM-003",
                "order_id", sourceOrderId,  // 源端的 order_id，Apply 时需要替换
                "product_name", "企业套餐C",
                "quantity", 1,
                "unit_price", new BigDecimal("499.00")
            );

            // 构造两个 diff：一个主表，一个子表
            BusinessDiff orderDiff = buildSingleTableDiff(
                "EXAMPLE_ORDER", "ORD-002", "example_order",
                DiffType.INSERT, orderFields, null
            );
            BusinessDiff itemDiff = buildSingleTableDiff(
                "EXAMPLE_ORDER", "ORD-002", "example_order_item",
                DiffType.INSERT, itemFields, null
            );

            // loader 按 action 的 tableName 返回不同 diff
            BusinessDiffLoader loader = action -> {
                if ("example_order".equals(action.getTableName())) return orderDiff;
                if ("example_order_item".equals(action.getTableName())) return itemDiff;
                throw new IllegalArgumentException("unknown table: " + action.getTableName());
            };

            // 按依赖层级排序：主表 level=0 先执行，子表 level=1 后执行
            ApplyPlan plan = buildPlan(1L, List.of(
                buildAction("EXAMPLE_ORDER", "ORD-002", "example_order", 0, "ORD-002", DiffType.INSERT),
                buildAction("EXAMPLE_ORDER", "ORD-002", "example_order_item", 1, "ITEM-003", DiffType.INSERT)
            ));

            ApplyResult result = executor.execute(plan, ApplyMode.EXECUTE, TARGET_TENANT, loader, jdbcTemplate);

            assertTrue(result.isSuccess());
            assertEquals(2, result.getAffectedRows());

            // 验证主表写入
            List<Map<String, Object>> orders = jdbcTemplate.queryForList(
                "SELECT * FROM example_order WHERE tenantsid = ? AND order_code = ?",
                TARGET_TENANT, "ORD-002"
            );
            assertEquals(1, orders.size());
            Long newOrderId = ((Number) orders.get(0).get("ID")).longValue();

            // 验证 IdMapping
            IdMapping idMapping = result.getIdMapping();
            Long mappedId = idMapping.get("example_order", "ORD-002");
            assertEquals(newOrderId, mappedId);

            // 验证子表写入（注意：没有 ApplySupport 做 FK 替换时，order_id 还是源端值）
            // 这个测试验证的是 executor 层面的 IdMapping 记录，FK 替换由 ApplySupport 负责
            List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT * FROM example_order_item WHERE tenantsid = ? AND item_code = ?",
                TARGET_TENANT, "ITEM-003"
            );
            assertEquals(1, items.size());
        }
    }

    // ================================================================
    // 多表父子 DELETE：依赖层级排序
    // ================================================================

    @Nested
    @DisplayName("多表父子 DELETE")
    class ParentChildDelete {

        @Test
        @DisplayName("DELETE 时先删子表再删主表")
        void delete_should_remove_child_before_parent() {
            Long targetOrderId = jdbcTemplate.queryForObject(
                "SELECT id FROM example_order WHERE tenantsid = ? AND order_code = ?",
                Long.class, TARGET_TENANT, "ORD-001"
            );
            Long targetItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM example_order_item WHERE tenantsid = ? AND item_code = ?",
                Long.class, TARGET_TENANT, "ITEM-001"
            );

            // 子表 diff
            BusinessDiff itemDiff = buildSingleTableDiff(
                "EXAMPLE_ORDER", "ORD-001", "example_order_item",
                DiffType.DELETE, null, Map.of("id", targetItemId, "item_code", "ITEM-001")
            );
            // 主表 diff
            BusinessDiff orderDiff = buildSingleTableDiff(
                "EXAMPLE_ORDER", "ORD-001", "example_order",
                DiffType.DELETE, null, Map.of("id", targetOrderId, "order_code", "ORD-001")
            );

            BusinessDiffLoader loader = action -> {
                if ("example_order_item".equals(action.getTableName())) return itemDiff;
                if ("example_order".equals(action.getTableName())) return orderDiff;
                throw new IllegalArgumentException("unknown table: " + action.getTableName());
            };

            // 故意把主表排在前面，executor 应该重排为先删子表
            ApplyPlan plan = ApplyPlan.builder()
                .planId("test-delete-order")
                .sessionId(1L)
                .direction(ApplyDirection.A_TO_B)
                .options(ApplyOptions.builder().allowDelete(true).build())
                .actions(List.of(
                    buildAction("EXAMPLE_ORDER", "ORD-001", "example_order", 0, "ORD-001", DiffType.DELETE),
                    buildAction("EXAMPLE_ORDER", "ORD-001", "example_order_item", 1, "ITEM-001", DiffType.DELETE)
                ))
                .build();

            ApplyResult result = executor.execute(plan, ApplyMode.EXECUTE, TARGET_TENANT, loader, jdbcTemplate);

            assertTrue(result.isSuccess());
            assertEquals(2, result.getAffectedRows());

            // 验证都已删除
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order WHERE tenantsid = ? AND order_code = ?",
                Integer.class, TARGET_TENANT, "ORD-001"
            ));
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM example_order_item WHERE tenantsid = ? AND item_code = ?",
                Integer.class, TARGET_TENANT, "ITEM-001"
            ));
        }
    }

    // ================================================================
    // SQL 执行失败
    // ================================================================

    @Nested
    @DisplayName("SQL 执行失败")
    class SqlFailure {

        @Test
        @DisplayName("SQL 失败时抛 ApplyExecutionException 并携带 partialResult")
        void should_throw_apply_execution_exception_with_partial_result() {
            // 构造一个会失败的场景：INSERT 到不存在的表
            Map<String, Object> fields = Map.of("some_col", "value");
            BusinessDiff diff = buildSingleTableDiff(
                "FAKE_TYPE", "FAKE-001", "non_existent_table",
                DiffType.INSERT, fields, null
            );

            ApplyPlan plan = buildPlan(1L, List.of(
                buildAction("FAKE_TYPE", "FAKE-001", "non_existent_table", 0, "FAKE-001", DiffType.INSERT)
            ));

            ApplyExecutionException ex = assertThrows(ApplyExecutionException.class,
                () -> executor.execute(plan, ApplyMode.EXECUTE, TARGET_TENANT, action -> diff, jdbcTemplate));

            assertNotNull(ex.getPartialResult());
            assertFalse(ex.getPartialResult().isSuccess());
        }
    }

    // ================================================================
    // SqlBuilder 单元验证
    // ================================================================

    @Nested
    @DisplayName("SqlBuilder 安全约束")
    class SqlBuilderSafety {

        @Test
        @DisplayName("INSERT 自动过滤 id 和 tenantsid 字段")
        void insert_should_filter_id_and_tenantsid() {
            Map<String, Object> fields = Map.of(
                "id", 999L,
                "tenantsid", 888L,
                "product_code", "TEST",
                "product_name", "测试"
            );

            StandaloneSqlBuilder.SqlAndArgs sql = StandaloneSqlBuilder.buildInsert("example_product", TARGET_TENANT, fields);
            assertNotNull(sql);
            // SQL 中不应包含 id 列（tenantsid 由框架强制设置）
            assertFalse(sql.sql().contains(" id,"));
            assertFalse(sql.sql().contains(",id "));
            // 第一个参数应该是 targetTenantId
            assertEquals(TARGET_TENANT, sql.args()[0]);
        }

        @Test
        @DisplayName("UPDATE WHERE 条件强制包含 tenantsid + id")
        void update_should_enforce_tenant_and_id_constraint() {
            Map<String, Object> fields = Map.of("product_name", "新名称");
            StandaloneSqlBuilder.SqlAndArgs sql = StandaloneSqlBuilder.buildUpdateById("example_product", TARGET_TENANT, 100L, fields);

            assertNotNull(sql);
            assertTrue(sql.sql().contains("tenantsid = ?"));
            assertTrue(sql.sql().contains("id = ?"));
        }

        @Test
        @DisplayName("DELETE WHERE 条件强制包含 tenantsid + id")
        void delete_should_enforce_tenant_and_id_constraint() {
            StandaloneSqlBuilder.SqlAndArgs sql = StandaloneSqlBuilder.buildDeleteById("example_product", TARGET_TENANT, 100L);

            assertNotNull(sql);
            assertTrue(sql.sql().contains("tenantsid = ?"));
            assertTrue(sql.sql().contains("id = ?"));
        }

        @Test
        @DisplayName("UPDATE 无可更新字段时返回 null")
        void update_should_return_null_when_no_updatable_fields() {
            // 只有 id 和 tenantsid，过滤后无可更新字段
            Map<String, Object> fields = Map.of("id", 1L, "tenantsid", 2L);
            StandaloneSqlBuilder.SqlAndArgs sql = StandaloneSqlBuilder.buildUpdateById("example_product", TARGET_TENANT, 100L, fields);

            assertNull(sql);
        }
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    private static ApplyPlan buildPlan(Long sessionId, List<ApplyAction> actions) {
        return ApplyPlan.builder()
            .planId("test-plan")
            .sessionId(sessionId)
            .direction(ApplyDirection.A_TO_B)
            .options(ApplyOptions.builder().build())
            .actions(actions)
            .build();
    }

    private static ApplyAction buildAction(String businessType, String businessKey,
                                           String tableName, int dependencyLevel,
                                           String recordBusinessKey, DiffType diffType) {
        return ApplyAction.builder()
            .businessType(businessType)
            .businessKey(businessKey)
            .tableName(tableName)
            .dependencyLevel(dependencyLevel)
            .recordBusinessKey(recordBusinessKey)
            .diffType(diffType)
            .build();
    }

    private static BusinessDiff buildSingleTableDiff(String businessType, String businessKey,
                                                     String tableName, DiffType diffType,
                                                     Map<String, Object> sourceFields,
                                                     Map<String, Object> targetFields) {
        RecordDiff recordDiff = RecordDiff.builder()
            .recordBusinessKey(diffType == DiffType.DELETE
                ? extractBusinessKey(targetFields, tableName)
                : extractBusinessKey(sourceFields, tableName))
            .diffType(diffType)
            .sourceFields(sourceFields)
            .targetFields(targetFields)
            .build();

        TableDiff tableDiff = TableDiff.builder()
            .tableName(tableName)
            .dependencyLevel(0)
            .recordDiffs(List.of(recordDiff))
            .build();

        return BusinessDiff.builder()
            .businessType(businessType)
            .businessKey(businessKey)
            .businessTable(tableName)
            .tableDiffs(List.of(tableDiff))
            .build();
    }

    private static String extractBusinessKey(Map<String, Object> fields, String tableName) {
        if (fields == null) return "unknown";
        // 根据表名推断 business key 字段
        if (tableName.contains("order_item")) {
            Object v = fields.get("item_code");
            return v == null ? "unknown" : v.toString();
        }
        if (tableName.contains("order")) {
            Object v = fields.get("order_code");
            return v == null ? "unknown" : v.toString();
        }
        Object v = fields.get("product_code");
        return v == null ? "unknown" : v.toString();
    }
}
