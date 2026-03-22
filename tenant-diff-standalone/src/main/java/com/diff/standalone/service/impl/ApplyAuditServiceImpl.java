package com.diff.standalone.service.impl;

import com.diff.core.domain.apply.ApplyRecordStatus;
import com.diff.standalone.persistence.entity.TenantDiffApplyRecordPo;
import com.diff.standalone.persistence.mapper.TenantDiffApplyRecordMapper;
import com.diff.standalone.service.ApplyAuditService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 基于独立事务的 Apply 审计服务实现。
 *
 * <p>通过 {@code REQUIRES_NEW} 隔离 apply_record 的写入，确保主执行链路失败时仍保留审计轨迹。</p>
 *
 * @author tenant-diff
 * @since 2026-03-22
 */
public class ApplyAuditServiceImpl implements ApplyAuditService {

    private final TenantDiffApplyRecordMapper applyRecordMapper;
    private final TransactionTemplate requiresNewTransactionTemplate;

    /**
     * @param applyRecordMapper apply_record 持久化入口
     * @param transactionManager 事务管理器，用于构造独立事务模板
     */
    public ApplyAuditServiceImpl(TenantDiffApplyRecordMapper applyRecordMapper,
                                 PlatformTransactionManager transactionManager) {
        this.applyRecordMapper = applyRecordMapper;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public TenantDiffApplyRecordPo createRunningRecord(TenantDiffApplyRecordPo record) {
        if (record == null) {
            throw new IllegalArgumentException("record is null");
        }
        return requiresNewTransactionTemplate.execute(status -> {
            applyRecordMapper.insert(record);
            if (record.getId() == null) {
                throw new IllegalStateException("insert apply_record failed: id is null");
            }
            return record;
        });
    }

    @Override
    public void markFailed(Long applyId,
                           String errorMsg,
                           String failureStage,
                           String failureActionId,
                           String diagnosticsJson,
                           LocalDateTime finishedAt) {
        updateStatus(applyId,
            ApplyRecordStatus.FAILED,
            errorMsg,
            failureStage,
            failureActionId,
            diagnosticsJson,
            finishedAt,
            null,
            null);
    }

    @Override
    public void updateStatus(Long applyId,
                             ApplyRecordStatus status,
                             String errorMsg,
                             String failureStage,
                             String failureActionId,
                             String diagnosticsJson,
                             LocalDateTime finishedAt,
                             String verifyStatus,
                             String verifyJson) {
        if (applyId == null) {
            throw new IllegalArgumentException("applyId is null");
        }
        requiresNewTransactionTemplate.executeWithoutResult(txStatus -> {
            TenantDiffApplyRecordPo update = new TenantDiffApplyRecordPo();
            update.setId(applyId);
            update.setStatus(status == null ? null : status.name());
            update.setErrorMsg(errorMsg);
            update.setFailureStage(failureStage);
            update.setFailureActionId(failureActionId);
            update.setDiagnosticsJson(diagnosticsJson);
            update.setFinishedAt(finishedAt);
            update.setVerifyStatus(verifyStatus);
            update.setVerifyJson(verifyJson);
            applyRecordMapper.updateById(update);
        });
    }
}
