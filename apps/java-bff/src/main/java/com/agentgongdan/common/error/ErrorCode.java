package com.agentgongdan.common.error;

/**
 * 公共错误码（Phase 1 骨架子集，完整 32 个见 04 §9）。
 */
public enum ErrorCode {

    SUCCESS(0, "ok"),
    AUTH_UNAUTHORIZED(10001, "未登录或令牌无效"),
    AUTH_TOKEN_EXPIRED(10002, "令牌过期"),
    AUTH_FORBIDDEN(10003, "无权限"),
    AUTH_LOGIN_FAILED(10004, "账号或密码错误"),
    AUTH_ACCOUNT_LOCKED(10005, "账号锁定"),
    AUTH_REFRESH_INVALID(10006, "刷新令牌无效"),
    TICKET_NOT_FOUND(20001, "工单不存在"),
    TICKET_STATE_CONFLICT(20002, "工单状态冲突"),
    TICKET_ASSIGN_CONFLICT(20003, "派单/抢单冲突"),
    TICKET_IDEMPOTENCY_CONFLICT(20004, "幂等键请求体不一致"),
    AGENT_TASK_DEADLINE_EXCEEDED(20005, "任务已超过截止时间"),
    AI_UNAVAILABLE(30001, "AI 服务不可用"),
    AI_LLM_TIMEOUT(30002, "模型超时"),
    AI_TOOL_FAILED(30003, "工具调用失败"),
    AI_AGENT_STEP_LIMIT(30004, "超出最大执行步骤"),
    AI_STREAM_TIMEOUT(30007, "流式超时"),
    KB_INGEST_FAILED(40001, "知识入库失败"),
    KB_VECTOR_STORE_ERROR(40003, "向量库异常"),
    SYS_STORAGE_ERROR(50003, "对象存储异常"),
    SYS_DEPENDENCY_UNAVAILABLE(50004, "依赖不可用"),
    SYS_RATE_LIMITED(50002, "请求过于频繁，请稍后重试"),
    VALIDATION_ERROR(60001, "参数校验失败"),
    VALIDATION_FILE_TYPE(60002, "文件类型不支持"),
    VALIDATION_FILE_SIZE(60003, "文件超限"),
    SYS_INTERNAL_ERROR(50001, "系统内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
