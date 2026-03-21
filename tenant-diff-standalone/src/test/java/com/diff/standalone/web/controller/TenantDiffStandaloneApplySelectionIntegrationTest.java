package com.diff.standalone.web.controller;

import com.diff.core.domain.apply.ApplyDirection;
import com.diff.core.domain.apply.ApplyMode;
import com.diff.core.domain.apply.ApplyOptions;
import com.diff.core.domain.apply.ApplyPlan;
import com.diff.core.domain.apply.ApplyResult;
import com.diff.core.domain.apply.SelectionMode;
import com.diff.core.domain.diff.BusinessDiff;
import com.diff.core.domain.diff.DecisionType;
import com.diff.core.domain.diff.DiffType;
import com.diff.core.domain.diff.RecordDiff;
import com.diff.core.domain.diff.SessionStatus;
import com.diff.core.domain.diff.TableDiff;
import com.diff.standalone.apply.StandaloneApplyExecutor;
import com.diff.standalone.config.TenantDiffSchemaInitConfiguration;
import com.diff.standalone.config.TenantDiffStandaloneConfiguration;
import com.diff.standalone.datasource.DiffDataSourceAutoConfiguration;
import com.diff.standalone.persistence.entity.TenantDiffResultPo;
import com.diff.standalone.persistence.entity.TenantDiffSessionPo;
import com.diff.standalone.persistence.entity.TenantDiffSnapshotPo;
import com.diff.standalone.persistence.mapper.TenantDiffResultMapper;
import com.diff.standalone.persistence.mapper.TenantDiffSessionMapper;
import com.diff.standalone.snapshot.StandaloneSnapshotBuilder;
import com.diff.standalone.web.dto.request.ApplyExecuteRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = TenantDiffStandaloneApplySelectionIntegrationTest.TestApp.class,
    properties = {
        "tenant-diff.standalone.enabled=true",
        "tenant-diff.standalone.schema.init-mode=always",
        "tenant-diff.apply.preview-action-limit=5000",
        "spring.datasource.url=jdbc:h2:mem:tenant_diff_sel;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
    }
)
@AutoConfigureMockMvc
class TenantDiffStandaloneApplySelectionIntegrationTest {

    @SpringBootApplication
    @ImportAutoConfiguration({
        DiffDataSourceAutoConfiguration.class,
        TenantDiffSchemaInitConfiguration.class,
        TenantDiffStandaloneConfiguration.class
    })
    static class TestApp {

        @Bean
        @Primary
        public RecordingApplyExecutor testStandaloneApplyExecutor() {
            return new RecordingApplyExecutor();
        }

        @Bean
        @Primary
        public StandaloneSnapshotBuilder testStandaloneSnapshotBuilder(
            com.diff.standalone.model.StandaloneTenantModelBuilder modelBuilder,
            ObjectMapper objectMapper
        ) {
            return new NoopSnapshotBuilder(modelBuilder, objectMapper);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantDiffSessionMapper sessionMapper;

    @Autowired
    private TenantDiffResultMapper resultMapper;

    @Autowired
    private RecordingApplyExecutor recordingExecutor;

    @Autowired
    private com.diff.standalone.persistence.mapper.TenantDiffApplyRecordMapper applyRecordMapper;

    @Autowired
    private com.diff.standalone.persistence.mapper.TenantDiffSnapshotMapper snapshotMapper;

    @Autowired
    private StandaloneSnapshotBuilder snapshotBuilderBean;

    @Autowired
    private com.diff.core.apply.PlanBuilder planBuilder;

    @BeforeEach
    void resetRecordingExecutor() {
        recordingExecutor.reset();
    }

    @Test
    void preview_returnsActionsAndPreviewToken() throws Exception {
        long sessionId = seedSessionWithDiffs(3, 0);

        ApplyExecuteRequest request = ApplyExecuteRequest.builder()
            .sessionId(sessionId)
            .direction(ApplyDirection.A_TO_B)
            .options(ApplyOptions.builder().build())
            .build();

        MvcResult previewResult = mockMvc.perform(post("/api/tenantDiff/standalone/apply/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.previewToken").isNotEmpty())
            .andExpect(jsonPath("$.data.actions").isArray())
            .andReturn();

        JsonNode root = objectMapper.readTree(previewResult.getResponse().getContentAsString());
        String token = root.path("data").path("previewToken").asText();
        assertTrue(token.startsWith("pt_v1_"));

        ArrayNode actions = (ArrayNode) root.path("data").path("actions");
        assertEquals(3, actions.size());
        for (JsonNode action : actions) {
            String id = action.path("actionId").asText();
            assertFalse(id.isBlank());
            assertTrue(id.startsWith("v1:"));
        }
    }

    @Test
    void preview_then_executePartial_filtersActionsPassedToExecutor() throws Exception {
        long sessionId = seedSessionWithDiffs(3, 0);
        PreviewData preview = preview(sessionId);

        String id1 = preview.actionIds.get(0);
        String id2 = preview.actionIds.get(1);

        ApplyExecuteRequest request = ApplyExecuteRequest.builder()
            .sessionId(sessionId)
            .direction(ApplyDirection.A_TO_B)
            .options(ApplyOptions.builder()
                .selectionMode(SelectionMode.PARTIAL)
                .previewToken(preview.previewToken)
                .selectedActionIds(Set.of(id1, id2))
                .build())
            .build();

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        assertEquals(1, recordingExecutor.getCallCount());
        assertEquals(ApplyMode.EXECUTE, recordingExecutor.getLastMode());

        ApplyPlan plan = recordingExecutor.getLastPlan();
        assertNotNull(plan.getOptions());
        assertEquals(ApplyMode.EXECUTE, plan.getOptions().getMode());
        assertEquals(SelectionMode.PARTIAL, plan.getOptions().getSelectionMode());

        assertEquals(2, plan.getActions().size());
        assertTrue(plan.getActions().stream().allMatch(a -> Set.of(id1, id2).contains(a.getActionId())));
    }

    @Test
    void execute_partial_tamperedPreviewToken_returnsSelectionStale() throws Exception {
        long sessionId = seedSessionWithDiffs(2, 0);
        PreviewData preview = preview(sessionId);

        ApplyExecuteRequest request = ApplyExecuteRequest.builder()
            .sessionId(sessionId)
            .direction(ApplyDirection.A_TO_B)
            .options(ApplyOptions.builder()
                .selectionMode(SelectionMode.PARTIAL)
                .previewToken(preview.previewToken + "_tampered")
                .selectedActionIds(Set.of(preview.actionIds.get(0)))
                .build())
            .build();

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_2012"));

        assertEquals(0, recordingExecutor.getCallCount());
    }

    @Test
    void execute_partial_unknownActionId_returnsSelectionInvalidId() throws Exception {
        long sessionId = seedSessionWithDiffs(1, 0);
        PreviewData preview = preview(sessionId);

        ApplyExecuteRequest request = ApplyExecuteRequest.builder()
            .sessionId(sessionId)
            .direction(ApplyDirection.A_TO_B)
            .options(ApplyOptions.builder()
                .selectionMode(SelectionMode.PARTIAL)
                .previewToken(preview.previewToken)
                .selectedActionIds(Set.of("v1:UNKNOWN"))
                .build())
            .build();

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_2011"));

        assertEquals(0, recordingExecutor.getCallCount());
    }

    @Test
    void execute_partial_emptySelectedActionIds_returnsSelectionEmpty() throws Exception {
        long sessionId = seedSessionWithDiffs(1, 0);
        PreviewData preview = preview(sessionId);

        ApplyExecuteRequest request = ApplyExecuteRequest.builder()
            .sessionId(sessionId)
            .direction(ApplyDirection.A_TO_B)
            .options(ApplyOptions.builder()
                .selectionMode(SelectionMode.PARTIAL)
                .previewToken(preview.previewToken)
                .selectedActionIds(Set.of())
                .build())
            .build();

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_2010"));

        assertEquals(0, recordingExecutor.getCallCount());
    }

    @Test
    void execute_partial_selectSubTableAction_returnsParamInvalid() throws Exception {
        long sessionId = seedSessionWithDiffs(1, 1);
        PreviewData preview = preview(sessionId);

        ApplyExecuteRequest request = ApplyExecuteRequest.builder()
            .sessionId(sessionId)
            .direction(ApplyDirection.A_TO_B)
            .options(ApplyOptions.builder()
                .selectionMode(SelectionMode.PARTIAL)
                .previewToken(preview.previewToken)
                .selectedActionIds(Set.of(preview.actionIds.get(0)))
                .build())
            .build();

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_0001"));

        assertEquals(0, recordingExecutor.getCallCount());
    }

    @Test
    void execute_withoutSelectionMode_executesAllActions() throws Exception {
        long sessionId = seedSessionWithDiffs(2, 0);

        ApplyExecuteRequest request = ApplyExecuteRequest.builder()
            .sessionId(sessionId)
            .direction(ApplyDirection.A_TO_B)
            .options(null) // selectionMode not provided
            .build();

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        assertEquals(1, recordingExecutor.getCallCount());
        assertEquals(ApplyMode.EXECUTE, recordingExecutor.getLastMode());
        assertNotNull(recordingExecutor.getLastPlan());
        assertEquals(2, recordingExecutor.getLastPlan().getActions().size());
    }

    @Test
    void execute_clientModeDryRun_mustNotBypassMaxAffectedRows() throws Exception {
        long sessionId = seedSessionWithDiffs(2, 0);

        ApplyExecuteRequest request = ApplyExecuteRequest.builder()
            .sessionId(sessionId)
            .direction(ApplyDirection.A_TO_B)
            .options(ApplyOptions.builder()
                .mode(ApplyMode.DRY_RUN) // should be ignored on execute
                .maxAffectedRows(1)      // actions=2 -> threshold exceeded
                .build())
            .build();

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/execute")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("DIFF_E_2001"));

        assertEquals(0, recordingExecutor.getCallCount());
    }

    /**
     * PREVIEW_TOO_LARGE：preview action 数超过 previewActionLimit 时返回 DIFF_E_2014。
     *
     * 利用 Service 层直接调用绕过 limit=5000 的配置限制，
     * 通过反射/构造注入一个 limit=2 的 Service 实例来验证。
     */
    @Test
    void preview_tooManyActions_returnsPreviewTooLarge() throws Exception {
        long sessionId = seedSessionWithDiffs(3, 0);

        // 通过 HTTP 端点验证：当前 limit=5000，3 条数据不触发
        ApplyExecuteRequest request = ApplyExecuteRequest.builder()
            .sessionId(sessionId)
            .direction(ApplyDirection.A_TO_B)
            .options(ApplyOptions.builder().build())
            .build();

        mockMvc.perform(post("/api/tenantDiff/standalone/apply/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.actions.length()").value(3));

        // 构造 limit=2 的 Service 直接调用，验证 PREVIEW_TOO_LARGE 错径
        com.diff.core.domain.exception.TenantDiffException ex = assertThrows(
            com.diff.core.domain.exception.TenantDiffException.class,
            () -> previewWithLimit(sessionId, 2)
        );
        assertEquals("DIFF_E_2014", ex.getErrorCode().getCode());
    }

    private PreviewData preview(long sessionId) throws Exception {
        ApplyExecuteRequest request = ApplyExecuteRequest.builder()
            .sessionId(sessionId)
            .direction(ApplyDirection.A_TO_B)
            .options(ApplyOptions.builder().build())
            .build();

        String response = mockMvc.perform(post("/api/tenantDiff/standalone/apply/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode root = objectMapper.readTree(response);
        String token = root.path("data").path("previewToken").asText();
        ArrayNode actions = (ArrayNode) root.path("data").path("actions");

        List<String> ids = new ArrayList<>();
        for (JsonNode action : actions) {
            ids.add(action.path("actionId").asText());
        }
        return new PreviewData(token, ids);
    }

    /**
     * 以自定义 previewActionLimit 构造临时 Service 实例并调用 preview。
     * 用于验证 PREVIEW_TOO_LARGE 而无需修改 Spring 容器配置。
     */
    private void previewWithLimit(long sessionId, int limit) {
        com.diff.standalone.service.impl.TenantDiffStandaloneApplyServiceImpl svc =
            new com.diff.standalone.service.impl.TenantDiffStandaloneApplyServiceImpl(
                applyRecordMapper, snapshotMapper, sessionMapper, resultMapper,
                snapshotBuilderBean, recordingExecutor, planBuilder, objectMapper, limit, null
            );
        svc.preview(sessionId, ApplyDirection.A_TO_B, ApplyOptions.builder().build());
    }

    private long seedSessionWithDiffs(int actionCount, int dependencyLevel) throws Exception {
        TenantDiffSessionPo session = TenantDiffSessionPo.builder()
            .sessionKey("S-" + System.nanoTime())
            .sourceTenantId(1L)
            .targetTenantId(2L)
            .status(SessionStatus.SUCCESS.name())
            .version(0)
            .createdAt(LocalDateTime.now())
            .build();
        sessionMapper.insert(session);

        List<RecordDiff> records = new ArrayList<>();
        for (int i = 1; i <= actionCount; i++) {
            records.add(RecordDiff.builder()
                .recordBusinessKey("R-" + i)
                .diffType(DiffType.INSERT)
                .decision(DecisionType.ACCEPT)
                .build());
        }
        BusinessDiff diff = BusinessDiff.builder()
            .businessType("TYPE_A")
            .businessKey("BK_1")
            .tableDiffs(List.of(TableDiff.builder()
                .tableName("t_main")
                .dependencyLevel(dependencyLevel)
                .recordDiffs(records)
                .build()))
            .build();

        resultMapper.insert(TenantDiffResultPo.builder()
            .sessionId(session.getId())
            .businessType("TYPE_A")
            .businessKey("BK_1")
            .diffJson(objectMapper.writeValueAsString(diff))
            .createdAt(LocalDateTime.now())
            .build());

        return session.getId();
    }

    private static final class PreviewData {
        private final String previewToken;
        private final List<String> actionIds;

        private PreviewData(String previewToken, List<String> actionIds) {
            this.previewToken = previewToken;
            this.actionIds = actionIds;
        }
    }

    /**
     * No-op snapshot builder for integration tests: avoid requiring plugin/model build.
     */
    private static final class NoopSnapshotBuilder extends StandaloneSnapshotBuilder {
        private NoopSnapshotBuilder(com.diff.standalone.model.StandaloneTenantModelBuilder modelBuilder,
                                    ObjectMapper objectMapper) {
            super(modelBuilder, objectMapper);
        }

        @Override
        public List<TenantDiffSnapshotPo> buildTargetSnapshots(Long applyId, TenantDiffSessionPo session, ApplyPlan plan) {
            return List.of();
        }
    }

    /**
     * Recording executor for integration tests: captures the plan after selection filtering.
     */
    public static final class RecordingApplyExecutor implements StandaloneApplyExecutor {
        private volatile int callCount = 0;
        private volatile ApplyPlan lastPlan;
        private volatile ApplyMode lastMode;

        @Override
        public ApplyResult execute(ApplyPlan plan, ApplyMode mode) {
            callCount++;
            lastPlan = plan;
            lastMode = mode;
            return ApplyResult.builder().success(true).affectedRows(0).build();
        }

        public int getCallCount() {
            return callCount;
        }

        public void reset() {
            callCount = 0;
            lastPlan = null;
            lastMode = null;
        }

        public ApplyPlan getLastPlan() {
            return lastPlan;
        }

        public ApplyMode getLastMode() {
            return lastMode;
        }
    }
}
