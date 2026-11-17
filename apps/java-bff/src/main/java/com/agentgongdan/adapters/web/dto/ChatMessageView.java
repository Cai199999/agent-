package com.agentgongdan.adapters.web.dto;

import java.util.List;

public record ChatMessageView(
        String id,
        String role,
        String content,
        String contentType,
        String status,
        Integer tokenIn,
        Integer tokenOut,
        Integer latencyMs,
        String errorCode,
        String createdAt,
        List<ChatCitationView> citations,
        List<ChatToolCallView> toolCalls
) {
}
