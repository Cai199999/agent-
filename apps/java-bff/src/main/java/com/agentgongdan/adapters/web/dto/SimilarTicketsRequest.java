package com.agentgongdan.adapters.web.dto;

import jakarta.validation.constraints.NotBlank;

public record SimilarTicketsRequest(
        @NotBlank(message = "query 不能为空") String query,
        Integer topK,
        Long excludeTicketId
) {
}
