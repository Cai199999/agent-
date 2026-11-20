package com.agentgongdan.adapters.web.dto;

public record AgentTaskEventView(
        String id,
        long seq,
        String taskId,
        String type,
        String status,
        String message,
        String createdAt
) {
}
