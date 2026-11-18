package com.agentgongdan.adapters.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AgentConfirmRequest(
        @NotBlank(message = "confirmationToken 不能为空") String confirmationToken
) {
}
