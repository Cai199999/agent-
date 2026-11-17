package com.agentgongdan.common.error;

import cn.dev33.satoken.exception.NotLoginException;
import com.agentgongdan.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> handleApiException(ApiException ex) {
        HttpStatus status = switch (ex.getErrorCode()) {
            case AUTH_UNAUTHORIZED, AUTH_TOKEN_EXPIRED, AUTH_LOGIN_FAILED, AUTH_REFRESH_INVALID ->
                    HttpStatus.UNAUTHORIZED;
            case AUTH_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case AUTH_ACCOUNT_LOCKED -> HttpStatus.LOCKED;
            case TICKET_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case TICKET_STATE_CONFLICT, TICKET_ASSIGN_CONFLICT, TICKET_IDEMPOTENCY_CONFLICT ->
                    HttpStatus.CONFLICT;
            case VALIDATION_FILE_SIZE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case SYS_STORAGE_ERROR, KB_VECTOR_STORE_ERROR, SYS_DEPENDENCY_UNAVAILABLE, AI_UNAVAILABLE ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            case KB_INGEST_FAILED, AI_LLM_TIMEOUT, AI_STREAM_TIMEOUT, AI_TOOL_FAILED -> HttpStatus.GATEWAY_TIMEOUT;
            case AI_AGENT_STEP_LIMIT -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotLogin(NotLoginException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ErrorCode.AUTH_UNAUTHORIZED));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse(ErrorCode.VALIDATION_ERROR.getMessage());
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ErrorCode.VALIDATION_ERROR, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(ErrorCode.SYS_INTERNAL_ERROR));
    }
}
