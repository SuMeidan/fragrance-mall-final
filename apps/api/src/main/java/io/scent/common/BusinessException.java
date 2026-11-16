package io.scent.common;

public final class BusinessException extends RuntimeException {
    private final int status;
    private final String code;

    public BusinessException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static BusinessException invalid(String message) {
        return new BusinessException(400, "VALIDATION", message);
    }

    public static BusinessException missing() {
        return new BusinessException(404, "NOT_FOUND", "内容不存在或无权访问");
    }

    public static BusinessException conflict(String code, String message) {
        return new BusinessException(409, code, message);
    }
}
