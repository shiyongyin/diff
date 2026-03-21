package com.diff.standalone.service.impl;

import com.diff.core.apply.PlanBuilder;
import com.diff.core.domain.apply.*;
import com.diff.core.domain.diff.*;
import com.diff.standalone.apply.StandaloneApplyExecutor;
import com.diff.standalone.persistence.entity.TenantDiffDecisionRecordPo;
import com.diff.standalone.persistence.entity.TenantDiffResultPo;
import com.diff.standalone.persistence.entity.TenantDiffSessionPo;
import com.diff.standalone.persistence.mapper.TenantDiffApplyRecordMapper;
import com.diff.standalone.persistence.mapper.TenantDiffResultMapper;
import com.diff.standalone.persistence.mapper.TenantDiffSessionMapper;
import com.diff.standalone.persistence.mapper.TenantDiffSnapshotMapper;
import com.diff.standalone.service.DecisionRecordService;
import com.diff.standalone.snapshot.StandaloneSnapshotBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 验证 Apply 构建计划时 Decision 过滤逻辑。
 */
@ExtendWith(MockitoExtension.class)
class ApplyDecisionFilterTest {

    @Mock private TenantDiffApplyRecordMapper applyRecordMapper;
    @Mock private TenantDiffSnapshotMapper snapshotMapper;
    @Mock private TenantDiffSessionMapper sessionMapper;
    @Mock private TenantDiffResultMapper resultMapper;
    @Mock private StandaloneSnapshotBuilder snapshotBuilder;
    @Mock private StandaloneApplyExecutor applyExecutor;
    @Mock private DecisionRecordService decisionRecordService;

    private TenantDiffStandaloneApplyServiceImpl applyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        PlanBuilder planBuilder = new PlanBuilder();
        applyService = new TenantDiffStandaloneApplyServiceImpl(
            applyRecordMapper, snapshotMapper, sessionMapper, resultMapper,
            snapshotBuilder, applyExecutor, planBuilder, objectMapper,
            5000, decisionRecordService
        );
    }

    private static RecordDiff record(String key, DiffType type) {
        return RecordDiff.builder()
            .recordBusinessKey(key)
            .diffType(type)
            .decision(DecisionType.ACCEPT)
            .sourceFields(Map.of("code", key))
            .targetFields(Map.of("code", key + "_old"))
            .build();
    }

    private static TableDiff table(String name, int level, List<RecordDiff> records) {
        return TableDiff.builder()
            .tableName(name)
            .dependencyLevel(level)
            .recordDiffs(records)
            .build();
    }

    private static BusinessDiff business(String type, String key, List<TableDiff> tables) {
        return BusinessDiff.builder()
            .businessType(type)
            .businessTable("main_table")
            .businessKey(key)
            .businessName("test")
            .diffType(DiffType.UPDATE)
            .tableDiffs(tables)
            .build();
    }

    @Test
    @DisplayName("有 SKIP 决策的记录在 buildPlan 时被过滤")
    void skippedRecordsFilteredFromPlan() throws Exception {
        TenantDiffSessionPo session = new TenantDiffSessionPo();
        session.setId(1L);
        session.setStatus(SessionStatus.SUCCESS.name());
        when(sessionMapper.selectById(1L)).thenReturn(session);

        BusinessDiff diff = business("ORDER", "ORD-001", List.of(
            table("main_table", 0, List.of(
                record("R1", DiffType.INSERT),
                record("R2", DiffType.INSERT),
                record("R3", DiffType.UPDATE)
            ))
        ));
        String diffJson = objectMapper.writeValueAsString(diff);
        TenantDiffResultPo resultPo = new TenantDiffResultPo();
        resultPo.setId(1L);
        resultPo.setSessionId(1L);
        resultPo.setBusinessType("ORDER");
        resultPo.setBusinessKey("ORD-001");
        resultPo.setDiffJson(diffJson);
        when(resultMapper.selectList(any())).thenReturn(List.of(resultPo));

        List<TenantDiffDecisionRecordPo> decisions = List.of(
            TenantDiffDecisionRecordPo.builder()
                .tableName("main_table").recordBusinessKey("R2")
                .decision("SKIP").build(),
            TenantDiffDecisionRecordPo.builder()
                .tableName("main_table").recordBusinessKey("R1")
                .decision("ACCEPT").build()
        );
        when(decisionRecordService.listDecisions(1L, "ORDER", "ORD-001")).thenReturn(decisions);

        ApplyOptions opts = ApplyOptions.builder()
            .mode(ApplyMode.DRY_RUN)
            .selectionMode(SelectionMode.ALL)
            .selectedActionIds(Collections.emptySet())
            .build();

        ApplyPlan plan = applyService.buildPlan(1L, ApplyDirection.A_TO_B, opts);

        assertNotNull(plan);
        assertNotNull(plan.getActions());

        boolean hasR2 = plan.getActions().stream()
            .anyMatch(a -> "R2".equals(a.getRecordBusinessKey()));
        assertFalse(hasR2, "R2 was SKIP, should not appear in plan");

        boolean hasR1 = plan.getActions().stream()
            .anyMatch(a -> "R1".equals(a.getRecordBusinessKey()));
        assertTrue(hasR1, "R1 was ACCEPT, should appear in plan");

        boolean hasR3 = plan.getActions().stream()
            .anyMatch(a -> "R3".equals(a.getRecordBusinessKey()));
        assertTrue(hasR3, "R3 had no decision, should appear in plan");
    }

    @Test
    @DisplayName("没有 Decision 记录时所有记录保留")
    void noDecisionsKeepsAll() throws Exception {
        TenantDiffSessionPo session = new TenantDiffSessionPo();
        session.setId(1L);
        session.setStatus(SessionStatus.SUCCESS.name());
        when(sessionMapper.selectById(1L)).thenReturn(session);

        BusinessDiff diff = business("ORDER", "ORD-001", List.of(
            table("main_table", 0, List.of(
                record("R1", DiffType.INSERT),
                record("R2", DiffType.INSERT)
            ))
        ));
        String diffJson = objectMapper.writeValueAsString(diff);
        TenantDiffResultPo resultPo = new TenantDiffResultPo();
        resultPo.setId(1L);
        resultPo.setSessionId(1L);
        resultPo.setBusinessType("ORDER");
        resultPo.setBusinessKey("ORD-001");
        resultPo.setDiffJson(diffJson);
        when(resultMapper.selectList(any())).thenReturn(List.of(resultPo));

        when(decisionRecordService.listDecisions(1L, "ORDER", "ORD-001"))
            .thenReturn(Collections.emptyList());

        ApplyOptions opts = ApplyOptions.builder()
            .mode(ApplyMode.DRY_RUN)
            .selectionMode(SelectionMode.ALL)
            .selectedActionIds(Collections.emptySet())
            .build();

        ApplyPlan plan = applyService.buildPlan(1L, ApplyDirection.A_TO_B, opts);

        assertEquals(2, plan.getActions().size());
    }

    @Test
    @DisplayName("DecisionRecordService 为 null 时不过滤")
    void nullServiceSkipsFilter() throws Exception {
        TenantDiffStandaloneApplyServiceImpl noDecisionService =
            new TenantDiffStandaloneApplyServiceImpl(
                applyRecordMapper, snapshotMapper, sessionMapper, resultMapper,
                snapshotBuilder, applyExecutor, new PlanBuilder(), objectMapper,
                5000, null
            );

        TenantDiffSessionPo session = new TenantDiffSessionPo();
        session.setId(1L);
        session.setStatus(SessionStatus.SUCCESS.name());
        when(sessionMapper.selectById(1L)).thenReturn(session);

        BusinessDiff diff = business("ORDER", "ORD-001", List.of(
            table("main_table", 0, List.of(
                record("R1", DiffType.INSERT)
            ))
        ));
        String diffJson = objectMapper.writeValueAsString(diff);
        TenantDiffResultPo resultPo = new TenantDiffResultPo();
        resultPo.setId(1L);
        resultPo.setSessionId(1L);
        resultPo.setBusinessType("ORDER");
        resultPo.setBusinessKey("ORD-001");
        resultPo.setDiffJson(diffJson);
        when(resultMapper.selectList(any())).thenReturn(List.of(resultPo));

        ApplyOptions opts = ApplyOptions.builder()
            .mode(ApplyMode.DRY_RUN)
            .selectionMode(SelectionMode.ALL)
            .selectedActionIds(Collections.emptySet())
            .build();

        ApplyPlan plan = noDecisionService.buildPlan(1L, ApplyDirection.A_TO_B, opts);

        assertEquals(1, plan.getActions().size());
        verifyNoInteractions(decisionRecordService);
    }

    @Test
    @DisplayName("过滤不修改原始 BusinessDiff")
    void filterDoesNotMutateOriginal() throws Exception {
        TenantDiffSessionPo session = new TenantDiffSessionPo();
        session.setId(1L);
        session.setStatus(SessionStatus.SUCCESS.name());
        when(sessionMapper.selectById(1L)).thenReturn(session);

        List<RecordDiff> originalRecords = List.of(
            record("R1", DiffType.INSERT),
            record("R2", DiffType.INSERT)
        );
        BusinessDiff diff = business("ORDER", "ORD-001", List.of(
            table("main_table", 0, originalRecords)
        ));
        String diffJson = objectMapper.writeValueAsString(diff);
        TenantDiffResultPo resultPo = new TenantDiffResultPo();
        resultPo.setId(1L);
        resultPo.setSessionId(1L);
        resultPo.setBusinessType("ORDER");
        resultPo.setBusinessKey("ORD-001");
        resultPo.setDiffJson(diffJson);
        when(resultMapper.selectList(any())).thenReturn(List.of(resultPo));

        List<TenantDiffDecisionRecordPo> decisions = List.of(
            TenantDiffDecisionRecordPo.builder()
                .tableName("main_table").recordBusinessKey("R2")
                .decision("SKIP").build()
        );
        when(decisionRecordService.listDecisions(1L, "ORDER", "ORD-001")).thenReturn(decisions);

        ApplyOptions opts = ApplyOptions.builder()
            .mode(ApplyMode.DRY_RUN)
            .selectionMode(SelectionMode.ALL)
            .selectedActionIds(Collections.emptySet())
            .build();

        applyService.buildPlan(1L, ApplyDirection.A_TO_B, opts);

        assertEquals(2, originalRecords.size(), "original records should not be mutated");
    }
}
