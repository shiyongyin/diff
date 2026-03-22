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
import static java.util.Map.entry;
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

    private static final Map<String, HttpStatus> ERROR_CODE_STATUS_MAP = Map.ofEntries(
        entry(ErrorCode.PARAM_INVALID.getCode(), HttpStatus.BAD_REQUEST),
        entry(ErrorCode.SESSION_NOT_FOUND.getCode(), HttpStatus.NOT_FOUND),
        entry(ErrorCode.APPLY_RECORD_NOT_FOUND.getCode(), HttpStatus.NOT_FOUND),
        entry(ErrorCode.BUSINESS_DETAIL_NOT_FOUND.getCode(), HttpStatus.NOT_FOUND),
        entry(ErrorCode.SESSION_COMPARE_CONFLICT.getCode(), HttpStatus.CONFLICT),
        entry(ErrorCode.APPLY_CONCURRENT_CONFLICT.getCode(), HttpStatus.CONFLICT),
        entry(ErrorCode.APPLY_TARGET_BUSY.getCode(), HttpStatus.CONFLICT),
        entry(ErrorCode.APPLY_COMPARE_TOO_OLD.getCode(), HttpStatus.CONFLICT),
        entry(ErrorCode.ROLLBACK_DRIFT_DETECTED.getCode(), HttpStatus.CONFLICT),
        entry(ErrorCode.ROLLBACK_CONCURRENT_CONFLICT.getCode(), HttpStatus.CONFLICT),
        entry(ErrorCode.SESSION_ALREADY_APPLIED.getCode(), HttpStatus.CONFLICT),
        entry(ErrorCode.INTERNAL_ERROR.getCode(), HttpStatus.INTERNAL_SERVER_ERROR)
    );

    /**
     * 处理参数绑定校验失败，返回统一的错误结构。
     *
     * <p>
     * 该 handler 只负责合并 binding 结果并触发 {@link ErrorCode#PARAM_INVALID}，
     * 让前端感知参数校验失败而不用解析异常堆栈。
     * </p>
     *
     * @param e 校验异常
     * @return 400 + 参数无效错误码
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_INVALID));
    }

    /**
     * 处理方法级参数校验失败（例如 {@code @Validated}）并返回统一响应。
     *
     * @param e 校验异常
     * @return 400 + 参数无效错误码
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Object>> handleHandlerMethodValidation(HandlerMethodValidationException e) {
        String detail = e.getAllErrors().stream()
            .map(error -> error.getDefaultMessage() == null ? "" : error.getDefaultMessage())
            .collect(Collectors.joining("; "));
        log.warn("方法参数校验失败: {}", detail);
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_INVALID));
    }

    /**
     * 处理 {@link jakarta.validation.constraints} 注解触发的约束异常。
     *
     * @param e 校验异常
     * @return 400 + 参数无效错误码
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
            .map(ConstraintViolation::getMessage)
            .collect(Collectors.joining("; "));
        log.warn("约束校验失败: {}", detail);
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_INVALID));
    }

    /**
     * 处理请求体解析失败（如 JSON 结构不匹配）的问题，避免抛出底层 HttpMessageNotReadableException。
     *
     * @param e 解析异常
     * @return 400 + 请求体格式错误
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.REQUEST_BODY_MALFORMED));
    }

    /**
     * 处理业务层抛出的 {@link TenantDiffException}，并根据错误码映射 HTTP 状态。
     *
     * @param e 业务异常
     * @return 业务对应的 Status + 脱敏 ApiResponse
     */
    @ExceptionHandler(TenantDiffException.class)
    public ResponseEntity<ApiResponse<Object>> handleTenantDiffException(TenantDiffException e) {
        log.warn("业务异常 [{}]: {}", e.getErrorCode().getCode(), e.getMessage());
        HttpStatus status = ERROR_CODE_STATUS_MAP.getOrDefault(e.getErrorCode().getCode(), HttpStatus.UNPROCESSABLE_ENTITY);
        return ResponseEntity.status(status).body(ApiResponse.fail(e.getErrorCode()));
    }

    /**
     * 处理 {@link IllegalArgumentException}，常见于 controller 参数预检或 parse 失败。
     *
     * @param e 非法参数异常
     * @return 400 + 参数无效错误
     */
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
