package com.agentgongdan.adapters.web.dto;

import jakarta.validation.constraints.Size;

public record UpdateUserProfileRequest(
        @Size(max = 100) String contactName,
        @Size(max = 32) String phone,
        Long projectId,
        Long buildingId,
        Long roomId,
        Long customerId
) {
}
