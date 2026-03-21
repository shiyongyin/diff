package com.diff.core.domain.apply;

/**
 * Apply 方向——决定"把哪一侧的数据应用到哪一侧"。
 *
 * <p>
 * A_TO_B 表示将 A 侧（源）的数据同步到 B 侧（目标），
 * 此时 diff 结果中的 INSERT 直接执行；B_TO_A 则反向，
 * {@link com.diff.core.apply.PlanBuilder} 会将 INSERT ↔ DELETE 互换。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 */
public enum ApplyDirection {
    /** A（源）→ B（目标）。 */
    A_TO_B,
    /** B（目标）→ A（源），INSERT/DELETE 语义反转。 */
    B_TO_A
}
