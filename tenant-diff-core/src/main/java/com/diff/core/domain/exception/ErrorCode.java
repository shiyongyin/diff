package com.diff.core.domain.exception;

/**
 * 结构化错误码枚举——跨层共享的错误标识与用户友好消息。
 *
 * <p>
 * 为什么集中管理错误码：分散在各处的硬编码错误信息难以维护且容易不一致，
 * 集中枚举确保同一错误在 API 响应、日志、监控中使用统一的 code 和 message。
 * </p>
 *
 * <p>编码格式：{@code DIFF_E_xxxx}，按功能域分段（0xxx=通用，1xxx=Session，2xxx=Apply，3xxx=Rollback）。</p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see TenantDiffException
 */
public enum ErrorCode {
    // ── 通用（0xxx） ──
    PARAM_INVALID("DIFF_E_0001", "请求参数不合法"),
    REQUEST_BODY_MALFORMED("DIFF_E_0002", "请求体格式错误"),
    INTERNAL_ERROR("DIFF_E_0003", "请求处理失败，请稍后重试"),

    // ── Session（1xxx） ──
    SESSION_NOT_FOUND("DIFF_E_1001", "会话不存在"),
    BUSINESS_DETAIL_NOT_FOUND("DIFF_E_1002", "业务明细不存在"),
    SESSION_NOT_READY("DIFF_E_1003", "会话尚未完成对比，无法执行 Apply"),
    SESSION_ALREADY_APPLIED("DIFF_E_1004", "该会话已有成功的 Apply 记录，请勿重复执行"),
    SESSION_COMPARE_CONFLICT("DIFF_E_1005", "当前会话处于写入态，无法重跑对比"),

    // ── Apply（2xxx） ──
    APPLY_THRESHOLD_EXCEEDED("DIFF_E_2001", "影响行数超过安全阈值"),
    APPLY_DELETE_NOT_ALLOWED("DIFF_E_2002", "未授权 DELETE 操作"),
    APPLY_RECORD_NOT_FOUND("DIFF_E_2003", "Apply 记录不存在"),
    APPLY_NOT_SUCCESS("DIFF_E_2004", "Apply 记录状态不是 SUCCESS，无法回滚"),
    APPLY_ALREADY_ROLLED_BACK("DIFF_E_2005", "该 Apply 已被回滚，请勿重复执行"),
    APPLY_CONCURRENT_CONFLICT("DIFF_E_2006", "并发冲突：当前会话正在执行 Apply 或已被其他请求处理"),
    APPLY_UNSAFE_AFFECTED_ROWS("DIFF_E_2007", "执行结果异常：单次动作影响多行"),
    APPLY_TARGET_BUSY("DIFF_E_2008", "目标租户当前已有进行中的 Apply"),
    SELECTION_EMPTY("DIFF_E_2010", "未选择任何记录"),
    SELECTION_INVALID_ID("DIFF_E_2011", "所选记录标识无效"),
    SELECTION_STALE("DIFF_E_2012", "数据已变化，请重新预览"),
    PREVIEW_TOO_LARGE("DIFF_E_2014", "预览结果过大，请缩小筛选范围"),
    PREVIEW_TOKEN_EXPIRED("DIFF_E_2015", "预览令牌已过期，请重新预览"),
    APPLY_COMPARE_TOO_OLD("DIFF_E_2016", "对比结果已过期，请重新执行 Compare"),

    // ── Rollback（3xxx） ──
    ROLLBACK_DATASOURCE_UNSUPPORTED("DIFF_E_3001", "回滚暂不支持外部数据源"),
    ROLLBACK_CONCURRENT_CONFLICT("DIFF_E_3002", "并发冲突：当前 Apply 正在回滚或已被其他请求处理"),
    ROLLBACK_SNAPSHOT_INCOMPLETE("DIFF_E_3003", "回滚快照不完整，无法执行回滚"),
    ROLLBACK_DRIFT_DETECTED("DIFF_E_3004", "目标数据已发生漂移，请确认后再回滚");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * @return 结构化错误码（如 {@code DIFF_E_0001}）
     */
    public String getCode() {
        return code;
    }

    /**
     * @return 用户友好的错误描述
     */
    public String getMessage() {
        return message;
    }
}
