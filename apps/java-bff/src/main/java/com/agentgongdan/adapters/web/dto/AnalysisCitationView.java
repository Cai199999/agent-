package com.agentgongdan.adapters.web.dto;

public record AnalysisCitationView(
        String docId,
        String docNo,
        String title,
        Double score
) {
}
