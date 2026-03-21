package com.diff.standalone.apply;


import com.diff.core.domain.apply.ApplyMode;
import com.diff.core.domain.apply.ApplyPlan;
import com.diff.core.domain.apply.ApplyResult;
import com.diff.core.domain.diff.BusinessDiff;

import java.util.List;

/**
 * 基于内存中的 {@link BusinessDiff} 明细执行 Apply 计划（不依赖结果表）。
 *
 * <p>
 * 与 {@link StandaloneApplyExecutor} 的区别：
 * <ul>
 *     <li>{@link StandaloneApplyExecutor}：从结果表加载 diffJson（依赖持久化）</li>
 *     <li>本接口：直接使用调用方传入的 diffs（适合回滚等场景）</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>典型场景</b>：回滚时 diff 由"快照 vs 当前"即时生成，无需持久化到结果表，
 * 调用方直接传入 diffs 即可执行 Apply。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public interface StandaloneBusinessDiffApplyExecutor {
    /**
     * 执行 Apply 计划（使用传入的 diff 明细）。
     *
     * @param targetTenantId 需要写入/变更的目标 tenant ID
     * @param plan 待执行的 Apply 计划
     * @param diffs 业务级 diff 明细（需覆盖 plan 中 action 涉及的业务范围）
     * @param mode 执行模式（DRY_RUN/EXECUTE）
     * @return 执行结果，含 affectedRows、idMapping、errors 等
     */
    ApplyResult execute(Long targetTenantId, ApplyPlan plan, List<BusinessDiff> diffs, ApplyMode mode);
}

