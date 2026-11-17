package com.agentgongdan.adapters.web.dto;

import java.util.Map;

public record TriageRecordView(
        String id,
        String messageId,
        String intentCode,
        Map<String, Object> entities,
        Double confidence,
        String action,
        Boolean lowConfidence,
        Map<String, Object> draft,
        String draftStatus,
        String model,
        String createdAt,
        String confirmedAt,
        String ticketId,
        String ticketNo,
        String ticketStatusCode
) {
}
