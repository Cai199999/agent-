package com.agentgongdan.adapters.web.dto;

public record ChatSessionView(
        String id,
        String sessionNo,
        String title,
        String status,
        Integer messageCount,
        String lastMessageAt,
        String createdAt
) {
}
