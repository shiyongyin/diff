package com.diff.standalone.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.diff.core.domain.exception.ErrorCode;
import com.diff.core.domain.exception.TenantDiffException;
import com.diff.standalone.persistence.entity.TenantDiffApplyLeasePo;
import com.diff.standalone.persistence.mapper.TenantDiffApplyLeaseMapper;
import com.diff.standalone.service.ApplyLeaseService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 基于独立事务的 Apply 租约服务实现。
 *
 * <p>租约获取与释放放在独立事务中执行，避免 Apply 主事务回滚后留下“未写入/未释放”状态不一致。</p>
 *
 * @author tenant-diff
 * @since 2026-03-22
 */
public class ApplyLeaseServiceImpl implements ApplyLeaseService {

    private final TenantDiffApplyLeaseMapper applyLeaseMapper;
    private final TransactionTemplate requiresNewTransactionTemplate;

    /**
     * @param applyLeaseMapper apply_lease 持久化入口
     * @param transactionManager 事务管理器，用于创建独立事务模板
     */
    public ApplyLeaseServiceImpl(TenantDiffApplyLeaseMapper applyLeaseMapper,
                                 PlatformTransactionManager transactionManager) {
        this.applyLeaseMapper = applyLeaseMapper;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public TenantDiffApplyLeasePo acquire(Long targetTenantId,
                                          String targetDataSourceKey,
                                          Long sessionId,
                                          Duration leaseTtl) {
        if (targetTenantId == null) {
            throw new IllegalArgumentException("targetTenantId is null");
        }
        if (targetDataSourceKey == null || targetDataSourceKey.isBlank()) {
            throw new IllegalArgumentException("targetDataSourceKey is blank");
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is null");
        }
        Duration effectiveTtl = (leaseTtl == null || leaseTtl.isZero() || leaseTtl.isNegative())
            ? Duration.ofMinutes(10)
            : leaseTtl;
        return requiresNewTransactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();
            applyLeaseMapper.delete(new QueryWrapper<TenantDiffApplyLeasePo>()
                .eq("target_tenant_id", targetTenantId)
                .eq("target_data_source_key", targetDataSourceKey)
                .lt("expires_at", now));

            TenantDiffApplyLeasePo lease = TenantDiffApplyLeasePo.builder()
                .targetTenantId(targetTenantId)
                .targetDataSourceKey(targetDataSourceKey)
                .sessionId(sessionId)
                .leaseToken(UUID.randomUUID().toString().replace("-", ""))
                .leasedAt(now)
                .expiresAt(now.plus(effectiveTtl))
                .build();
            try {
                applyLeaseMapper.insert(lease);
            } catch (DuplicateKeyException e) {
                throw new TenantDiffException(
                    ErrorCode.APPLY_TARGET_BUSY,
                    "target tenant is already locked by another apply", e);
            }
            return lease;
        });
    }

    @Override
    public void release(String leaseToken) {
        if (leaseToken == null || leaseToken.isBlank()) {
            return;
        }
        requiresNewTransactionTemplate.executeWithoutResult(status ->
            applyLeaseMapper.delete(new QueryWrapper<TenantDiffApplyLeasePo>()
                .eq("lease_token", leaseToken))
        );
    }
}
