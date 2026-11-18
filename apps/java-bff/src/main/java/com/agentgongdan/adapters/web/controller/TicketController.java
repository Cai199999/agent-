package com.agentgongdan.adapters.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.excel.EasyExcel;
import com.agentgongdan.adapters.web.dto.AttachmentView;
import com.agentgongdan.adapters.web.dto.CreateCommentRequest;
import com.agentgongdan.adapters.web.dto.CreateTicketRequest;
import com.agentgongdan.adapters.web.dto.PageResult;
import com.agentgongdan.adapters.web.dto.SimilarTicketView;
import com.agentgongdan.adapters.web.dto.TicketAiAnalysisView;
import com.agentgongdan.adapters.web.dto.TicketCommentView;
import com.agentgongdan.adapters.web.dto.TicketDetail;
import com.agentgongdan.adapters.web.dto.TicketSummary;
import com.agentgongdan.adapters.web.dto.TransitionRequest;
import com.agentgongdan.app.service.AttachmentService;
import com.agentgongdan.app.service.TicketAccessService;
import com.agentgongdan.app.service.TicketAiAnalysisService;
import com.agentgongdan.app.service.TicketExportService;
import com.agentgongdan.app.service.TicketService;
import com.agentgongdan.app.service.TicketVectorService;
import com.agentgongdan.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final AttachmentService attachmentService;
    private final TicketVectorService ticketVectorService;
    private final TicketAiAnalysisService ticketAiAnalysisService;
    private final TicketExportService ticketExportService;
    private final TicketAccessService ticketAccessService;

    public TicketController(TicketService ticketService, AttachmentService attachmentService,
                            TicketVectorService ticketVectorService,
                            TicketAiAnalysisService ticketAiAnalysisService,
                            TicketExportService ticketExportService,
                            TicketAccessService ticketAccessService) {
        this.ticketService = ticketService;
        this.attachmentService = attachmentService;
        this.ticketVectorService = ticketVectorService;
        this.ticketAiAnalysisService = ticketAiAnalysisService;
        this.ticketExportService = ticketExportService;
        this.ticketAccessService = ticketAccessService;
    }

    private void requireReadable(long ticketId) {
        ticketAccessService.requireReadable(ticketId, StpUtil.getLoginIdAsLong(), StpUtil.getRoleList());
    }

    private void requireHandler(long ticketId) {
        ticketAccessService.requireHandler(ticketId, StpUtil.getLoginIdAsLong(), StpUtil.getRoleList());
    }

    private void requireHandlerOrUnassigned(long ticketId) {
        ticketAccessService.requireHandlerOrUnassigned(
                ticketId, StpUtil.getLoginIdAsLong(), StpUtil.getRoleList());
    }

    private void requireReporterOrPrivileged(long ticketId) {
        ticketAccessService.requireReporterOrPrivileged(
                ticketId, StpUtil.getLoginIdAsLong(), StpUtil.getRoleList());
    }

    @PostMapping
    public ApiResponse<TicketDetail> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTicketRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:create");
        return ApiResponse.ok(ticketService.create(request, StpUtil.getLoginIdAsLong(), idempotencyKey));
    }

    @GetMapping
    public ApiResponse<PageResult<TicketSummary>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String priorityCode,
            @RequestParam(required = false) String ticketTypeCode,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:read");
        List<String> roles = StpUtil.getRoleList();
        boolean privileged = roles.contains("ADMIN") || roles.contains("DISPATCHER")
                || roles.contains("ENGINEER") || roles.contains("INSPECTOR");
        Long mineUserId = (mine || !privileged) ? StpUtil.getLoginIdAsLong() : null;
        return ApiResponse.ok(ticketService.list(page, Math.min(pageSize, 100), status, keyword,
                priorityCode, ticketTypeCode, assigneeId, mineUserId,
                parseDbLocalTime(startTime), parseDbLocalTime(endTime)));
    }

    /**
     * Phase A：导出当前数据模型可提供的 16 列，内存生成并直接流式返回。
     * REPORTER 默认仅能导出本人相关工单；ADMIN/DISPATCHER/ENGINEER/INSPECTOR 可导出全部。
     */
    @GetMapping("/export")
    public void export(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String priorityCode,
            @RequestParam(required = false) String ticketTypeCode,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            HttpServletResponse response) throws IOException {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:read");
        List<String> roles = StpUtil.getRoleList();
        boolean privileged = roles.contains("ADMIN") || roles.contains("DISPATCHER")
                || roles.contains("ENGINEER") || roles.contains("INSPECTOR");
        Long scopeUserId = (mine || !privileged) ? StpUtil.getLoginIdAsLong() : null;
        List<List<String>> rows = ticketExportService.exportRows(status, keyword, priorityCode,
                ticketTypeCode, null, assigneeId, scopeUserId,
                parseDbLocalTime(startTime), parseDbLocalTime(endTime));
        String filename = "tickets-" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".xlsx";
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''"
                        + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
        EasyExcel.write(response.getOutputStream())
                .head(TicketExportService.HEADERS.stream().map(List::of).toList())
                .sheet("工单")
                .doWrite(rows);
        response.flushBuffer();
    }

    /**
     * 前端传入 ISO UTC；created_at 由 MySQL 按数据库本地时区写入，查询前统一转为 Asia/Shanghai。
     */
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

    @GetMapping("/{id}")
    public ApiResponse<TicketDetail> detail(@PathVariable long id) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:read");
        requireReadable(id);
        return ApiResponse.ok(ticketService.detail(id));
    }

    @GetMapping("/{id}/comments")
    public ApiResponse<List<TicketCommentView>> comments(@PathVariable long id) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:read");
        requireReadable(id);
        return ApiResponse.ok(ticketService.listComments(id));
    }

    @PostMapping("/{id}/comments")
    public ApiResponse<TicketCommentView> addComment(@PathVariable long id,
                                                     @Valid @RequestBody CreateCommentRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:read");
        requireReadable(id);
        return ApiResponse.ok(ticketService.addComment(
                id, request.content(), request.visibility(), StpUtil.getLoginIdAsLong()));
    }

    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AttachmentView> uploadAttachment(
            @PathVariable long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "GENERAL") String type) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:update");
        requireReadable(id);
        return ApiResponse.ok(attachmentService.upload(
                id, file, StpUtil.getLoginIdAsLong(), type));
    }

    @GetMapping("/{id}/attachments")
    public ApiResponse<List<AttachmentView>> attachments(@PathVariable long id) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:read");
        requireReadable(id);
        return ApiResponse.ok(attachmentService.list(id));
    }

    @DeleteMapping("/{id}/attachments/{attachmentId}")
    public ApiResponse<Void> deleteAttachment(@PathVariable long id,
                                              @PathVariable long attachmentId) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:update");
        requireReadable(id);
        attachmentService.delete(id, attachmentId, StpUtil.getLoginIdAsLong());
        return ApiResponse.<Void>ok(null);
    }

    @PostMapping("/{id}/assign")
    public ApiResponse<TicketDetail> assign(@PathVariable long id,
                                            @Valid @RequestBody TransitionRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:assign");
        requireReadable(id);
        return ApiResponse.ok(ticketService.transition(id, "assign", request, StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/{id}/accept")
    public ApiResponse<TicketDetail> accept(@PathVariable long id,
                                            @Valid @RequestBody TransitionRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:handle");
        requireHandlerOrUnassigned(id);
        return ApiResponse.ok(ticketService.transition(id, "accept", request, StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<TicketDetail> resolve(@PathVariable long id,
                                             @Valid @RequestBody TransitionRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:handle");
        requireHandler(id);
        return ApiResponse.ok(ticketService.transition(id, "resolve", request, StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/{id}/verify")
    public ApiResponse<TicketDetail> verify(@PathVariable long id,
                                            @Valid @RequestBody TransitionRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:verify");
        requireReporterOrPrivileged(id);
        return ApiResponse.ok(ticketService.transition(id, "verify", request, StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<TicketDetail> close(@PathVariable long id,
                                           @Valid @RequestBody TransitionRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:close");
        requireReadable(id);
        return ApiResponse.ok(ticketService.transition(id, "close", request, StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<TicketDetail> cancel(@PathVariable long id,
                                            @Valid @RequestBody TransitionRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:close");
        requireReadable(id);
        return ApiResponse.ok(ticketService.transition(id, "cancel", request, StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/{id}/hold")
    public ApiResponse<TicketDetail> hold(@PathVariable long id,
                                          @Valid @RequestBody TransitionRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:handle");
        requireHandler(id);
        return ApiResponse.ok(ticketService.transition(id, "hold", request, StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/{id}/resume")
    public ApiResponse<TicketDetail> resume(@PathVariable long id,
                                            @Valid @RequestBody TransitionRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:handle");
        requireHandler(id);
        return ApiResponse.ok(ticketService.transition(id, "resume", request, StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/{id}/finish")
    public ApiResponse<TicketDetail> finish(@PathVariable long id,
                                            @Valid @RequestBody TransitionRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:close");
        requireReadable(id);
        return ApiResponse.ok(ticketService.transition(id, "finish", request, StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/{id}/ai-analysis")
    public ApiResponse<TicketAiAnalysisView> analyze(@PathVariable long id) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:read");
        requireReadable(id);
        return ApiResponse.ok(ticketAiAnalysisService.analyze(id, StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/{id}/ai-analysis")
    public ApiResponse<TicketAiAnalysisView> latestAnalysis(@PathVariable long id) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:read");
        requireReadable(id);
        return ApiResponse.ok(ticketAiAnalysisService.latest(id));
    }

    @GetMapping("/{id}/similar")
    public ApiResponse<List<SimilarTicketView>> similar(@PathVariable long id,
                                                        @RequestParam(defaultValue = "5") int topK) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:read");
        requireReadable(id);
        return ApiResponse.ok(ticketVectorService.similarForTicket(id, Math.min(Math.max(topK, 1), 20)));
    }

    @PostMapping("/vector/reindex")
    public ApiResponse<Map<String, Integer>> reindexTickets(@RequestParam(defaultValue = "500") int limit) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("admin:config");
        return ApiResponse.ok(Map.of("indexed", ticketVectorService.reindexAll(limit)));
    }
}
