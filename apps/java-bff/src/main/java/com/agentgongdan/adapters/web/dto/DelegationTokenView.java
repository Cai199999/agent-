package com.agentgongdan.adapters.web.dto;

public record DelegationTokenView(
        String token,
        String delegationId,
        String expiresAt
) {
}
