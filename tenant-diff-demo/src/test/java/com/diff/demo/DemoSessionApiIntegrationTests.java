package com.diff.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DemoSessionApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void healthCheckReturnsStructuredSessionNotFound() throws Exception {
        mockMvc.perform(get("/api/tenantDiff/standalone/session/get").param("sessionId", "0"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_1001"))
            .andExpect(jsonPath("$.message").value("会话不存在"));
    }

    @Test
    void createSessionAndQueryBusinessResults() throws Exception {
        long sessionId = createSession();

        mockMvc.perform(get("/api/tenantDiff/standalone/session/get").param("sessionId", String.valueOf(sessionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.sessionId").value(sessionId))
            .andExpect(jsonPath("$.data.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.statistics.totalBusinesses").value(greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/api/tenantDiff/standalone/session/listBusiness")
                .param("sessionId", String.valueOf(sessionId))
                .param("pageNo", "1")
                .param("pageSize", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.total").value(greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.data.items[*].businessKey", hasItem("PROD-002")))
            .andExpect(jsonPath("$.data.items[*].businessKey", hasItem("PROD-003")));

        mockMvc.perform(get("/api/tenantDiff/standalone/session/getBusinessDetail")
                .param("sessionId", String.valueOf(sessionId))
                .param("businessType", "EXAMPLE_PRODUCT")
                .param("businessKey", "PROD-002"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.businessKey").value("PROD-002"))
            .andExpect(jsonPath("$.data.tableDiffs[0].recordDiffs[0].diffType").value("UPDATE"));
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
