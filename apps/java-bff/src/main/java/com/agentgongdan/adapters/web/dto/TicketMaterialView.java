package com.agentgongdan.adapters.web.dto;

import java.math.BigDecimal;

public record TicketMaterialView(
        String id,
        String materialName,
        String specification,
        BigDecimal quantity,
        String unit,
        BigDecimal amount,
        String remark,
        String createdAt
) {
}
