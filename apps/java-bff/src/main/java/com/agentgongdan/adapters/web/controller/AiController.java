package com.agentgongdan.adapters.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agentgongdan.adapters.web.dto.SimilarTicketView;
import com.agentgongdan.adapters.web.dto.SimilarTicketsRequest;
import com.agentgongdan.app.service.TicketVectorService;
import com.agentgongdan.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final TicketVectorService ticketVectorService;

    public AiController(TicketVectorService ticketVectorService) {
        this.ticketVectorService = ticketVectorService;
    }

    @PostMapping("/similar-tickets")
    public ApiResponse<List<SimilarTicketView>> similarTickets(
            @Valid @RequestBody SimilarTicketsRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ai:similar");
        int topK = request.topK() == null ? 5 : Math.min(Math.max(request.topK(), 1), 20);
        List<SimilarTicketView> hits = ticketVectorService.similarByQuery(request.query(), topK);
        if (request.excludeTicketId() != null) {
            hits = hits.stream()
                    .filter(hit -> !hit.ticketId().equals(String.valueOf(request.excludeTicketId())))
                    .toList();
        }
        return ApiResponse.ok(hits);
    }
}
