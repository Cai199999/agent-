package com.agentgongdan.adapters.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agentgongdan.adapters.web.dto.AddTicketMaterialRequest;
import com.agentgongdan.adapters.web.dto.TicketMaterialView;
import com.agentgongdan.app.service.TicketMaterialService;
import com.agentgongdan.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/materials")
public class TicketMaterialController {

    private final TicketMaterialService materialService;

    public TicketMaterialController(TicketMaterialService materialService) {
        this.materialService = materialService;
    }

    @GetMapping
    public ApiResponse<List<TicketMaterialView>> list(@PathVariable long ticketId) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:read");
        return ApiResponse.ok(materialService.list(ticketId));
    }

    @PostMapping
    public ApiResponse<TicketMaterialView> add(@PathVariable long ticketId,
                                               @Valid @RequestBody AddTicketMaterialRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:handle");
        return ApiResponse.ok(materialService.add(ticketId, request, StpUtil.getLoginIdAsLong()));
    }
}
