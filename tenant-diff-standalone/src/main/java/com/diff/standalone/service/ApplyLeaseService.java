package com.diff.standalone.service;

import com.diff.standalone.persistence.entity.TenantDiffApplyLeasePo;

import java.time.Duration;

/**
 * Apply 目标租约服务：负责跨 session 的目标租户持久锁。
 *
 * <p>
 * Apply 启动前在独立事务获取租约，执行完成或异常时再释放；租约保证同一个目标租户不会被多个 Apply 同时写入。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-03-22
 */
public interface ApplyLeaseService {

    /**
     * 在目标租户上获取租约，若已存在且未过期则抛出 {@link com.diff.core.domain.exception.TenantDiffException#APPLY_TARGET_BUSY}。
     *
     * @param targetTenantId      目标租户 ID
     * @param targetDataSourceKey 目标数据源标识（如 "primary"）
     * @param sessionId           当前 sessionId
     * @param leaseTtl            租约有效期
     * @return 新建的租约记录
     */
    TenantDiffApplyLeasePo acquire(Long targetTenantId,
                                   String targetDataSourceKey,
                                   Long sessionId,
                                   Duration leaseTtl);

    /**
     * 释放租约（成功结束或异常时调用）。
     *
     * @param leaseToken 租约标识
     */
    void release(String leaseToken);
}
