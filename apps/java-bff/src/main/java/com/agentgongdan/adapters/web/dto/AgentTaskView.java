package com.agentgongdan.adapters.web.dto;

public record AgentTaskView(
        String taskId,
        String agentId,
        String capability,
        String status,
        Object result,
        String errorCode,
        String errorMessage,
        String traceId,
        long latencyMs,
        String createdAt
) {
}
