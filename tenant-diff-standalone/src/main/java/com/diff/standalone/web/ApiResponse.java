package com.diff.standalone.web;

import com.diff.core.domain.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standalone 模块统一 REST 响应包装（不依赖 DAP）。
 *
 * <p>
 * 以 {@code success/code/message/data} 四段式表达结果。
 * </p>
 *
 * <p>
 * <b>设计动机：</b>{@link #fail(String)} 对 message 做 sanitize，过滤包含 exception/sql/jdbc/syntax/stack
 * 等关键词或超长文本，防止内部堆栈、SQL 错误等敏感信息泄露给客户端，降低安全风险。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private boolean success;
    private String code;
    private String message;
    private T data;

    /**
     * 构建成功响应。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return success=true，message="OK"，data 为传入值
     */
    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
            .success(true)
            .message("OK")
            .data(data)
            .build();
    }

    /**
     * 构建失败响应（message 会经 sanitize 处理，避免泄露内部信息）。
     *
     * @param message 原始错误信息
     * @param <T>     数据类型
     * @return success=false，code 为 null，message 为脱敏后的文本
     */
    public static <T> ApiResponse<T> fail(String message) {
        return ApiResponse.<T>builder()
            .success(false)
            .message(sanitizeMessage(message))
            .data(null)
            .build();
    }

    /**
     * 构建失败响应（使用预定义 ErrorCode）。
     *
     * @param errorCode 错误码
     * @param <T>       数据类型
     * @return success=false，code 与 message 来自 errorCode
     */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return ApiResponse.<T>builder()
            .success(false)
            .code(errorCode.getCode())
            .message(errorCode.getMessage())
            .data(null)
            .build();
    }

    private static String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "请求处理失败";
        }
        String trimmed = message.trim();
        String lower = trimmed.toLowerCase();
        boolean unsafe = lower.contains("exception")
            || lower.contains("sql")
            || lower.contains("jdbc")
            || lower.contains("syntax")
            || lower.contains("stack")
            || lower.contains("org.springframework")
            || trimmed.length() > 120;
        return unsafe ? "请求处理失败" : trimmed;
    }
}
