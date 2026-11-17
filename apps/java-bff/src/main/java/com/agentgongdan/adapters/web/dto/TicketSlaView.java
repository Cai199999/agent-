package com.agentgongdan.adapters.web.dto;

public record TicketSlaView(
        String responseDueAt,
        String resolveDueAt,
        Boolean responseBreached,
        Boolean resolveBreached,
        String firstResponseAt,
        String acceptedAt,
        String resolvedAt,
        String closedAt
) {
}
