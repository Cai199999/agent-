package com.agentgongdan.adapters.web.dto;

import java.util.List;

public record TicketAiAnalysisView(
        String id,
        String ticketId,
        String summary,
        List<String> causes,
        List<String> actions,
        String riskLevel,
        List<AnalysisCitationView> citations,
        String model,
        String createdAt
) {
}
