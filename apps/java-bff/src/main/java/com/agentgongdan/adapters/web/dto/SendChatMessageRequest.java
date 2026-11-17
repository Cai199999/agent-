package com.agentgongdan.adapters.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record SendChatMessageRequest(
        @Size(max = 64, message = "客户端消息 ID 过长") String clientMessageId,
        @NotBlank(message = "消息内容不能为空")
        @Size(max = 8000, message = "消息内容不能超过 8000 个字符") String content,
        String contentType,
        Map<String, Object> context
) {
}
