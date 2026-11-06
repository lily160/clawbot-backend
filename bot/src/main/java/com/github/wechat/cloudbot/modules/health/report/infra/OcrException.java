package com.github.wechat.cloudbot.modules.health.report.infra;

public class OcrException extends RuntimeException {

    private final String code;
    private final String requestId;

    public OcrException(String code, String requestId, String message) {
        super(message);
        this.code = code == null ? "" : code;
        this.requestId = requestId == null ? "" : requestId;
    }

    public OcrException(String code, String requestId, String message, Throwable cause) {
        super(message, cause);
        this.code = code == null ? "" : code;
        this.requestId = requestId == null ? "" : requestId;
    }

    public String code() {
        return code;
    }

    public String requestId() {
        return requestId;
    }
}
