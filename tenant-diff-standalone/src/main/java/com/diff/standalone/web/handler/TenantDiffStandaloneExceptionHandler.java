package com.diff.standalone.web.handler;

import com.diff.core.domain.exception.TenantDiffException;
import com.diff.standalone.web.ApiResponse;
import com.diff.core.domain.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Standalone Diff 模块异常处理器。
 *
 * <p>
 * <b>设计动机：</b>集中式异常处理，保证所有 Controller 返回统一的 {@link ApiResponse} 结构，
 * 便于前端解析；同时对底层异常做信息脱敏，避免堆栈、SQL、内部类名等敏感信息泄露给客户端。
 * HTTP 状态码与错误类型匹配，便于基础设施监控和 API 网关正确处理。
 * </p>
 *
 * @author tenant-diff
 * @since 2026-01-20
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.diff.standalone.web.controller")
public class TenantDiffStandaloneExceptionHandler {

    private static final Map<String, HttpStatus> ERROR_CODE_STATUS_MAP = Map.of(
        ErrorCode.PARAM_INVALID.getCode(), HttpStatus.BAD_REQUEST,
        ErrorCode.SESSION_NOT_FOUND.getCode(), HttpStatus.NOT_FOUND,
        ErrorCode.APPLY_RECORD_NOT_FOUND.getCode(), HttpStatus.NOT_FOUND,
        ErrorCode.BUSINESS_DETAIL_NOT_FOUND.getCode(), HttpStatus.NOT_FOUND,
        ErrorCode.APPLY_CONCURRENT_CONFLICT.getCode(), HttpStatus.CONFLICT,
        ErrorCode.ROLLBACK_CONCURRENT_CONFLICT.getCode(), HttpStatus.CONFLICT,
        ErrorCode.SESSION_ALREADY_APPLIED.getCode(), HttpStatus.CONFLICT
    );

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_INVALID));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Object>> handleHandlerMethodValidation(HandlerMethodValidationException e) {
        String detail = e.getAllErrors().stream()
            .map(error -> error.getDefaultMessage() == null ? "" : error.getDefaultMessage())
            .collect(Collectors.joining("; "));
        log.warn("方法参数校验失败: {}", detail);
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_INVALID));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
            .map(ConstraintViolation::getMessage)
            .collect(Collectors.joining("; "));
        log.warn("约束校验失败: {}", detail);
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_INVALID));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.REQUEST_BODY_MALFORMED));
    }

    @ExceptionHandler(TenantDiffException.class)
    public ResponseEntity<ApiResponse<Object>> handleTenantDiffException(TenantDiffException e) {
        log.warn("业务异常 [{}]: {}", e.getErrorCode().getCode(), e.getMessage());
        HttpStatus status = ERROR_CODE_STATUS_MAP.getOrDefault(e.getErrorCode().getCode(), HttpStatus.UNPROCESSABLE_ENTITY);
        return ResponseEntity.status(status).body(ApiResponse.fail(e.getErrorCode()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_INVALID));
    }

    /**
     * 兜底处理未捕获异常。内部细节仅记录日志，不返回客户端。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleException(Exception e) {
        log.error("请求处理失败", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }
}
