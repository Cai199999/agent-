package com.agentgongdan.adapters.web.dto;

import java.util.List;

public record MeResponse(
        UserView user,
        List<String> roles,
        List<String> permissions
) {
}
