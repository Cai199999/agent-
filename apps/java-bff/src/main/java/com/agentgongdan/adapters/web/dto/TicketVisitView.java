package com.agentgongdan.adapters.web.dto;

public record TicketVisitView(
        String id,
        Boolean required,
        String visitMethod,
        String visitorId,
        String visitorName,
        String visitedAt,
        String visitResult,
        String status,
        String remark
) {
}
