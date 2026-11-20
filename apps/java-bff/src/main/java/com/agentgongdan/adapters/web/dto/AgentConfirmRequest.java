package com.agentgongdan.adapters.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 人工确认请求。
 * decision 可选：APPROVE（默认）/ REJECT，保持旧调用方兼容。
 */
public record AgentConfirmRequest(
        @NotBlank(message = "confirmationToken 不能为空") String confirmationToken,
        String decision
) {
}
