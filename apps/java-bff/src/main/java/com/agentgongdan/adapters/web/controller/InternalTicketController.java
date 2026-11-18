package com.agentgongdan.adapters.web.controller;

import com.agentgongdan.adapters.web.dto.CreateTicketRequest;
import com.agentgongdan.adapters.web.dto.InternalTicketExportRequest;
import com.agentgongdan.adapters.web.dto.SimilarTicketView;
import com.agentgongdan.adapters.web.dto.TicketDetail;
import com.agentgongdan.adapters.web.dto.TicketExportDownloadView;
import com.agentgongdan.adapters.web.dto.TicketSummary;
import com.agentgongdan.app.service.TicketExportJobService;
import com.agentgongdan.app.service.TicketService;
import com.agentgongdan.app.service.TicketVectorService;
import com.agentgongdan.common.api.ApiResponse;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.validation.Valid;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Python 工具网关使用的内部接口；由 InternalTokenInterceptor 强制鉴权。
 */
@RestController
@RequestMapping("/internal/v1/tickets")
public class InternalTicketController {

    private final TicketService ticketService;
    private final TicketVectorService ticketVectorService;
    private final TicketExportJobService ticketExportJobService;

    public InternalTicketController(TicketService ticketService,
                                    TicketVectorService ticketVectorService,
                                    TicketExportJobService ticketExportJobService) {
        this.ticketService = ticketService;
        this.ticketVectorService = ticketVectorService;
        this.ticketExportJobService = ticketExportJobService;
    }

    @GetMapping
    public ApiResponse<List<TicketSummary>> list(
            @RequestAttribute("internalActorId") long actorId,
            @RequestAttribute("internalActorRoles") List<String> roles,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.ok(ticketService.listInternal(actorId, roles, status, keyword, limit));
    }

    @GetMapping("/{id}")
    public ApiResponse<TicketDetail> detail(
            @PathVariable long id,
            @RequestAttribute("internalActorId") long actorId,
            @RequestAttribute("internalActorRoles") List<String> roles) {
        return ApiResponse.ok(ticketService.detailInternal(id, actorId, roles));
    }

    @GetMapping("/similar")
    public ApiResponse<List<SimilarTicketView>> similar(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestAttribute("internalActorId") long actorId,
            @RequestAttribute("internalActorRoles") List<String> roles) {
        return ApiResponse.ok(ticketVectorService.similarByQuery(
                query, Math.min(Math.max(topK, 1), 20)));
    }

    /**
     * AI/内部导出：生成 Excel 到项目 MinIO exports/ 前缀，返回 10 分钟预签名地址。
     */
    @PostMapping("/export")
    public ApiResponse<TicketExportDownloadView> export(
            @RequestBody(required = false) InternalTicketExportRequest request,
            @RequestAttribute("internalActorId") long actorId,
            @RequestAttribute("internalActorRoles") List<String> roles) {
        InternalTicketExportRequest req = request == null
                ? new InternalTicketExportRequest(null, null, null, null, null, null, null, null)
                : request;
        boolean privileged = roles.contains("ADMIN") || roles.contains("DISPATCHER")
                || roles.contains("ENGINEER") || roles.contains("INSPECTOR");
        Long scopeUserId = privileged ? null : actorId;
        return ApiResponse.ok(ticketExportJobService.export(
                req.status(), req.keyword(), req.priorityCode(), req.ticketTypeCode(),
                req.location(), req.assigneeId(), scopeUserId,
                parseDbLocalTime(req.startTime()), parseDbLocalTime(req.endTime())));
    }

    private LocalDateTime parseDbLocalTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value)
                    .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                    .toLocalDateTime();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (Exception e) {
                return null;
            }
        }
    }

    @PostMapping
    public ApiResponse<TicketDetail> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTicketRequest request,
            @RequestAttribute("internalActorId") long actorId) {
        String key = StringUtils.hasText(idempotencyKey) ? idempotencyKey : "agent-" + IdWorker.getId();
        return ApiResponse.ok(ticketService.createFromAgent(request, actorId, key));
    }
}
