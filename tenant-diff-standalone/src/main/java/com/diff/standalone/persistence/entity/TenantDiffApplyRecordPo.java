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

    /** Apply 审计表自增主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 对比请求的唯一 key，便于幂等与排查。 */
    @TableField("apply_key")
    private String applyKey;

    /** 关联的 Diff 会话记录 ID。 */
    @TableField("session_id")
    private Long sessionId;

    /** Apply 目标租户编号。 */
    @TableField("target_tenant_id")
    private Long targetTenantId;

    /** 目标数据源 key，null/"primary" 表示主库。 */
    @TableField("target_data_source_key")
    private String targetDataSourceKey;

    /** Apply 方向（A_TO_B / B_TO_A）。 */
    @TableField("direction")
    private String direction;

    /** 原始 Apply 计划 JSON，用于回放或审计。 */
    @TableField("plan_json")
    private String planJson;

    /** 执行状态（RUNNING/FAILED/SUCCESS 等）。 */
    @TableField("status")
    private String status;

    /** 失败时的简要异常信息。 */
    @TableField("error_msg")
    private String errorMsg;

    /** 出错阶段（如 EXECUTE、VERIFY）。 */
    @TableField("failure_stage")
    private String failureStage;

    /** 发生错误的具体 ApplyAction ID，有助于查找差异行。 */
    @TableField("failure_action_id")
    private String failureActionId;

    /** 执行诊断信息 JSON（包含 actionErrors / warnings 等）。 */
    @TableField("diagnostics_json")
    private String diagnosticsJson;

    /** 乐观锁版本号。 */
    @Version
    @TableField("version")
    private Integer version;

    /** Apply 执行开始时间。 */
    @TableField("started_at")
    private LocalDateTime startedAt;

    /** Apply 执行结束时间。 */
    @TableField("finished_at")
    private LocalDateTime finishedAt;

    /** 校验状态（如 VERIFY_PASSED/VERIFY_FAILED）用于回滚前审查。 */
    @TableField("verify_status")
    private String verifyStatus;

    /** 校验详情 JSON。 */
    @TableField("verify_json")
    private String verifyJson;
}
