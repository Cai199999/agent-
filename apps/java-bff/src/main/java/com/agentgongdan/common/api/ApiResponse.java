package com.agentgongdan.common.api;

import com.agentgongdan.common.error.ErrorCode;
import org.slf4j.MDC;

/**
 * 统一响应体：code / message / data / traceId。
 */
public record ApiResponse<T>(int code, String message, T data, String traceId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data, currentTraceId());
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.getCode(), errorCode.getMessage(), null, currentTraceId());
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(), message, null, currentTraceId());
    }

    private static String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? "" : traceId;
    }
}
