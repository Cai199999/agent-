package com.agentgongdan.adapters.web.dto;

public record TicketEventView(
        String eventId,
        String eventType,
        String fromStatus,
        String toStatus,
        String operatorId,
        String reason,
        String createdAt
) {
}
