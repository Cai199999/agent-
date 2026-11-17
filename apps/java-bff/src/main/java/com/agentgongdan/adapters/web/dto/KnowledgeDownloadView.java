package com.agentgongdan.adapters.web.dto;

public record KnowledgeDownloadView(
        String url,
        String originalName,
        Integer expiresIn
) {
}
