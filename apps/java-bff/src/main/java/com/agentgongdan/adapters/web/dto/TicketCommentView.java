package com.agentgongdan.adapters.web.dto;

public record TicketCommentView(
        String id,
        String ticketId,
        String parentId,
        String authorId,
        String visibility,
        String content,
        String createdAt
) {
}
