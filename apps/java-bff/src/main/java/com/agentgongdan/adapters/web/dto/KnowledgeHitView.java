package com.agentgongdan.adapters.web.dto;

public record KnowledgeHitView(
        String docId,
        String docNo,
        String title,
        String chunkId,
        Integer chunkNo,
        String vectorId,
        Double score,
        String content
) {
}
