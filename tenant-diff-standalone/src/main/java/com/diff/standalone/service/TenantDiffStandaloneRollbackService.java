package com.diff.standalone.service;


import com.diff.standalone.web.dto.response.TenantDiffRollbackResponse;

/**
 * Standalone 回滚服务（将 TARGET 恢复到 Apply 前快照）。
 *
 * <p>
 * v1 回滚策略：对“apply 前 TARGET 快照”与“当前 TARGET”再次执行 Diff，
 * 然后生成一份“恢复计划”并执行，从而达到回到 Apply 前状态的目的。
 * </p>
 *
 * <p>
 * <b>设计动机：与 Diff/Apply 分离为独立服务</b>。回滚具有不同的事务语义与数据流：
 * 基于快照的逆向 Diff，需 CAS 乐观锁防并发；v1 仅支持主库 target，外部数据源回滚需二期支持。
 * 独立服务便于单独配置事务、超时与权限，避免与 Apply 的写事务耦合。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
public interface TenantDiffStandaloneRollbackService {
    default TenantDiffRollbackResponse rollback(Long applyId) {
        return rollback(applyId, false);
    }

    /**
     * 回滚指定的 Apply 操作。
     *
     * @param applyId Apply 记录 ID（对应 apply_record 表主键）
     * @param acknowledgeDrift 是否显式确认“目标数据已在 Apply 后发生漂移”
     * @return 回滚执行结果（含 applyResult、affectedRows 等）
     * @throws IllegalArgumentException applyId 为空
     * @throws com.diff.core.domain.exception.TenantDiffException 记录不存在、已回滚、非 SUCCESS 状态、
     *         并发冲突或数据源不支持回滚
     */
    TenantDiffRollbackResponse rollback(Long applyId, boolean acknowledgeDrift);
}
