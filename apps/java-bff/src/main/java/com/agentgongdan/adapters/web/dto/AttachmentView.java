package com.agentgongdan.adapters.web.dto;

public record AttachmentView(
        String id,
        String fileId,
        String originalName,
        String contentType,
        Long sizeBytes,
        String uploadedBy,
        String attachmentType,
        String createdAt,
        String url
) {
}
