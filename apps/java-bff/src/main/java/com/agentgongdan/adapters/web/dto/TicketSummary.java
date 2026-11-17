package com.agentgongdan.adapters.web.dto;

public record TicketSummary(
        String id,
        String ticketNo,
        String title,
        String statusCode,
        String priorityCode,
        String ticketTypeCode,
        String reporterId,
        String assigneeId,
        String customerId,
        String roomId,
        String equipmentId,
        String locationText,
        String createdAt,
        String updatedAt,
        String slaResolveDueAt,
        Integer version
) {
}
