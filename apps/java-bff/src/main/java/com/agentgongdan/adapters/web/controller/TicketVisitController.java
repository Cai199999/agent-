package com.agentgongdan.adapters.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agentgongdan.adapters.web.dto.AddTicketVisitRequest;
import com.agentgongdan.adapters.web.dto.TicketVisitView;
import com.agentgongdan.app.service.TicketVisitService;
import com.agentgongdan.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tickets/{ticketId}/visits")
public class TicketVisitController {

    private final TicketVisitService visitService;

    public TicketVisitController(TicketVisitService visitService) {
        this.visitService = visitService;
    }

    @GetMapping
    public ApiResponse<List<TicketVisitView>> list(@PathVariable long ticketId) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:read");
        return ApiResponse.ok(visitService.list(ticketId));
    }

    @PostMapping
    public ApiResponse<TicketVisitView> add(@PathVariable long ticketId,
                                            @RequestBody AddTicketVisitRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:close");
        return ApiResponse.ok(visitService.add(ticketId, request, StpUtil.getLoginIdAsLong()));
    }
}
