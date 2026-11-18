package com.agentgongdan.adapters.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record DelegationTokenRequest(
        @NotNull(message = "actorId 不能为空")
        @Positive(message = "actorId 必须为正数")
        Long actorId,
        List<String> roles,
        List<String> scopes,
        Long ttlSeconds
) {
}
