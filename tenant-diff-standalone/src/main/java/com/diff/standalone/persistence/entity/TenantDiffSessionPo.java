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
 * {@code xai_tenant_diff_session} 的 MyBatis-Plus 持久化对象（PO）。
 *
 * <p>Standalone 模块使用该表记录 Diff 会话的输入（source/target tenant、scope、options）与执行状态（无 DAP 依赖）。</p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xai_tenant_diff_session")
public class TenantDiffSessionPo {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("session_key")
    private String sessionKey;

    @TableField("source_tenant_id")
    private Long sourceTenantId;

    @TableField("target_tenant_id")
    private Long targetTenantId;

    @TableField("scope_json")
    private String scopeJson;

    @TableField("options_json")
    private String optionsJson;

    @TableField("status")
    private String status;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @Version
    @TableField("version")
    private Integer version;

    @TableField("finished_at")
    private LocalDateTime finishedAt;
}

