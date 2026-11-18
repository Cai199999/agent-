package com.agentgongdan.adapters.web.dto;

public record ProfileReviewView(
        String userId,
        String username,
        String displayName,
        String contactName,
        String phoneMask,
        String projectId,
        String buildingId,
        String roomId,
        String customerId,
        String reviewStatus,
        String createdAt
) {
}
