package com.agentgongdan.adapters.web.dto;

import java.util.List;

public record LoginResponse(
        String accessToken,
        long expiresIn,
        String tokenType,
        UserView user,
        List<String> roles,
        List<String> permissions
) {
}
