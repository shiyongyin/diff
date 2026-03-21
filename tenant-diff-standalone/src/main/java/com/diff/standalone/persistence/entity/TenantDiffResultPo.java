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
 * {@code xai_tenant_diff_result} 的 MyBatis-Plus 持久化对象（PO）。
 *
 * <p>用于保存 session 的业务级摘要（businessType、businessKey、diffType、statistics）与 diffJson 明细（无 DAP 依赖）。</p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xai_tenant_diff_result")
public class TenantDiffResultPo {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private Long sessionId;

    @TableField("business_type")
    private String businessType;

    @TableField("business_table")
    private String businessTable;

    @TableField("business_key")
    private String businessKey;

    @TableField("business_name")
    private String businessName;

    @TableField("diff_type")
    private String diffType;

    @TableField("statistics_json")
    private String statisticsJson;

    @TableField("diff_json")
    private String diffJson;

    @TableField("created_at")
    private LocalDateTime createdAt;
}

