package com.diff.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对抗性护栏端到端测试：验证系统对恶意/边界输入的防御能力。
 *
 * <h3>测试目标</h3>
 * <p>本测试类模拟攻击者或异常客户端可能触发的边界场景，
 * 验证系统在每种情况下都能正确拒绝并保持数据不变。
 * 通过缩短 TTL 配置（{@code preview-token-ttl=PT1S, max-compare-age=PT1S}）
 * 使时效性相关的护栏在测试中可控地触发。</p>
 *
 * <h3>覆盖场景</h3>
 * <ul>
 *   <li><b>过期 previewToken</b> — 篡改 token 中的时间戳使其过期 → DIFF_E_2015</li>
 *   <li><b>过时比对结果</b> — 篡改 session 的完成时间使其超过 max-compare-age → DIFF_E_2016</li>
 *   <li><b>超行数上限</b> — 设置 maxAffectedRows=1 但实际有 2 个 action → DIFF_E_2001</li>
 *   <li><b>禁止删除时请求删除</b> — allowDelete=false 但 diffTypes 包含 DELETE → DIFF_E_2002</li>
 *   <li><b>重复同步</b> — 同一 session 执行两次同步 → DIFF_E_1004</li>
 *   <li><b>B→A 反向同步 + 回滚</b> — 验证反向（目标→源）同步和回滚的正确性</li>
 *   <li><b>孤儿子行过滤</b> — 存在外键悬挂的脏数据时，比对和预览应自动过滤</li>
 * </ul>
 *
 * <h3>运行前提</h3>
 * <ul>
 *   <li>需要设置 {@code TENANT_DIFF_TEST_MYSQL_ENABLED=true}</li>
 *   <li>需要运行中的 MySQL 实例（由 {@code application-mysql-e2e.yml} 配置）</li>
 *   <li>每个测试方法后通过 {@link DirtiesContext} 重建上下文，保证测试隔离</li>
 * </ul>
 *
 * @see MysqlReleaseGateE2ETest 发布门禁主测试（基本功能路径）
 */
@SpringBootTest(properties = {
    "tenant-diff.apply.preview-token-ttl=PT1S",
    "tenant-diff.apply.max-compare-age=PT1S"
})
@AutoConfigureMockMvc
@Tag("mysql-e2e")
@EnabledIfEnvironmentVariable(named = "TENANT_DIFF_TEST_MYSQL_ENABLED", matches = "(?i:true|1|yes)")
@ActiveProfiles({"test", "mysql-e2e"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MysqlAdversarialGuardrailE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 验证过期 previewToken 被拒绝。
     *
     * <p><b>测什么：</b>将 previewToken 中的时间戳篡改为过期值后发起 PARTIAL 同步，
     * 应返回 422 + DIFF_E_2015。</p>
     *
     * <p><b>为什么：</b>previewToken 携带生成时的时间戳，用于防止用户长时间持有旧预览结果后执行。
     * 在预览和执行之间，源/目标数据可能已变更，过期 token 必须被拒绝。</p>
     *
     * <p><b>如何验证：</b>通过 {@link #expirePreviewToken} 篡改 token 时间戳 →
     * 执行同步 → 断言 422 + DIFF_E_2015，且目标表无数据变更。</p>
     */
    @Test
    void partial_execute_should_reject_expired_preview_token_on_real_mysql() throws Exception {
        long sessionId = createProductSession();
        PreviewData preview = previewProduct(sessionId);
        ActionPreview insertAction = preview.findByRecordBusinessKey("PROD-003");

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "selectionMode": "PARTIAL",
                        "selectedActionIds": ["%s"],
                        "previewToken": "%s"
                      }
                    }
                    """.formatted(sessionId, insertAction.actionId, expirePreviewToken(preview.previewToken))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_2015"));

        assertEquals(0, countTargetProduct("PROD-003"));
    }

    /**
     * 验证过时比对结果被拒绝。
     *
     * <p><b>测什么：</b>将 session 的 finished_at 篡改为 5 分钟前（超过 max-compare-age=PT1S），
     * 执行同步时应返回 409 + DIFF_E_2016。</p>
     *
     * <p><b>为什么：</b>比对结果有时效性——如果源/目标数据在比对完成后发生了变化，
     * 基于旧比对结果执行同步可能导致数据不一致。max-compare-age 配置定义了
     * 比对结果的最大有效期，超期后必须重新比对。</p>
     *
     * <p><b>如何验证：</b>篡改 DB 中 session 的完成时间 → 执行同步 →
     * 断言 409 + DIFF_E_2016，且目标表无数据变更。</p>
     */
    @Test
    void execute_should_reject_stale_compare_on_real_mysql() throws Exception {
        long sessionId = createProductSession();
        jdbcTemplate.update(
            "UPDATE xai_tenant_diff_session SET finished_at = ? WHERE id = ?",
            Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)),
            sessionId
        );

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": false,
                        "businessTypes": ["EXAMPLE_PRODUCT"],
                        "diffTypes": ["INSERT", "UPDATE"]
                      }
                    }
                    """.formatted(sessionId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_2016"));

        assertEquals(0, countTargetProduct("PROD-003"));
        BigDecimal unchangedPrice = jdbcTemplate.queryForObject(
            "SELECT price FROM example_product WHERE tenantsid = 2 AND product_code = 'PROD-002'",
            BigDecimal.class
        );
        assertEquals(0, new BigDecimal("249.00").compareTo(unchangedPrice));
    }

    /**
     * 验证 maxAffectedRows 超限时同步被拒绝。
     *
     * <p><b>测什么：</b>设置 maxAffectedRows=1，但实际有 2 个 action 待同步，
     * 应返回 422 + DIFF_E_2001。</p>
     *
     * <p><b>为什么：</b>maxAffectedRows 是一道安全防线，防止用户误操作导致大规模数据变更。
     * 如果预计影响行数超过上限，框架必须在执行前拦截。</p>
     *
     * <p><b>如何验证：</b>执行同步 → 断言 422 + DIFF_E_2001，
     * 且 PROD-003 未插入、PROD-002 名称未变。</p>
     */
    @Test
    void execute_should_reject_when_max_affected_rows_exceeded_on_real_mysql() throws Exception {
        long sessionId = createProductSession();

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": false,
                        "maxAffectedRows": 1,
                        "businessTypes": ["EXAMPLE_PRODUCT"],
                        "diffTypes": ["INSERT", "UPDATE"]
                      }
                    }
                    """.formatted(sessionId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_2001"));

        assertEquals(0, countTargetProduct("PROD-003"));
        assertEquals("高级套餐B-改", productName(2L, "PROD-002"));
    }

    /**
     * 验证 allowDelete=false 时 DELETE 类型同步被拒绝。
     *
     * <p><b>测什么：</b>allowDelete=false 但 diffTypes 包含 DELETE，
     * 应返回 422 + DIFF_E_2002。</p>
     *
     * <p><b>为什么：</b>删除操作是最高风险的数据变更，allowDelete 开关默认关闭。
     * 当开关关闭时，即使客户端在 diffTypes 中传入 DELETE，
     * 框架也必须拒绝（而非静默忽略），让用户明确感知并做出决策。</p>
     *
     * <p><b>如何验证：</b>执行同步 → 断言 422 + DIFF_E_2002，
     * 且待删除数据（ORD-DEL / ITEM-DEL / DTL-DEL）仍然存在。</p>
     */
    @Test
    void execute_should_reject_delete_when_allow_delete_is_false_on_real_mysql() throws Exception {
        long sessionId = createOrderSession();

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": false,
                        "businessTypes": ["EXAMPLE_ORDER"],
                        "diffTypes": ["DELETE"]
                      }
                    }
                    """.formatted(sessionId)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_2002"));

        assertEquals(1, countOrder(2L, "ORD-DEL"));
        assertEquals(1, countItemByCode("ITEM-DEL"));
        assertEquals(1, countDetailByCode("DTL-DEL"));
    }

    /**
     * 验证同一 session 的重复同步被拒绝。
     *
     * <p><b>测什么：</b>对同一 sessionId 连续执行两次同步：第一次成功，
     * 第二次应返回 409 + DIFF_E_1004（SESSION_ALREADY_APPLIED）。</p>
     *
     * <p><b>为什么：</b>一个比对结果只应被同步一次。重复执行可能导致幂等性问题
     * （如 INSERT 操作重复插入、UPDATE 操作覆盖回滚后的数据等）。</p>
     *
     * <p><b>如何验证：</b>两次执行同步 → 第一次 200 + affectedRows=2，
     * 第二次 409 + DIFF_E_1004。确认 apply_record 只有 1 条，目标数据仅被写入一次。</p>
     */
    @Test
    void same_session_should_reject_duplicate_apply_on_real_mysql() throws Exception {
        long sessionId = createProductSession();

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": false,
                        "businessTypes": ["EXAMPLE_PRODUCT"],
                        "diffTypes": ["INSERT", "UPDATE"]
                      }
                    }
                    """.formatted(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.applyResult.affectedRows").value(2));

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": false,
                        "businessTypes": ["EXAMPLE_PRODUCT"],
                        "diffTypes": ["INSERT", "UPDATE"]
                      }
                    }
                    """.formatted(sessionId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_1004"));

        assertEquals(1, countApplyRecordsForSession(sessionId));
        assertEquals(1, countTargetProduct("PROD-003"));
    }

    /**
     * 验证 B→A 反向同步及回滚的正确性。
     *
     * <p><b>测什么：</b>使用 direction=B_TO_A，将目标租户（tenant 2）的 PROD-002 数据
     * 同步到源租户（tenant 1）；然后回滚，验证源租户数据还原。</p>
     *
     * <p><b>为什么：</b>框架支持双向同步。B→A 的写入目标是源租户而非目标租户，
     * 回滚快照和租约逻辑需要正确切换方向。此测试验证反向链路的完整性。</p>
     *
     * <p><b>如何验证：</b></p>
     * <ol>
     *   <li>B→A 同步 PROD-002 UPDATE → tenant 1 的 product_name 变为"高级套餐B-改"</li>
     *   <li>回滚 → tenant 1 的 product_name 恢复为"高级套餐B"，price 恢复为 199.00</li>
     * </ol>
     */
    @Test
    void b_to_a_update_apply_and_rollback_should_work_on_real_mysql() throws Exception {
        long sessionId = createProductSession();

        String applyResponse = mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "B_TO_A",
                      "options": {
                        "allowDelete": false,
                        "businessTypes": ["EXAMPLE_PRODUCT"],
                        "businessKeys": ["PROD-002"],
                        "diffTypes": ["UPDATE"]
                      }
                    }
                    """.formatted(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.applyResult.affectedRows").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();

        long applyId = objectMapper.readTree(applyResponse).path("data").path("applyId").asLong();

        assertEquals("高级套餐B-改", productName(1L, "PROD-002"));
        assertEquals(0, new BigDecimal("249.00").compareTo(productPrice(1L, "PROD-002")));

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "applyId": %d
                    }
                    """.formatted(applyId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.verification.success").value(true));

        assertEquals("高级套餐B", productName(1L, "PROD-002"));
        assertEquals(0, new BigDecimal("199.00").compareTo(productPrice(1L, "PROD-002")));
    }

    /**
     * 验证比对和预览自动过滤孤儿子行（外键悬挂的脏数据）。
     *
     * <p><b>测什么：</b>向 example_order_item 和 example_order_item_detail 插入
     * 外键指向不存在父记录的脏数据（ITEM-ORPHAN / DTL-ORPHAN），
     * 验证比对和预览不会包含这些孤儿行。</p>
     *
     * <p><b>为什么：</b>生产环境中可能存在历史遗留的脏数据（外键悬挂）。
     * 框架不应将这些数据纳入差异对比，否则会导致同步时外键约束失败
     * 或产生无意义的变更。同时，warningCount 应为 0（孤儿行不是告警，而是静默过滤）。</p>
     *
     * <p><b>如何验证：</b></p>
     * <ol>
     *   <li>插入孤儿子行 → 创建 session → session 状态为 SUCCESS 且 warningCount=0</li>
     *   <li>预览 action 列表不包含 ITEM-ORPHAN 和 DTL-ORPHAN</li>
     *   <li>孤儿行在数据库中仍然存在（未被清理，仅被过滤）</li>
     * </ol>
     */
    @Test
    void order_compare_should_ignore_orphan_child_rows_on_real_mysql() throws Exception {
        jdbcTemplate.update(
            "INSERT INTO example_order_item (tenantsid, item_code, order_id, product_name, quantity, unit_price) VALUES (?, ?, ?, ?, ?, ?)",
            2L, "ITEM-ORPHAN", 999999L, "脏数据孤儿子项", 7, new BigDecimal("1.00")
        );
        jdbcTemplate.update(
            "INSERT INTO example_order_item_detail (tenantsid, detail_code, order_item_id, detail_name, detail_value) VALUES (?, ?, ?, ?, ?)",
            2L, "DTL-ORPHAN", 888888L, "脏数据孤儿明细", "DIRTY"
        );

        long sessionId = createOrderSession();

        mockMvc.perform(get("/api/tenantDiff/standalone/session/get")
                .param("sessionId", String.valueOf(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.warningCount").value(0))
            .andExpect(jsonPath("$.data.statistics.totalBusinesses").value(3));

        PreviewData preview = previewOrder(sessionId, true);
        assertEquals(10, preview.actions.size());
        assertFalse(preview.containsRecordBusinessKey("ITEM-ORPHAN"));
        assertFalse(preview.containsRecordBusinessKey("DTL-ORPHAN"));
        assertEquals(1, countItemByCode("ITEM-ORPHAN"));
        assertEquals(1, countDetailByCode("DTL-ORPHAN"));
    }

    // ======================== 辅助方法 ========================

    /** 创建 EXAMPLE_PRODUCT 比对 session 并返回 sessionId。 */
    private long createProductSession() throws Exception {
        String response = mockMvc.perform(post("/api/tenantDiff/standalone/session/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sourceTenantId": 1,
                      "targetTenantId": 2,
                      "scope": {
                        "businessTypes": ["EXAMPLE_PRODUCT"]
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).path("data").path("sessionId").asLong();
    }

    /** 创建 EXAMPLE_ORDER 比对 session 并返回 sessionId。 */
    private long createOrderSession() throws Exception {
        String response = mockMvc.perform(post("/api/tenantDiff/standalone/session/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sourceTenantId": 1,
                      "targetTenantId": 2,
                      "scope": {
                        "businessTypes": ["EXAMPLE_ORDER"]
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(response).path("data").path("sessionId").asLong();
    }

    /** 调用 EXAMPLE_PRODUCT 的预览接口。 */
    private PreviewData previewProduct(long sessionId) throws Exception {
        String response = mockMvc.perform(post("/api/tenantDiff/standalone/apply/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": false,
                        "businessTypes": ["EXAMPLE_PRODUCT"],
                        "diffTypes": ["INSERT", "UPDATE"]
                      }
                    }
                    """.formatted(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return toPreviewData(response);
    }

    /** 调用 EXAMPLE_ORDER 的预览接口，可指定是否允许 DELETE。 */
    private PreviewData previewOrder(long sessionId, boolean allowDelete) throws Exception {
        String response = mockMvc.perform(post("/api/tenantDiff/standalone/apply/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "sessionId": %d,
                      "direction": "A_TO_B",
                      "options": {
                        "allowDelete": %s,
                        "businessTypes": ["EXAMPLE_ORDER"],
                        "diffTypes": ["INSERT", "UPDATE", "DELETE"]
                      }
                    }
                    """.formatted(sessionId, allowDelete)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
        return toPreviewData(response);
    }

    /** 将预览接口的 JSON 响应解析为 PreviewData。 */
    private PreviewData toPreviewData(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response).path("data");
        List<ActionPreview> actions = new ArrayList<>();
        for (JsonNode action : root.path("actions")) {
            actions.add(new ActionPreview(
                action.path("actionId").asText(),
                action.path("recordBusinessKey").asText()
            ));
        }
        return new PreviewData(root.path("previewToken").asText(), actions);
    }

    /** 篡改 previewToken 中的时间戳部分，使其立即过期（当前时间 - 5 秒）。 */
    private String expirePreviewToken(String previewToken) {
        String[] parts = previewToken.split("_", 4);
        long expiredEpochSeconds = Math.max(0, java.time.Instant.now().getEpochSecond() - 5);
        return parts[0] + "_" + parts[1] + "_" + expiredEpochSeconds + "_" + parts[3];
    }

    private int countTargetProduct(String productCode) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM example_product WHERE tenantsid = 2 AND product_code = ?",
            Integer.class,
            productCode
        );
    }

    private String productName(Long tenantId, String productCode) {
        return jdbcTemplate.queryForObject(
            "SELECT product_name FROM example_product WHERE tenantsid = ? AND product_code = ?",
            String.class,
            tenantId,
            productCode
        );
    }

    private BigDecimal productPrice(Long tenantId, String productCode) {
        return jdbcTemplate.queryForObject(
            "SELECT price FROM example_product WHERE tenantsid = ? AND product_code = ?",
            BigDecimal.class,
            tenantId,
            productCode
        );
    }

    private int countApplyRecordsForSession(long sessionId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM xai_tenant_diff_apply_record WHERE session_id = ?",
            Integer.class,
            sessionId
        );
    }

    private int countOrder(Long tenantId, String orderCode) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM example_order WHERE tenantsid = ? AND order_code = ?",
            Integer.class,
            tenantId,
            orderCode
        );
    }

    private int countItemByCode(String itemCode) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM example_order_item WHERE tenantsid = 2 AND item_code = ?",
            Integer.class,
            itemCode
        );
    }

    private int countDetailByCode(String detailCode) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM example_order_item_detail WHERE tenantsid = 2 AND detail_code = ?",
            Integer.class,
            detailCode
        );
    }

    /** 预览接口的解析结果：包含 previewToken 和 action 列表。 */
    private record PreviewData(String previewToken, List<ActionPreview> actions) {
        private ActionPreview findByRecordBusinessKey(String recordBusinessKey) {
            return actions.stream()
                .filter(action -> recordBusinessKey.equals(action.recordBusinessKey))
                .findFirst()
                .orElseThrow();
        }

        private boolean containsRecordBusinessKey(String recordBusinessKey) {
            return actions.stream().anyMatch(action -> recordBusinessKey.equals(action.recordBusinessKey));
        }
    }

    /** 单个 action 的预览摘要。 */
    private record ActionPreview(String actionId, String recordBusinessKey) {
    }
}
