package io.scent.common;

import org.slf4j.MDC;

public record ApiResult<T>(int resultCode, String message, T data, String errorCode, String traceId) {
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(200, "success", data, null, MDC.get("traceId"));
    }

    public static <T> ApiResult<T> created(T data) {
        return new ApiResult<>(201, "success", data, null, MDC.get("traceId"));
    }

    public static ApiResult<Void> error(int status, String code, String message) {
        return new ApiResult<>(status, message, null, code, MDC.get("traceId"));
    }
}
