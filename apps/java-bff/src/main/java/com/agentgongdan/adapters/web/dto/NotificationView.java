package com.agentgongdan.adapters.web.dto;

public record NotificationView(
        String id,
        String typeCode,
        String title,
        String content,
        String bizType,
        String bizId,
        String readAt,
        String createdAt
) {
}
