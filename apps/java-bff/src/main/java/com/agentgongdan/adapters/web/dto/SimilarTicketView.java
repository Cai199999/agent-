package com.agentgongdan.adapters.web.dto;

public record SimilarTicketView(
        String ticketId,
        String ticketNo,
        String title,
        String statusCode,
        String priorityCode,
        String ticketTypeCode,
        Double score
) {
}
