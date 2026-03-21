package com.diff.core.domain.apply;

/**
 * Apply 执行模式。
 *
 * <p>
 * DRY_RUN 只生成计划和预估统计但不实际写库，用于执行前的风险评估和 UI 预览。
 * EXECUTE 真正执行数据库操作。分离两种模式使得"先预览再执行"的安全流程成为可能。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 */
public enum ApplyMode {
    /** 预览模式：只生成计划，不写库。 */
    DRY_RUN,
    /** 执行模式：实际执行数据库写操作。 */
    EXECUTE
}
