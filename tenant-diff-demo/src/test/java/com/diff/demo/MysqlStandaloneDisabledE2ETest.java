package com.diff.demo;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.diff.standalone.datasource.DiffDataSourceRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone 功能开关端到端测试：验证 {@code tenant-diff.standalone.enabled=false} 时端点完全不暴露。
 *
 * <h3>测试目标</h3>
 * <p>当功能开关关闭时，所有 Standalone 相关的 Controller 不应注册到 Spring MVC，
 * 访问其 URL 应返回原生 404（而非框架自定义的错误响应）。</p>
 *
 * <h3>为什么需要这个测试</h3>
 * <p>在多模块部署中，某些微服务可能只需要核心比对能力而不需要 Standalone 模式。
 * 功能开关必须彻底禁用端点注册（而非仅返回错误），以避免安全扫描误报和接口文档污染。
 * 测试验证 404 响应中不包含 {@code "success"} 和 {@code "code"} 字段，
 * 确认返回的是 Spring 原生 404 而非框架异常处理器的输出。</p>
 *
 * <h3>测试策略</h3>
 * <p>通过 {@code @SpringBootTest(properties)} 设置 enabled=false，
 * 并使用 {@code @MockBean} 模拟 {@link DiffDataSourceRegistry} 以避免数据源初始化。
 * 分别验证 session 和 decision 两组端点均返回原生 404。</p>
 *
 * <h3>运行前提</h3>
 * <p>需要设置 {@code TENANT_DIFF_TEST_MYSQL_ENABLED=true}，并提供可访问的 MySQL
 * 实例（由 {@code application-mysql-e2e.yml} 配置）。</p>
 */
@SpringBootTest(properties = "tenant-diff.standalone.enabled=false")
@AutoConfigureMockMvc
@Tag("mysql-e2e")
@EnabledIfEnvironmentVariable(named = "TENANT_DIFF_TEST_MYSQL_ENABLED", matches = "(?i:true|1|yes)")
@ActiveProfiles({"test", "mysql-e2e"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MysqlStandaloneDisabledE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DiffDataSourceRegistry diffDataSourceRegistry;

    /**
     * 验证功能关闭时 Standalone 端点和 Decision 端点均不暴露。
     *
     * <p><b>测什么：</b></p>
     * <ul>
     *   <li>GET /api/tenantDiff/standalone/session/get → 原生 404（非框架错误）</li>
     *   <li>GET /api/tenant-diff/decision/list → 原生 404（非框架错误）</li>
     * </ul>
     *
     * <p><b>如何验证：</b>断言 HTTP 404，且响应体不包含 {@code "success"} 和 {@code "code"} 字段
     * （区分于框架异常处理器返回的标准错误格式）。</p>
     */
    @Test
    void standalone_endpoints_should_not_be_exposed_when_disabled() throws Exception {
        mockMvc.perform(get("/api/tenantDiff/standalone/session/get").param("sessionId", "0"))
            .andExpect(status().isNotFound())
            .andExpect(content().string(not(containsString("\"success\""))))
            .andExpect(content().string(not(containsString("\"code\""))));

        mockMvc.perform(get("/api/tenant-diff/decision/list")
                .param("sessionId", "1")
                .param("businessType", "EXAMPLE_PRODUCT")
                .param("businessKey", "PROD-003"))
            .andExpect(status().isNotFound())
            .andExpect(content().string(not(containsString("\"success\""))))
            .andExpect(content().string(not(containsString("\"code\""))));
    }
}
