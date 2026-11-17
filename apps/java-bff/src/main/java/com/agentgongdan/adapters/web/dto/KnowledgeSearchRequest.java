package com.agentgongdan.adapters.web.dto;

import jakarta.validation.constraints.NotBlank;

public record KnowledgeSearchRequest(
        @NotBlank(message = "查询内容不能为空") String query,
        Integer topK,
        String categoryCode
) {
}
