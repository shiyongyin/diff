package com.diff.demo;

import com.diff.demo.plugin.ExampleProductPlugin;
import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 告警降级端到端测试：验证插件加载异常时系统的优雅降级行为。
 *
 * <h3>测试目标</h3>
 * <p>当业务插件加载某条记录失败时，框架应将该记录标记为告警（warning）而非整体失败，
 * 其余正常记录的同步和回滚应不受影响。</p>
 *
 * <h3>为什么需要降级</h3>
 * <p>生产环境中，部分记录可能因数据异常或第三方依赖故障导致加载失败。
 * 如果一条记录的失败导致整个 session 失败，会阻断所有正常记录的同步。
 * 框架采用"部分成功 + 告警"策略：失败的记录被跳过并记录告警，
 * 用户可以查看告警原因并决定是否手动处理。</p>
 *
 * <h3>测试策略</h3>
 * <p>通过 {@link TestConfiguration} 注入故障版 {@link ExampleProductPlugin}，
 * 在加载 PROD-003 时抛出 {@link IllegalStateException}。
 * 框架应将其降级为 warning，其余记录（PROD-001 / PROD-002）正常处理。</p>
 *
 * <h3>运行前提</h3>
 * <ul>
 *   <li>需要设置 {@code TENANT_DIFF_TEST_MYSQL_ENABLED=true}</li>
 *   <li>需要运行中的 MySQL 实例（由 {@code application-mysql-e2e.yml} 配置）</li>
 *   <li>{@code allow-bean-definition-overriding=true} 允许测试配置覆盖默认 Bean</li>
 * </ul>
 *
 * @see MysqlReleaseGateE2ETest 发布门禁主测试
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@Tag("mysql-e2e")
@EnabledIfEnvironmentVariable(named = "TENANT_DIFF_TEST_MYSQL_ENABLED", matches = "(?i:true|1|yes)")
@ActiveProfiles({"test", "mysql-e2e"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MysqlWarningDegradationE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 验证加载告警降级后，预览/同步/回滚的完整链路仍然可用。
     *
     * <p><b>测什么：</b>插件加载 PROD-003 时注入异常 → session 状态为 SUCCESS 但 warningCount=1，
     * 告警记录包含 side=source / businessType / businessKey / 错误信息。
     * 预览和同步仅处理 PROD-002（UPDATE），PROD-003 被跳过。
     * 同步后回滚可正常还原。</p>
     *
     * <p><b>为什么：</b>这是框架"部分成功"策略的核心验证。
     * 必须确保：</p>
     * <ul>
     *   <li>告警信息完整可查（运维可据此排查问题）</li>
     *   <li>告警记录不会出现在预览和同步的 action 中</li>
     *   <li>正常记录的同步和回滚不受影响</li>
     * </ul>
     *
     * <p><b>如何验证：</b></p>
     * <ol>
     *   <li>创建 session → 验证 warningCount=1，告警详情正确</li>
     *   <li>预览 → totalActions=1，updateCount=1，insertCount=0（PROD-003 被跳过）</li>
     *   <li>同步 → affectedRows=1，PROD-002 名称变为"高级套餐B"，PROD-003 未被插入</li>
     *   <li>回滚 → PROD-002 名称恢复为"高级套餐B-改"，价格恢复为 249.00</li>
     * </ol>
     */
    @Test
    void compare_warning_should_degrade_to_partial_success_and_still_allow_apply_and_rollback_on_real_mysql() throws Exception {
        long sessionId = createProductSession();

        mockMvc.perform(get("/api/tenantDiff/standalone/session/get")
                .param("sessionId", String.valueOf(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.warningCount").value(1))
            .andExpect(jsonPath("$.data.warnings[0].side").value("source"))
            .andExpect(jsonPath("$.data.warnings[0].businessType").value("EXAMPLE_PRODUCT"))
            .andExpect(jsonPath("$.data.warnings[0].businessKey").value("PROD-003"))
            .andExpect(jsonPath("$.data.warnings[0].message", containsString("mysql e2e injected load failure for PROD-003")));

        String previewResponse = mockMvc.perform(post("/api/tenantDiff/standalone/apply/preview")
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
            .andExpect(jsonPath("$.data.statistics.totalActions").value(1))
            .andExpect(jsonPath("$.data.statistics.insertCount").value(0))
            .andExpect(jsonPath("$.data.statistics.updateCount").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode preview = objectMapper.readTree(previewResponse).path("data");
        assertEquals("PROD-002", preview.path("actions").get(0).path("recordBusinessKey").asText());

        String applyResponse = mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
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
            .andExpect(jsonPath("$.data.applyResult.affectedRows").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();

        long applyId = objectMapper.readTree(applyResponse).path("data").path("applyId").asLong();

        assertEquals("高级套餐B", productName(2L, "PROD-002"));
        assertEquals(0, countTargetProduct("PROD-003"));

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

        assertEquals("高级套餐B-改", productName(2L, "PROD-002"));
        assertEquals(0, new BigDecimal("249.00").compareTo(productPrice(2L, "PROD-002")));
        assertEquals(0, countTargetProduct("PROD-003"));
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

    /**
     * 故障注入测试配置：替换默认 ExampleProductPlugin，使其在加载 PROD-003 时抛出异常。
     *
     * <p>通过 {@code @Primary} 覆盖原始 Bean，在 resolveJdbcTemplate 返回的
     * {@link FaultInjectingJdbcTemplate} 中对特定查询注入故障：
     * 当查询条件为 {@code tenantsid=1 AND product_code='PROD-003'} 时抛出异常。</p>
     */
    @TestConfiguration
    static class WarningPluginTestConfiguration {

        @Bean("exampleProductPlugin")
        @Primary
        ExampleProductPlugin exampleProductPlugin(ObjectMapper objectMapper, DiffDataSourceRegistry dataSourceRegistry) {
            return new ExampleProductPlugin(objectMapper, dataSourceRegistry) {
                @Override
                protected JdbcTemplate resolveJdbcTemplate(com.diff.core.domain.scope.LoadOptions options) {
                    return new FaultInjectingJdbcTemplate(super.resolveJdbcTemplate(options));
                }
            };
        }

        private static final class FaultInjectingJdbcTemplate extends JdbcTemplate {
            private final JdbcTemplate delegate;

            private FaultInjectingJdbcTemplate(JdbcTemplate delegate) {
                this.delegate = delegate;
            }

            @Override
            public <T> java.util.List<T> queryForList(String sql, Class<T> elementType, Object... args) {
                return delegate.queryForList(sql, elementType, args);
            }

            @Override
            public java.util.List<java.util.Map<String, Object>> queryForList(String sql, Object... args) {
                if (sql != null
                    && sql.startsWith("SELECT * FROM example_product")
                    && args != null
                    && args.length >= 2
                    && Long.valueOf(1L).equals(args[0])
                    && "PROD-003".equals(args[1])) {
                    throw new IllegalStateException("mysql e2e injected load failure for PROD-003");
                }
                return delegate.queryForList(sql, args);
            }
        }
    }
}
