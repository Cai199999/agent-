package com.agentgongdan.adapters.web.dto;

public record ChatCitationView(
        String docId,
        String chunkId,
        String title,
        Double score,
        Integer rank
) {
}
