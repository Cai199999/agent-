package com.agentgongdan.adapters.web.dto;

public record UserProfileView(
        String contactName,
        String phoneMask,
        String projectId,
        String buildingId,
        String roomId,
        String customerId
) {
}
