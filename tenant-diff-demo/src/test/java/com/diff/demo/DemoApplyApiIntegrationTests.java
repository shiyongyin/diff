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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DemoApplyApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void previewShouldReturnStatisticsWithoutWriting() throws Exception {
        long sessionId = createSession();

        String previewPayload = """
            {
              "sessionId": %d,
              "direction": "A_TO_B",
              "options": {
                "allowDelete": false,
                "businessTypes": ["EXAMPLE_PRODUCT"],
                "diffTypes": ["INSERT", "UPDATE"]
              }
            }
            """.formatted(sessionId);

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(previewPayload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.sessionId").value(sessionId))
            .andExpect(jsonPath("$.data.direction").value("A_TO_B"))
            .andExpect(jsonPath("$.data.statistics.totalActions").value(greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.data.statistics.insertCount").value(greaterThanOrEqualTo(0)))
            .andExpect(jsonPath("$.data.statistics.updateCount").value(greaterThanOrEqualTo(0)))
            .andExpect(jsonPath("$.data.businessTypePreviews").isArray())
            .andExpect(jsonPath("$.data.businessTypePreviews[0].businessType").value("EXAMPLE_PRODUCT"));
    }

    @Test
    void executeApplyAndRollbackThroughApi() throws Exception {
        long sessionId = createSession();

        String applyPayload = """
            {
              "sessionId": %d,
              "direction": "A_TO_B",
              "options": {
                "mode": "EXECUTE",
                "allowDelete": false,
                "maxAffectedRows": 10,
                "businessTypes": ["EXAMPLE_PRODUCT"],
                "diffTypes": ["INSERT", "UPDATE"]
              }
            }
            """.formatted(sessionId);

        String applyResponse = mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(applyPayload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.applyResult.success").value(true))
            .andExpect(jsonPath("$.data.applyResult.affectedRows").value(greaterThanOrEqualTo(1)))
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode applyJson = objectMapper.readTree(applyResponse);
        long applyId = applyJson.path("data").path("applyId").asLong();

        String rollbackPayload = """
            {
              "applyId": %d
            }
            """.formatted(applyId);

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/rollback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rollbackPayload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.applyId").value(applyId))
            .andExpect(jsonPath("$.data.applyResult.success").value(true))
            .andExpect(jsonPath("$.data.applyResult.affectedRows").value(greaterThanOrEqualTo(1)));
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
