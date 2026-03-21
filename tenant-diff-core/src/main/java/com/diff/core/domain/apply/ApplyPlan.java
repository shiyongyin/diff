package com.diff.core.domain.apply;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * Apply 执行计划——从 diff 差异到数据库操作的中间产物。
 *
 * <p>
 * ApplyPlan 由 {@link com.diff.core.apply.PlanBuilder} 根据 diff 结果生成，
 * 包含排序后的 {@link ApplyAction} 列表。执行器按列表顺序逐一执行 INSERT/UPDATE/DELETE，
 * 保证外键依赖正确。计划与执行分离的设计使 DRY_RUN（预览）成为可能。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see com.diff.core.apply.PlanBuilder
 * @see ApplyAction
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyPlan {
    /** 计划唯一标识（UUID）。 */
    private String planId;

    /** 关联的 diff 会话 ID。 */
    @NotNull(message = "sessionId 不能为空")
    private Long sessionId;

    /** 应用方向（A_TO_B / B_TO_A）。 */
    @NotNull(message = "direction 不能为空")
    private ApplyDirection direction;

    /** 构建计划时使用的选项。 */
    private ApplyOptions options;

    /** 排序后的执行动作列表（按依赖层级与 key 排序）。 */
    @Builder.Default
    private List<ApplyAction> actions = Collections.emptyList();

    /** 计划统计信息（动作数、预估影响行数等）。 */
    private ApplyStatistics statistics;
}
