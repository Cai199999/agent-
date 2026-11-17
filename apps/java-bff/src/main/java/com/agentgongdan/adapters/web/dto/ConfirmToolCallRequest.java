package com.agentgongdan.adapters.web.dto;

import jakarta.validation.constraints.Size;

public record ConfirmToolCallRequest(
        @Size(max = 200, message = "标题不能超过 200 个字符") String title,
        @Size(max = 2000, message = "描述不能超过 2000 个字符") String description,
        @Size(max = 32) String ticketTypeCode,
        @Size(max = 16) String priorityCode,
        @Size(max = 255) String locationText
) {
}
