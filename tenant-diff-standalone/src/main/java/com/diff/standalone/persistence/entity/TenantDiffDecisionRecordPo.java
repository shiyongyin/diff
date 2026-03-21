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
 * {@code xai_tenant_diff_decision_record} 的 MyBatis-Plus 持久化对象（PO）。
 *
 * <p>用于记录 API_DEFINITION 等业务类型的差异决策与审计信息（decision、decisionReason、executionStatus 等）。</p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xai_tenant_diff_decision_record")
public class TenantDiffDecisionRecordPo {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private Long sessionId;

    @TableField("business_type")
    private String businessType;

    @TableField("business_key")
    private String businessKey;

    @TableField("table_name")
    private String tableName;

    @TableField("record_business_key")
    private String recordBusinessKey;

    @TableField("diff_type")
    private String diffType;

    @TableField("decision")
    private String decision;

    @TableField("decision_reason")
    private String decisionReason;

    @TableField("decision_time")
    private LocalDateTime decisionTime;

    @TableField("execution_status")
    private String executionStatus;

    @TableField("execution_time")
    private LocalDateTime executionTime;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("apply_id")
    private Long applyId;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
