package com.diff.core.domain.exception;

import com.diff.core.domain.apply.ApplyResult;
import lombok.Getter;

/**
 * Apply 执行过程中的异常——携带已执行的部分结果。
 *
 * <p>
 * 为什么需要单独的异常子类而非直接使用 {@link TenantDiffException}：
 * Apply 可能在执行到一半时失败，此时已有部分动作成功写入数据库。
 * 调用方需要从异常中提取 {@link #partialResult} 来了解哪些动作已执行、
 * 哪些失败，以便决定是否需要回滚。
 * </p>
 *
 * @author tenant-diff
 * @since 1.0.0
 * @see ApplyResult
 */
@Getter
public class ApplyExecutionException extends TenantDiffException {

    /** 已执行的部分结果（包含已成功的动作数和错误明细）。 */
    private final ApplyResult partialResult;

    /**
     * @param message       错误描述
     * @param cause         根因异常
     * @param partialResult 已执行的部分结果
     */
    public ApplyExecutionException(String message, Throwable cause, ApplyResult partialResult) {
        super(ErrorCode.INTERNAL_ERROR, message, cause);
        this.partialResult = partialResult;
    }
}
