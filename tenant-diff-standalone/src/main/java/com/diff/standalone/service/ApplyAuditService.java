package com.diff.standalone.service;

import com.diff.core.domain.apply.ApplyRecordStatus;
import com.diff.standalone.persistence.entity.TenantDiffApplyRecordPo;

import java.time.LocalDateTime;

/**
 * Apply 审计服务。
 *
 * <p>使用独立事务写入/更新 {@code apply_record}，确保业务事务失败时审计轨迹仍可保留。</p>
 *
 * <p>该服务与业务执行解耦：即便 Apply 主流程回滚，审计记录仍能在 {@code PROPAGATION_REQUIRES_NEW} 事务中持久化。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-03-22
 */
public interface ApplyAuditService {

    /**
     * 创建一条 RUNNING 状态的 {@code apply_record}，用于后续填充执行状态。
     *
     * <p>
     * 该操作运行于独立事务，避免业务执行失败时审计记录被回滚。
     * </p>
     *
     * @param record Base record 包含 planJson、targetTenantId 等
     * @return 持久化后的记录（含数据库生成 ID）
     */
    TenantDiffApplyRecordPo createRunningRecord(TenantDiffApplyRecordPo record);

    /**
     * 将指定 apply 标记为 FAILED。
     *
     * @param applyId          applyId
     * @param errorMsg         用户友好错误描述
     * @param failureStage     当前执行阶段（如 LOAD、EXECUTE）
     * @param failureActionId  所属动作 ID
     * @param diagnosticsJson  结构化诊断信息（如剩余 diff、堆栈片段）
     * @param finishedAt       结束时间
     */
    void markFailed(Long applyId,
                    String errorMsg,
                    String failureStage,
                    String failureActionId,
                    String diagnosticsJson,
                    LocalDateTime finishedAt);

    /**
     * 通用状态更新接口，既用于成功、失败，也用于回滚/验证字段的填充。
     *
     * @param applyId          applyId
     * @param status           新状态
     * @param errorMsg         错误信息
     * @param failureStage     失败阶段
     * @param failureActionId  失败的 actionId
     * @param diagnosticsJson  诊断详情
     * @param finishedAt       完成时间
     * @param verifyStatus     验证状态（如 SUCCESS/FAILED）
     * @param verifyJson       验证摘要 JSON
     */
    void updateStatus(Long applyId,
                      ApplyRecordStatus status,
                      String errorMsg,
                      String failureStage,
                      String failureActionId,
                      String diagnosticsJson,
                      LocalDateTime finishedAt,
                      String verifyStatus,
                      String verifyJson);
}
