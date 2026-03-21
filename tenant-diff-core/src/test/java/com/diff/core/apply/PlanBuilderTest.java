package com.diff.core.apply;

import com.diff.core.domain.apply.*;
import com.diff.core.domain.diff.*;
import com.diff.core.domain.exception.ErrorCode;
import com.diff.core.domain.exception.TenantDiffException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlanBuilderTest {

    private final PlanBuilder builder = new PlanBuilder();

    // ---- helpers ----

    private static RecordDiff recordDiff(String key, DiffType type) {
        return RecordDiff.builder()
            .recordBusinessKey(key)
            .diffType(type)
            .decision(DecisionType.ACCEPT)
            .build();
    }

    private static TableDiff tableDiff(String tableName, int depLevel, List<RecordDiff> records) {
        return TableDiff.builder()
            .tableName(tableName)
            .dependencyLevel(depLevel)
            .recordDiffs(records)
            .build();
    }

    private static BusinessDiff businessDiff(String type, String key, List<TableDiff> tables) {
        return BusinessDiff.builder()
            .businessType(type)
            .businessKey(key)
            .tableDiffs(tables)
            .build();
    }

    // ---- tests ----

    @Test
    void insertActions_sortedByDependencyLevelAsc() {
        BusinessDiff diff = businessDiff("T", "BK1", List.of(
            tableDiff("child", 1, List.of(recordDiff("r1", DiffType.INSERT))),
            tableDiff("parent", 0, List.of(recordDiff("r2", DiffType.INSERT)))
        ));

        ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, null, List.of(diff));

        assertEquals(2, plan.getActions().size());
        assertEquals("parent", plan.getActions().get(0).getTableName());
        assertEquals("child", plan.getActions().get(1).getTableName());
    }

    @Test
    void deleteActions_sortedByDependencyLevelDesc() {
        ApplyOptions options = ApplyOptions.builder().allowDelete(true).build();
        BusinessDiff diff = businessDiff("T", "BK1", List.of(
            tableDiff("parent", 0, List.of(recordDiff("r1", DiffType.DELETE))),
            tableDiff("child", 1, List.of(recordDiff("r2", DiffType.DELETE)))
        ));

        ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff));

        assertEquals(2, plan.getActions().size());
        // DELETE: child (dep=1) before parent (dep=0)
        assertEquals("child", plan.getActions().get(0).getTableName());
        assertEquals("parent", plan.getActions().get(1).getTableName());
    }

    @Test
    void bToA_reversesInsertAndDelete() {
        ApplyOptions options = ApplyOptions.builder().allowDelete(true).build();
        BusinessDiff diff = businessDiff("T", "BK1", List.of(
            tableDiff("t1", 0, List.of(
                recordDiff("r1", DiffType.INSERT),
                recordDiff("r2", DiffType.DELETE)
            ))
        ));

        ApplyPlan plan = builder.build(1L, ApplyDirection.B_TO_A, options, List.of(diff));

        assertEquals(2, plan.getActions().size());
        // INSERT becomes DELETE, DELETE becomes INSERT in B_TO_A
        ApplyAction insertAction = plan.getActions().stream()
            .filter(a -> a.getDiffType() == DiffType.INSERT).findFirst().orElseThrow();
        assertEquals("r2", insertAction.getRecordBusinessKey());

        ApplyAction deleteAction = plan.getActions().stream()
            .filter(a -> a.getDiffType() == DiffType.DELETE).findFirst().orElseThrow();
        assertEquals("r1", deleteAction.getRecordBusinessKey());
    }

    @Test
    void maxAffectedRows_throwsWhenExceeded() {
        ApplyOptions options = ApplyOptions.builder()
            .mode(ApplyMode.EXECUTE)
            .maxAffectedRows(1)
            .build();
        BusinessDiff diff = businessDiff("T", "BK1", List.of(
            tableDiff("t1", 0, List.of(
                recordDiff("r1", DiffType.INSERT),
                recordDiff("r2", DiffType.UPDATE)
            ))
        ));

        TenantDiffException ex = assertThrows(TenantDiffException.class, () ->
            builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff))
        );
        assertEquals(ErrorCode.APPLY_THRESHOLD_EXCEEDED, ex.getErrorCode());
    }

    @Test
    void emptyDiffs_emptyPlan() {
        ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, null, Collections.emptyList());

        assertNotNull(plan);
        assertTrue(plan.getActions().isEmpty());
        assertEquals(0, plan.getStatistics().getTotalActions());
    }

    @Test
    void businessTypeWhitelist_filtersCorrectly() {
        ApplyOptions options = ApplyOptions.builder()
            .businessTypes(List.of("ALLOWED"))
            .build();

        BusinessDiff included = businessDiff("ALLOWED", "BK1", List.of(
            tableDiff("t1", 0, List.of(recordDiff("r1", DiffType.INSERT)))
        ));
        BusinessDiff excluded = businessDiff("BLOCKED", "BK2", List.of(
            tableDiff("t1", 0, List.of(recordDiff("r2", DiffType.INSERT)))
        ));

        ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, options, List.of(included, excluded));

        assertEquals(1, plan.getActions().size());
        assertEquals("ALLOWED", plan.getActions().get(0).getBusinessType());
    }

    // ================================================================
    // Phase 1.1 补全
    // ================================================================

    @Nested
    @DisplayName("过滤与筛选")
    class FilteringTests {

        @Test
        @DisplayName("NOOP 记录不产生 action")
        void noopRecords_excluded() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(
                    recordDiff("r1", DiffType.NOOP),
                    recordDiff("r2", DiffType.INSERT)
                ))
            ));

            ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, null, List.of(diff));
            assertEquals(1, plan.getActions().size());
            assertEquals("r2", plan.getActions().get(0).getRecordBusinessKey());
        }

        @Test
        @DisplayName("diffType 白名单过滤：只包含指定类型")
        void diffTypeWhitelist_filtersCorrectly() {
            ApplyOptions options = ApplyOptions.builder()
                .diffTypes(List.of(DiffType.INSERT))
                .allowDelete(true)
                .build();

            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(
                    recordDiff("r1", DiffType.INSERT),
                    recordDiff("r2", DiffType.UPDATE),
                    recordDiff("r3", DiffType.DELETE)
                ))
            ));

            ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff));
            assertEquals(1, plan.getActions().size());
            assertEquals(DiffType.INSERT, plan.getActions().get(0).getDiffType());
        }

        @Test
        @DisplayName("businessKey 白名单过滤")
        void businessKeyWhitelist_filtersCorrectly() {
            ApplyOptions options = ApplyOptions.builder()
                .businessKeys(List.of("BK1"))
                .build();

            BusinessDiff bk1 = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(recordDiff("r1", DiffType.INSERT)))
            ));
            BusinessDiff bk2 = businessDiff("T", "BK2", List.of(
                tableDiff("t1", 0, List.of(recordDiff("r2", DiffType.INSERT)))
            ));

            ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, options, List.of(bk1, bk2));
            assertEquals(1, plan.getActions().size());
            assertEquals("BK1", plan.getActions().get(0).getBusinessKey());
        }

        @Test
        @DisplayName("allowDelete=false 时 DELETE 记录被过滤")
        void allowDeleteFalse_deleteExcluded() {
            ApplyOptions options = ApplyOptions.builder().allowDelete(false).build();

            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(
                    recordDiff("r1", DiffType.INSERT),
                    recordDiff("r2", DiffType.DELETE)
                ))
            ));

            ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff));
            assertEquals(1, plan.getActions().size());
            assertEquals(DiffType.INSERT, plan.getActions().get(0).getDiffType());
        }
    }

    @Nested
    @DisplayName("边界输入")
    class BoundaryInputs {

        @Test
        @DisplayName("null diffs 列表 → 空 plan")
        void nullDiffs_emptyPlan() {
            ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, null, null);
            assertNotNull(plan);
            assertTrue(plan.getActions().isEmpty());
        }

        @Test
        @DisplayName("null sessionId → 抛异常")
        void nullSessionId_throws() {
            assertThrows(IllegalArgumentException.class,
                () -> builder.build(null, ApplyDirection.A_TO_B, null, List.of()));
        }

        @Test
        @DisplayName("null direction → 抛异常")
        void nullDirection_throws() {
            assertThrows(IllegalArgumentException.class,
                () -> builder.build(1L, null, null, List.of()));
        }

        @Test
        @DisplayName("null options → 使用默认选项，不抛异常")
        void nullOptions_usesDefaults() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(recordDiff("r1", DiffType.INSERT)))
            ));
            ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, null, List.of(diff));
            assertEquals(1, plan.getActions().size());
        }
    }

    @Nested
    @DisplayName("统计信息")
    class StatisticsTests {

        @Test
        @DisplayName("Plan 统计与 action 数量一致")
        void statistics_matchActions() {
            ApplyOptions options = ApplyOptions.builder().allowDelete(true).build();
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(
                    recordDiff("r1", DiffType.INSERT),
                    recordDiff("r2", DiffType.UPDATE),
                    recordDiff("r3", DiffType.DELETE)
                ))
            ));

            ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff));
            ApplyStatistics stats = plan.getStatistics();

            assertEquals(3, stats.getTotalActions());
            assertEquals(1, stats.getInsertCount());
            assertEquals(1, stats.getUpdateCount());
            assertEquals(1, stats.getDeleteCount());
            assertEquals(3, stats.getEstimatedAffectedRows());
        }
    }

    @Nested
    @DisplayName("选择机制")
    class SelectionTests {

        @Test
        void selectionMode_ALL_returnsAllActions() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(
                    recordDiff("r1", DiffType.INSERT),
                    recordDiff("r2", DiffType.UPDATE)
                ))
            ));

            ApplyOptions options = ApplyOptions.builder()
                .selectionMode(SelectionMode.ALL)
                .build();

            ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff));
            assertEquals(2, plan.getActions().size());
        }

        @Test
        void selectionMode_null_treatedAsALL() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(recordDiff("r1", DiffType.INSERT)))
            ));

            ApplyOptions options = ApplyOptions.builder()
                .selectionMode(null)
                .build();

            ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff));
            assertEquals(1, plan.getActions().size());
        }

        @Test
        void partial_emptySelectedIds_throwsSelectionEmpty() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(recordDiff("r1", DiffType.INSERT)))
            ));

            ApplyOptions options = ApplyOptions.builder()
                .selectionMode(SelectionMode.PARTIAL)
                .selectedActionIds(Set.of())
                .previewToken("pt_v1_dummy")
                .build();

            TenantDiffException ex = assertThrows(TenantDiffException.class,
                () -> builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff)));
            assertEquals(ErrorCode.SELECTION_EMPTY, ex.getErrorCode());
        }

        @Test
        void partial_missingPreviewToken_throwsParamInvalid() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(recordDiff("r1", DiffType.INSERT)))
            ));

            ApplyOptions options = ApplyOptions.builder()
                .selectionMode(SelectionMode.PARTIAL)
                .selectedActionIds(Set.of("v1:dummy"))
                .previewToken(null)
                .build();

            TenantDiffException ex = assertThrows(TenantDiffException.class,
                () -> builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff)));
            assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        }

        @Test
        void partial_tokenMismatch_throwsSelectionStale() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(recordDiff("r1", DiffType.INSERT)))
            ));

            ApplyPlan fullPlan = builder.build(1L, ApplyDirection.A_TO_B, ApplyOptions.builder().build(), List.of(diff));
            String correctToken = PlanBuilder.computePreviewToken(1L, ApplyDirection.A_TO_B, fullPlan.getActions());
            String wrongToken = correctToken + "_tampered";

            String actionId = fullPlan.getActions().get(0).getActionId();
            ApplyOptions options = ApplyOptions.builder()
                .selectionMode(SelectionMode.PARTIAL)
                .selectedActionIds(Set.of(actionId))
                .previewToken(wrongToken)
                .build();

            TenantDiffException ex = assertThrows(TenantDiffException.class,
                () -> builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff)));
            assertEquals(ErrorCode.SELECTION_STALE, ex.getErrorCode());
        }

        @Test
        void partial_unknownActionId_throwsSelectionInvalidId() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(recordDiff("r1", DiffType.INSERT)))
            ));

            ApplyPlan fullPlan = builder.build(1L, ApplyDirection.A_TO_B, ApplyOptions.builder().build(), List.of(diff));
            String token = PlanBuilder.computePreviewToken(1L, ApplyDirection.A_TO_B, fullPlan.getActions());

            ApplyOptions options = ApplyOptions.builder()
                .selectionMode(SelectionMode.PARTIAL)
                .selectedActionIds(Set.of("v1:UNKNOWN"))
                .previewToken(token)
                .build();

            TenantDiffException ex = assertThrows(TenantDiffException.class,
                () -> builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff)));
            assertEquals(ErrorCode.SELECTION_INVALID_ID, ex.getErrorCode());
        }

        @Test
        void partial_actionIdMissingV1Prefix_throwsSelectionInvalidId() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(recordDiff("r1", DiffType.INSERT)))
            ));

            ApplyOptions options = ApplyOptions.builder()
                .selectionMode(SelectionMode.PARTIAL)
                .selectedActionIds(Set.of("bad"))
                .previewToken("pt_v1_dummy")
                .build();

            TenantDiffException ex = assertThrows(TenantDiffException.class,
                () -> builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff)));
            assertEquals(ErrorCode.SELECTION_INVALID_ID, ex.getErrorCode());
        }

        @Test
        void partial_selectedIdsExceed5000_throwsParamInvalid() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(recordDiff("r1", DiffType.INSERT)))
            ));

            Set<String> ids = new HashSet<>();
            for (int i = 0; i < 5001; i++) {
                ids.add("v1:ID_" + i);
            }
            ApplyOptions options = ApplyOptions.builder()
                .selectionMode(SelectionMode.PARTIAL)
                .selectedActionIds(ids)
                .previewToken("pt_v1_dummy")
                .build();

            TenantDiffException ex = assertThrows(TenantDiffException.class,
                () -> builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff)));
            assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        }

        @Test
        void partial_subTableAction_throwsParamInvalid() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("child", 1, List.of(recordDiff("r1", DiffType.INSERT)))
            ));

            ApplyPlan fullPlan = builder.build(1L, ApplyDirection.A_TO_B, ApplyOptions.builder().build(), List.of(diff));
            String token = PlanBuilder.computePreviewToken(1L, ApplyDirection.A_TO_B, fullPlan.getActions());
            String actionId = fullPlan.getActions().get(0).getActionId();

            ApplyOptions options = ApplyOptions.builder()
                .selectionMode(SelectionMode.PARTIAL)
                .selectedActionIds(Set.of(actionId))
                .previewToken(token)
                .build();

            TenantDiffException ex = assertThrows(TenantDiffException.class,
                () -> builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff)));
            assertEquals(ErrorCode.PARAM_INVALID, ex.getErrorCode());
        }

        @Test
        void partial_validSelection_filtersCorrectly() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(
                    recordDiff("r1", DiffType.INSERT),
                    recordDiff("r2", DiffType.INSERT),
                    recordDiff("r3", DiffType.INSERT)
                ))
            ));

            ApplyPlan fullPlan = builder.build(1L, ApplyDirection.A_TO_B, ApplyOptions.builder().build(), List.of(diff));
            String token = PlanBuilder.computePreviewToken(1L, ApplyDirection.A_TO_B, fullPlan.getActions());

            String id1 = fullPlan.getActions().get(0).getActionId();
            String id2 = fullPlan.getActions().get(1).getActionId();

            ApplyOptions options = ApplyOptions.builder()
                .selectionMode(SelectionMode.PARTIAL)
                .selectedActionIds(Set.of(id1, id2))
                .previewToken(token)
                .build();

            ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff));
            assertEquals(2, plan.getActions().size());
            assertTrue(plan.getActions().stream().allMatch(a -> Set.of(id1, id2).contains(a.getActionId())));
        }

        @Test
        void partial_withBusinessKeysFilter_andSemantics() {
            BusinessDiff bk1 = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(
                    recordDiff("r1", DiffType.INSERT),
                    recordDiff("r2", DiffType.UPDATE)
                ))
            ));
            BusinessDiff bk2 = businessDiff("T", "BK2", List.of(
                tableDiff("t1", 0, List.of(recordDiff("r3", DiffType.INSERT)))
            ));

            ApplyOptions previewOptions = ApplyOptions.builder()
                .businessKeys(List.of("BK1"))
                .build();
            ApplyPlan fullPlan = builder.build(1L, ApplyDirection.A_TO_B, previewOptions, List.of(bk1, bk2));
            String token = PlanBuilder.computePreviewToken(1L, ApplyDirection.A_TO_B, fullPlan.getActions());
            String selectedId = fullPlan.getActions().get(0).getActionId();

            ApplyOptions options = ApplyOptions.builder()
                .businessKeys(List.of("BK1"))
                .selectionMode(SelectionMode.PARTIAL)
                .selectedActionIds(Set.of(selectedId))
                .previewToken(token)
                .build();

            ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, options, List.of(bk1, bk2));
            assertEquals(1, plan.getActions().size());
            assertEquals(selectedId, plan.getActions().get(0).getActionId());
        }

        @Test
        void partial_deleteWithAllowDeleteFalse_stillBlocked() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(recordDiff("r1", DiffType.DELETE)))
            ));

            String deleteActionId = ApplyAction.computeActionId("T", "BK1", "t1", "r1");
            String token = PlanBuilder.computePreviewToken(1L, ApplyDirection.A_TO_B, List.of());

            ApplyOptions options = ApplyOptions.builder()
                .allowDelete(false)
                .selectionMode(SelectionMode.PARTIAL)
                .selectedActionIds(Set.of(deleteActionId))
                .previewToken(token)
                .build();

            TenantDiffException ex = assertThrows(TenantDiffException.class,
                () -> builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff)));
            assertEquals(ErrorCode.SELECTION_INVALID_ID, ex.getErrorCode());
        }

        @Test
        void computeActionId_deterministic() {
            String id1 = ApplyAction.computeActionId("TYPE_A", "KEY_1", "t", "r");
            String id2 = ApplyAction.computeActionId("TYPE_A", "KEY_1", "t", "r");
            assertEquals(id1, id2);
        }

        @Test
        void computeActionId_escapesColonAndPercent() {
            String id = ApplyAction.computeActionId("a%b", "c:d", "t", "r");
            assertEquals("v1:a%25b:c%3Ad:t:r", id);
        }

        @Test
        void maxAffectedRows_dryRun_notBlocked() {
            ApplyOptions options = ApplyOptions.builder().maxAffectedRows(1).build(); // DRY_RUN by default
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(
                    recordDiff("r1", DiffType.INSERT),
                    recordDiff("r2", DiffType.UPDATE)
                ))
            ));

            ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff));
            assertEquals(2, plan.getActions().size());
        }

        @Test
        void maxAffectedRows_execute_throwsWhenExceeded() {
            ApplyOptions options = ApplyOptions.builder()
                .mode(ApplyMode.EXECUTE)
                .maxAffectedRows(1)
                .build();
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(
                    recordDiff("r1", DiffType.INSERT),
                    recordDiff("r2", DiffType.UPDATE)
                ))
            ));

            TenantDiffException ex = assertThrows(TenantDiffException.class,
                () -> builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff)));
            assertEquals(ErrorCode.APPLY_THRESHOLD_EXCEEDED, ex.getErrorCode());
        }

        @Test
        void computePreviewToken_deterministic() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(recordDiff("r1", DiffType.INSERT)))
            ));
            ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, ApplyOptions.builder().build(), List.of(diff));

            String token1 = PlanBuilder.computePreviewToken(1L, ApplyDirection.A_TO_B, plan.getActions());
            String token2 = PlanBuilder.computePreviewToken(1L, ApplyDirection.A_TO_B, plan.getActions());
            assertEquals(token1, token2);
            assertTrue(token1.startsWith("pt_v1_"));
            assertEquals(38, token1.length());
        }

        @Test
        void computePreviewToken_changesOnActionDelta() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(recordDiff("r1", DiffType.INSERT)))
            ));
            ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, ApplyOptions.builder().build(), List.of(diff));

            String token1 = PlanBuilder.computePreviewToken(1L, ApplyDirection.A_TO_B, plan.getActions());
            List<ApplyAction> modified = new ArrayList<>(plan.getActions());
            modified.add(ApplyAction.builder().actionId("v1:EXTRA:K:t:r").build());
            String token2 = PlanBuilder.computePreviewToken(1L, ApplyDirection.A_TO_B, modified);

            assertNotEquals(token1, token2);
        }

        @Test
        void maxAffectedRows_execute_checkedAfterPartialFilter() {
            BusinessDiff diff = businessDiff("T", "BK1", List.of(
                tableDiff("t1", 0, List.of(
                    recordDiff("r1", DiffType.INSERT),
                    recordDiff("r2", DiffType.UPDATE)
                ))
            ));

            ApplyPlan fullPlan = builder.build(1L, ApplyDirection.A_TO_B, ApplyOptions.builder().build(), List.of(diff));
            String token = PlanBuilder.computePreviewToken(1L, ApplyDirection.A_TO_B, fullPlan.getActions());
            String selectedId = fullPlan.getActions().get(0).getActionId();

            ApplyOptions options = ApplyOptions.builder()
                .mode(ApplyMode.EXECUTE)
                .maxAffectedRows(1)
                .selectionMode(SelectionMode.PARTIAL)
                .selectedActionIds(Set.of(selectedId))
                .previewToken(token)
                .build();

            ApplyPlan plan = builder.build(1L, ApplyDirection.A_TO_B, options, List.of(diff));
            assertEquals(1, plan.getActions().size());
            assertEquals(selectedId, plan.getActions().get(0).getActionId());
        }
    }
}
