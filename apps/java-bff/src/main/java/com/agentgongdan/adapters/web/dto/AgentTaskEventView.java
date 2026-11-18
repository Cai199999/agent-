package com.agentgongdan.adapters.web.dto;

public record AgentTaskEventView(
        String id,
        String taskId,
        String status,
        String message,
        String createdAt
) {
}
