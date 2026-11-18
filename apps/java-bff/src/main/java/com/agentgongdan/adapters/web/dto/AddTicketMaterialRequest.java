package com.agentgongdan.adapters.web.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record AddTicketMaterialRequest(
        @NotBlank(message = "材料名称不能为空") String materialName,
        String specification,
        @DecimalMin(value = "0", message = "数量不能为负数") BigDecimal quantity,
        String unit,
        @DecimalMin(value = "0", message = "金额不能为负数") BigDecimal amount,
        String remark
) {
}
