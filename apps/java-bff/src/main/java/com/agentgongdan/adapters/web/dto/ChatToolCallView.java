package com.agentgongdan.adapters.web.dto;

import java.util.Map;

public record ChatToolCallView(
        String id,
        String toolCallId,
        String name,
        String status,
        Boolean requiresConfirm,
        Map<String, Object> arguments,
        String argumentsSummary,
        String resultSummary,
        String resultTicketId,
        String resultUrl,
        String resultFilename,
        Integer resultRowCount,
        String errorCode
) {
}
