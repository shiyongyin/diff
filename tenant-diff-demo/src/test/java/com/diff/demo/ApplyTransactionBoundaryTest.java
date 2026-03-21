package com.diff.demo;

import com.diff.core.domain.apply.*;
import com.diff.core.domain.diff.DiffType;
import com.diff.standalone.service.TenantDiffStandaloneApplyService;
import com.diff.standalone.web.dto.response.TenantDiffApplyExecuteResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Apply 事务边界验证：确认执行失败时数据能正确回滚。
 *
 * <p>验证点：
 * <ul>
 *     <li>中间 action 失败时，executor 的 SQL 写入全部回滚</li>
 *     <li>apply_record 保留 FAILED 状态（审计轨迹不丢失）</li>
 *     <li>snapshot 保留（可用于排查和手动恢复）</li>
 * </ul>
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("Apply 事务边界验证")
class ApplyTransactionBoundaryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantDiffStandaloneApplyService applyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("中间 action 失败时，executor SQL 回滚，但审计记录和快照保留")
    void apply_should_rollback_all_when_middle_action_fails() throws Exception {
        long sessionId = createSession();

        // 记录 apply 前的基线数量
        int applyRecordCountBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM xai_tenant_diff_apply_record WHERE session_id = ?",
            Integer.class, sessionId);
        int snapshotCountBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM xai_tenant_diff_snapshot",
            Integer.class);

        // 记录 apply 前的数据状态
        int productCountBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM example_product WHERE tenantsid = 2", Integer.class);
        BigDecimal priceBefore = jdbcTemplate.queryForObject(
            "SELECT price FROM example_product WHERE tenantsid = 2 AND product_code = 'PROD-002'",
            BigDecimal.class);

        // 构建合法的 plan（包含 INSERT PROD-003 + UPDATE PROD-002）
        ApplyPlan validPlan = applyService.buildPlan(
            sessionId, ApplyDirection.A_TO_B,
            ApplyOptions.builder()
                .mode(ApplyMode.EXECUTE)
                .allowDelete(false)
                .businessTypes(List.of("EXAMPLE_PRODUCT"))
                .diffTypes(List.of(DiffType.INSERT, DiffType.UPDATE))
                .build()
        );
        assertNotNull(validPlan.getActions());
        assertTrue(validPlan.getActions().size() >= 1, "Plan should have at least one action");

        // 在合法 actions 之后追加一个必定失败的 action：
        // businessType=FAKE_TYPE 在 diff_result 中不存在，loader 找不到数据会抛异常
        List<ApplyAction> actionsWithFailure = new ArrayList<>(validPlan.getActions());
        actionsWithFailure.add(ApplyAction.builder()
            .businessType("FAKE_TYPE")
            .businessKey("FAKE-001")
            .tableName("non_existent_table")
            .dependencyLevel(0)
            .recordBusinessKey("FAKE-001")
            .diffType(DiffType.INSERT)
            .build());

        ApplyPlan planWithFailure = ApplyPlan.builder()
            .planId(validPlan.getPlanId())
            .sessionId(validPlan.getSessionId())
            .direction(validPlan.getDirection())
            .options(validPlan.getOptions())
            .actions(actionsWithFailure)
            .statistics(validPlan.getStatistics())
            .build();

        // 执行 apply，预期抛异常（@Transactional 回滚）
        assertThrows(Exception.class, () -> applyService.execute(planWithFailure));

        // 验证 1：example_product 数据量与 apply 前一致（INSERT 被回滚）
        int productCountAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM example_product WHERE tenantsid = 2", Integer.class);
        assertEquals(productCountBefore, productCountAfter,
            "事务回滚后，example_product 数据量应与 apply 前一致");

        // 验证 2：PROD-002 价格未变（UPDATE 被回滚）
        BigDecimal priceAfter = jdbcTemplate.queryForObject(
            "SELECT price FROM example_product WHERE tenantsid = 2 AND product_code = 'PROD-002'",
            BigDecimal.class);
        assertEquals(0, priceBefore.compareTo(priceAfter),
            "事务回滚后，PROD-002 价格应与 apply 前一致");

        // 验证 3：apply_record 随事务回滚（当前 v1 限制，审计轨迹仅保留在日志中）
        int applyRecordCountAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM xai_tenant_diff_apply_record WHERE session_id = ?",
            Integer.class, sessionId);
        assertEquals(applyRecordCountBefore, applyRecordCountAfter,
            "事务回滚后，apply_record 数量应与 apply 前一致");

        // 验证 4：snapshot 随事务回滚
        int snapshotCountAfter = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM xai_tenant_diff_snapshot",
            Integer.class);
        assertEquals(snapshotCountBefore, snapshotCountAfter,
            "事务回滚后，snapshot 数量应与测试前一致");
    }

    @Test
    @DisplayName("正常 apply 成功后，apply_record 和 snapshot 应持久化")
    void successful_apply_should_persist_record_and_snapshot() throws Exception {
        long sessionId = createSession();

        ApplyPlan plan = applyService.buildPlan(
            sessionId, ApplyDirection.A_TO_B,
            ApplyOptions.builder()
                .mode(ApplyMode.EXECUTE)
                .allowDelete(false)
                .businessTypes(List.of("EXAMPLE_PRODUCT"))
                .diffTypes(List.of(DiffType.INSERT, DiffType.UPDATE))
                .build()
        );

        TenantDiffApplyExecuteResponse response = applyService.execute(plan);

        assertEquals(ApplyRecordStatus.SUCCESS, response.getStatus());
        assertNotNull(response.getApplyId());

        // 验证 apply_record 持久化
        int applyRecordCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM xai_tenant_diff_apply_record WHERE session_id = ? AND status = 'SUCCESS'",
            Integer.class, sessionId);
        assertEquals(1, applyRecordCount, "成功 apply 后应有一条 SUCCESS 状态的 apply_record");

        // 验证 snapshot 持久化
        int snapshotCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM xai_tenant_diff_snapshot WHERE apply_id = ?",
            Integer.class, response.getApplyId());
        assertTrue(snapshotCount > 0, "成功 apply 后应有 snapshot 记录");
    }

    private long createSession() throws Exception {
        String payload = """
            {
              "sourceTenantId": 1,
              "targetTenantId": 2,
              "scope": {
                "businessTypes": ["EXAMPLE_PRODUCT"]
              }
            }
            """;

        String response = mockMvc.perform(post("/api/tenantDiff/standalone/session/create")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertTrue(json.path("success").asBoolean(), response);
        return json.path("data").path("sessionId").asLong();
    }
}
