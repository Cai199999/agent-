package com.agentgongdan.adapters.web.dto;

public record KnowledgeDocView(
        String id,
        String docNo,
        String title,
        String categoryCode,
        String status,
        Integer chunkCount,
        String embeddingModel,
        Integer embeddingDim,
        String createdAt
) {
}
