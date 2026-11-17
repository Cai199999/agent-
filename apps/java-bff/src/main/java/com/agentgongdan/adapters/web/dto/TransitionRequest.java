package com.agentgongdan.adapters.web.dto;

import jakarta.validation.constraints.NotNull;

public record TransitionRequest(
        @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
        Long assigneeId,
        Boolean approved,
        String reason,
        String closeReasonCode,
        String cancelReason
) {
}
