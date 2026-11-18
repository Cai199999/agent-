package com.agentgongdan.adapters.web.dto;

public record AddTicketVisitRequest(
        Boolean required,
        String visitMethod,
        String visitedAt,
        String visitResult,
        String status,
        String remark
) {
}
