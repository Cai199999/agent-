package com.agentgongdan.adapters.web.dto;

public record ReviewProfileRequest(
        Boolean approved,
        String remark
) {
}
