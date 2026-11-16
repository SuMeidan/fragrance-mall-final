package io.scent.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiResult<Void>> upload() {
        return ResponseEntity.status(413).body(ApiResult.error(413,"UPLOAD_TOO_LARGE","请选择5MiB以内的PNG或JPEG图片"));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResult<Void>> business(BusinessException ex) {
        // Business errors are JSON even when a streaming caller only accepts SSE.
        return ResponseEntity.status(ex.status()).contentType(MediaType.APPLICATION_JSON)
            .body(ApiResult.error(ex.status(), ex.code(), ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
        HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
        HttpMessageNotReadableException.class, ServletRequestBindingException.class})
    ResponseEntity<ApiResult<Void>> validation(Exception ex) {
        return ResponseEntity.badRequest().body(ApiResult.error(400, "VALIDATION", "请检查输入内容和字段格式"));
    }

    @ExceptionHandler({DataIntegrityViolationException.class, PessimisticLockingFailureException.class})
    ResponseEntity<ApiResult<Void>> conflict(Exception ex) {
        LOG.warn("Database write conflict: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(409).body(ApiResult.error(409, "STATE_CONFLICT", "数据已变化，请刷新后重试"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResult<Void>> missing() {
        return ResponseEntity.status(404).body(ApiResult.error(404, "NOT_FOUND", "内容不存在"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiResult<Void>> method() {
        return ResponseEntity.status(405).body(ApiResult.error(405, "METHOD_NOT_ALLOWED", "请求方法不支持"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResult<Void>> unexpected(Exception ex) {
        // Do not log request bodies, authentication headers or SQL parameter values.
        String location = java.util.Arrays.stream(ex.getStackTrace()).filter(frame -> frame.getClassName().startsWith("io.scent."))
            .findFirst().map(frame -> frame.getClassName()+":"+frame.getLineNumber()).orElse("framework");
        LOG.error("Unhandled request failure: {} at {}", ex.getClass().getName(), location);
        return ResponseEntity.internalServerError().body(ApiResult.error(500, "INTERNAL_ERROR", "服务暂时异常，请凭traceId联系维护者"));
    }
}
