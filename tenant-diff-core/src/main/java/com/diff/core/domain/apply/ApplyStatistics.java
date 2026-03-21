package com.diff.core.domain.apply;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Apply 计划统计——用于执行前的影响评估与 UI 预览展示。
 *
 * <p>
 * 统计信息在 {@link com.diff.core.apply.PlanBuilder} 生成计划时同步计算，
 * 为调用方提供"这次 Apply 将影响多少行"的概览，
 * 便于在执行前做人工确认或自动化校验。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see ApplyPlan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyStatistics {
    /** 总动作数。 */
    @Builder.Default
    private Integer totalActions = 0;

    /** 预估影响行数（v1 等于 totalActions）。 */
    @Builder.Default
    private Integer estimatedAffectedRows = 0;

    @Builder.Default
    private Integer insertCount = 0;

    @Builder.Default
    private Integer updateCount = 0;

    @Builder.Default
    private Integer deleteCount = 0;
}
