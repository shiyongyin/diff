package com.diff.standalone.web.dto.response;


import com.diff.core.domain.apply.ApplyAction;
import com.diff.core.domain.apply.ApplyDirection;
import com.diff.core.domain.apply.ApplyPlan;
import com.diff.core.domain.apply.ApplyStatistics;
import com.diff.core.domain.diff.DiffType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apply 预览响应：展示即将执行的影响范围，不实际写库。
 *
 * <p>前端可据此展示确认页："即将执行 N 条 INSERT、M 条 UPDATE、K 条 DELETE"。</p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyPreviewResponse {

    private Long sessionId;

    private ApplyDirection direction;

    private ApplyStatistics statistics;

    private List<BusinessTypePreview> businessTypePreviews;

    /** preview 一致性令牌，execute(PARTIAL) 时必须回传。 */
    private String previewToken;

    /** action 级明细列表，前端据此展示勾选 UI。 */
    private List<ActionPreviewItem> actions;

    /**
     * 按 businessType 分组的操作统计（INSERT/UPDATE/DELETE 数量）。
     *
     * @author tenant-diff
     * @since 2026-01-20
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BusinessTypePreview {
        private String businessType;
        private int insertCount;
        private int updateCount;
        private int deleteCount;
        private int totalActions;
    }

    /**
     * Action 级预览条目（面向前端展示，隐藏 domain 层 payload）。
     *
     * <p>用于构建可渲染的审查行，每条记录只暴露必要的 key/级联信息，避免前端依赖 engine 细节。</p>
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionPreviewItem {
        private String actionId;
        private String businessType;
        private String businessKey;
        private String tableName;
        private String recordBusinessKey;
        private DiffType diffType;
        private Integer dependencyLevel;
    }

    /**
     * 从 ApplyPlan 构建预览响应。
     *
     * @param plan Apply 计划，可为 null
     * @param previewToken 一致性令牌
     * @return 预览响应，plan 为 null 时返回空结构
     */
    public static ApplyPreviewResponse from(ApplyPlan plan, String previewToken) {
        if (plan == null) {
            return ApplyPreviewResponse.builder()
                .previewToken(previewToken)
                .businessTypePreviews(Collections.emptyList())
                .actions(Collections.emptyList())
                .build();
        }

        Map<String, int[]> groupCounts = new LinkedHashMap<>();
        if (plan.getActions() != null) {
            for (ApplyAction action : plan.getActions()) {
                if (action == null || action.getBusinessType() == null) {
                    continue;
                }
                int[] counts = groupCounts.computeIfAbsent(action.getBusinessType(), k -> new int[3]);
                if (action.getDiffType() == DiffType.INSERT) {
                    counts[0]++;
                } else if (action.getDiffType() == DiffType.UPDATE) {
                    counts[1]++;
                } else if (action.getDiffType() == DiffType.DELETE) {
                    counts[2]++;
                }
            }
        }

        List<BusinessTypePreview> previews = new ArrayList<>(groupCounts.size());
        for (Map.Entry<String, int[]> entry : groupCounts.entrySet()) {
            int[] c = entry.getValue();
            previews.add(BusinessTypePreview.builder()
                .businessType(entry.getKey())
                .insertCount(c[0])
                .updateCount(c[1])
                .deleteCount(c[2])
                .totalActions(c[0] + c[1] + c[2])
                .build());
        }

        List<ActionPreviewItem> actionItems = new ArrayList<>();
        if (plan.getActions() != null) {
            for (ApplyAction action : plan.getActions()) {
                if (action == null) {
                    continue;
                }
                actionItems.add(ActionPreviewItem.builder()
                    .actionId(action.getActionId())
                    .businessType(action.getBusinessType())
                    .businessKey(action.getBusinessKey())
                    .tableName(action.getTableName())
                    .recordBusinessKey(action.getRecordBusinessKey())
                    .diffType(action.getDiffType())
                    .dependencyLevel(action.getDependencyLevel())
                    .build());
            }
        }

        return ApplyPreviewResponse.builder()
            .sessionId(plan.getSessionId())
            .direction(plan.getDirection())
            .statistics(plan.getStatistics())
            .businessTypePreviews(previews)
            .previewToken(previewToken)
            .actions(actionItems)
            .build();
    }
}
