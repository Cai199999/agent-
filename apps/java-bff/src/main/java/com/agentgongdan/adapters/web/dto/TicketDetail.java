package com.agentgongdan.adapters.web.dto;

import java.util.List;

public record TicketDetail(
        TicketSummary ticket,
        String description,
        List<TicketEventView> events,
        String reporterName,
        String assigneeName,
        Integer holdMinutes,
        String holdReason,
        TicketSlaView sla
) {
}
