package com.diff.core.domain.exception;

import lombok.Getter;

/**
 * 租户差异对比框架的基础异常——所有业务异常的统一基类。
 *
 * <p>
 * 为什么使用 RuntimeException 而非 checked exception：
 * diff/apply 流程中的异常通常不可恢复（如参数非法、会话不存在），
 * 强制 checked exception 只会让调用方堆积无意义的 try-catch。
 * </p>
 *
 * <p>
 * 每个异常实例携带结构化 {@link ErrorCode}，上层异常处理器据此
 * 统一转换为 API 响应中的 code + message，避免泄露内部实现细节。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see ErrorCode
 * @see ApplyExecutionException
 */
@Getter
public class TenantDiffException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * 使用错误码构造异常（message 取自 ErrorCode 默认消息）。
     *
     * @param errorCode 结构化错误码
     */
    public TenantDiffException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 使用错误码和自定义消息构造异常。
     *
     * @param errorCode 结构化错误码
     * @param message   自定义错误消息
     */
    public TenantDiffException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 使用错误码、自定义消息和根因构造异常。
     *
     * @param errorCode 结构化错误码
     * @param message   自定义错误消息
     * @param cause     根因异常
     */
    public TenantDiffException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
