package com.diff.core.domain.apply;


import com.diff.core.apply.IdMapping;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * Apply 执行结果——反映一次 Apply 的成败、影响范围与错误明细。
 *
 * <p>
 * 即使部分动作失败（非 fatal），Apply 仍可能标记为 success，
 * 此时 {@link #actionErrors} 中包含非致命告警。调用方应同时检查
 * {@link #success} 和 {@link #actionErrors} 以获取完整执行情况。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see ApplyPlan
 * @see ApplyActionError
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyResult {
    /** 整体是否成功。 */
    private boolean success;

    /** 执行结果摘要（如 EXECUTE / EXECUTE_WITH_WARNINGS / DRY_RUN）。 */
    private String message;

    /**
     * EXECUTE 模式下的实际影响行数。
     *
     * <p>v1 以"成功执行的动作数"近似，非精确 SQL affected rows。</p>
     */
    @Builder.Default
    private Integer affectedRows = 0;

    /**
     * DRY_RUN 模式下的预估影响行数。
     *
     * <p>v1 以 {@code actions.size()} 近似。</p>
     */
    @Builder.Default
    private Integer estimatedAffectedRows = 0;

    /** 全局错误信息列表。 */
    @Builder.Default
    private List<String> errors = Collections.emptyList();

    /**
     * 动作级错误/告警明细。
     *
     * <p>{@link ApplyActionError#isFatal()} 为 false 表示仅告警，不影响整体成功判定。</p>
     */
    @Builder.Default
    private List<ApplyActionError> actionErrors = Collections.emptyList();

    /** INSERT 动作产生的 businessKey → 新 id 映射（用于审计与外键追踪）。 */
    private IdMapping idMapping;
}
