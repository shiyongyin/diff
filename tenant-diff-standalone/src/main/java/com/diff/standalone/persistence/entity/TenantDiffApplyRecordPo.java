package com.diff.standalone.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * {@code xai_tenant_diff_apply_record} 的 MyBatis-Plus 持久化对象（PO）。
 *
 * <p>
 * 用于记录 Apply 审计信息：planJson、direction、status、errorMsg 与 startedAt/finishedAt（无 DAP 依赖）。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xai_tenant_diff_apply_record")
public class TenantDiffApplyRecordPo {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("apply_key")
    private String applyKey;

    @TableField("session_id")
    private Long sessionId;

    @TableField("direction")
    private String direction;

    @TableField("plan_json")
    private String planJson;

    @TableField("status")
    private String status;

    @TableField("error_msg")
    private String errorMsg;

    @Version
    @TableField("version")
    private Integer version;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;
}

