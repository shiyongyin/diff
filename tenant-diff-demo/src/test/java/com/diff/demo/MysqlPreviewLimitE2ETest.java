package com.diff.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 预览 action 数量上限端到端测试：验证配置化的预览容量保护机制。
 *
 * <h3>测试目标</h3>
 * <p>当预览结果中的 action 数量超过配置上限（{@code preview-action-limit}）时，
 * 接口应拒绝生成预览并返回明确的错误码。</p>
 *
 * <h3>为什么需要这个限制</h3>
 * <p>预览会将所有待同步的 action 一次性构建到内存中。如果差异量巨大（如全量初始化场景），
 * 可能导致 OOM 或响应超时。通过配置上限，框架可以在构建阶段提前拦截，
 * 引导用户缩小比对范围或分批处理。</p>
 *
 * <h3>测试策略</h3>
 * <p>通过 {@code @SpringBootTest(properties)} 将上限设为 1，
 * 而种子数据包含 2 个 action（PROD-002 UPDATE + PROD-003 INSERT），
 * 从而触发上限校验。</p>
 *
 * <h3>运行前提</h3>
 * <p>需要运行中的 MySQL 实例（由 {@code application-mysql-e2e.yml} 配置）。</p>
 */
@SpringBootTest(properties = "tenant-diff.apply.preview-action-limit=1")
@AutoConfigureMockMvc
@ActiveProfiles({"test", "mysql-e2e"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MysqlPreviewLimitE2ETest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 验证 action 数量超过上限时预览被拒绝。
     *
     * <p><b>测什么：</b>preview-action-limit=1，但比对结果有 2 个 action，
     * 预览接口应返回 422 + DIFF_E_2014（PREVIEW_TOO_LARGE）。</p>
     *
     * <p><b>如何验证：</b>创建 EXAMPLE_PRODUCT session → 调用 preview →
     * 断言 HTTP 422 和错误码 DIFF_E_2014。</p>
     */
    @Test
    void preview_should_return_preview_too_large_when_action_limit_exceeded() throws Exception {
        String sessionResponse = mockMvc.perform(post("/api/tenantDiff/standalone/session/create")
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
            .andReturn()
            .getResponse()
            .getContentAsString();

        long sessionId = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(sessionResponse)
            .path("data")
            .path("sessionId")
            .asLong();

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/preview")
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
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_2014"));
    }
}
