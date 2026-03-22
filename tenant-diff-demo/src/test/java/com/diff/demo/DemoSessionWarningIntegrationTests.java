package com.diff.demo;

import com.diff.demo.plugin.ExampleProductPlugin;
import com.diff.standalone.datasource.DiffDataSourceRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DemoSessionWarningIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createSession_whenPluginPartiallyFails_exposesStructuredWarnings() throws Exception {
        long sessionId = createSession();

        mockMvc.perform(get("/api/tenantDiff/standalone/session/get").param("sessionId", String.valueOf(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.sessionId").value(sessionId))
            .andExpect(jsonPath("$.data.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.warningCount").value(1))
            .andExpect(jsonPath("$.data.warnings[0].side").value("source"))
            .andExpect(jsonPath("$.data.warnings[0].businessType").value("EXAMPLE_PRODUCT"))
            .andExpect(jsonPath("$.data.warnings[0].businessKey").value("PROD-003"))
            .andExpect(jsonPath("$.data.warnings[0].message", containsString("mock load failure for PROD-003")));
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
        return json.path("data").path("sessionId").asLong();
    }

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
                    throw new IllegalStateException("mock load failure for PROD-003");
                }
                return delegate.queryForList(sql, args);
            }
        }
    }
}
