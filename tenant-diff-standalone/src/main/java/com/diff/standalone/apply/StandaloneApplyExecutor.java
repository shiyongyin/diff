package com.diff.standalone.apply;


import com.diff.core.domain.apply.ApplyMode;
import com.diff.core.domain.apply.ApplyPlan;
import com.diff.core.domain.apply.ApplyResult;

/**
 * Standalone Apply 执行器（无 DAP 依赖）。
 *
 * <p>
 * 该接口关注“如何把 {@link ApplyPlan} 落地为数据库操作”，并返回执行结果（含 idMapping）。
 * 具体的记录定位策略/SQL 拼装方式由实现类决定。
 * </p>
 *
 * <p>
 * <b>设计动机（WHY 独立接口）</b>：Standalone 模块需与 DAP 解耦，调用方仅依赖本接口即可完成 Apply，
 * 无需关心 diff 来源（结果表 vs 内存）、事务边界（主库 vs 外部数据源）等实现细节。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public interface StandaloneApplyExecutor {
    /**
     * 执行 Apply 计划。
     *
     * @param plan 待执行的 Apply 计划
     * @param mode 执行模式（DRY_RUN/EXECUTE）；部分调用方会强制传入 EXECUTE 覆盖 plan.options.mode
     * @return 执行结果，含 affectedRows、idMapping、errors 等
     */
    ApplyResult execute(ApplyPlan plan, ApplyMode mode);
}

