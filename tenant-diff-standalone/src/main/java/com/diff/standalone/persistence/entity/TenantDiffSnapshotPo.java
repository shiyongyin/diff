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
 * {@code xai_tenant_diff_snapshot} 的 MyBatis-Plus 持久化对象（PO）。
 *
 * <p>
 * 用于保存 Apply 前的业务快照（v1 粒度为 BusinessData JSON），回滚时作为恢复基线（无 DAP 依赖）。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xai_tenant_diff_snapshot")
public class TenantDiffSnapshotPo {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("apply_id")
    private Long applyId;

    @TableField("session_id")
    private Long sessionId;

    @TableField("side")
    private String side;

    @TableField("business_type")
    private String businessType;

    @TableField("business_table")
    private String businessTable;

    @TableField("business_key")
    private String businessKey;

    @TableField("snapshot_json")
    private String snapshotJson;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

