package com.agentgongdan.app.dto;

import java.util.List;

public record AuthResult(
        String accessToken,
        long expiresIn,
        String userId,
        String username,
        String displayName,
        List<String> roles,
        List<String> permissions
) {
}
