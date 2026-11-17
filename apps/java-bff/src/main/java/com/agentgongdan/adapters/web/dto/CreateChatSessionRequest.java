package com.agentgongdan.adapters.web.dto;

import jakarta.validation.constraints.Size;

public record CreateChatSessionRequest(
        @Size(max = 200, message = "标题不能超过 200 个字符") String title
) {
}
