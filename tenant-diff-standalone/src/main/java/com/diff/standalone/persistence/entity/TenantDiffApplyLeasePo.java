package com.diff.standalone.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * {@code xai_tenant_diff_apply_lease} 的持久化对象。
 *
 * <p>用于为同一目标租户 + 数据源建立跨 session 可见的 Apply 互斥租约，避免并发 Apply
 * 同时向同一数据源写入而导致数据冲突，同时支持租约过期与刷新。</p>
 *
 * <p>记录包括 session/Apply 关联、租约 token 与时间戳，方便 Apply 任务调度或租约续租时
 * 按照 expiresAt 比较并判定冲突。</p>
 *
 * @author tenant-diff
 * @since 2026-03-22
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xai_tenant_diff_apply_lease")
public class TenantDiffApplyLeasePo {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("target_tenant_id")
    private Long targetTenantId;

    @TableField("target_data_source_key")
    private String targetDataSourceKey;

    @TableField("session_id")
    private Long sessionId;

    @TableField("apply_id")
    private Long applyId;

    @TableField("lease_token")
    private String leaseToken;

    @TableField("leased_at")
    private LocalDateTime leasedAt;

    @TableField("expires_at")
    private LocalDateTime expiresAt;
}
