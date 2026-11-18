package com.agentgongdan.adapters.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agentgongdan.adapters.web.dto.ChatMessageView;
import com.agentgongdan.adapters.web.dto.ChatSessionView;
import com.agentgongdan.adapters.web.dto.ConfirmToolCallRequest;
import com.agentgongdan.adapters.web.dto.ConfirmTriageRequest;
import com.agentgongdan.adapters.web.dto.CreateChatSessionRequest;
import com.agentgongdan.adapters.web.dto.PageResult;
import com.agentgongdan.adapters.web.dto.SendChatMessageRequest;
import com.agentgongdan.adapters.web.dto.TicketDetail;
import com.agentgongdan.adapters.web.dto.TicketExportDownloadView;
import com.agentgongdan.adapters.web.dto.TriageRecordView;
import com.agentgongdan.app.service.ChatService;
import com.agentgongdan.app.service.ChatToolCallService;
import com.agentgongdan.app.service.TriageService;
import com.agentgongdan.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/v1/chat")
public class ChatController {

    private final ChatService chatService;
    private final TriageService triageService;
    private final ChatToolCallService toolCallService;

    public ChatController(ChatService chatService, TriageService triageService,
                          ChatToolCallService toolCallService) {
        this.chatService = chatService;
        this.triageService = triageService;
        this.toolCallService = toolCallService;
    }

    @PostMapping("/sessions")
    public ApiResponse<ChatSessionView> createSession(
            @Valid @RequestBody(required = false) CreateChatSessionRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("chat:use");
        return ApiResponse.ok(chatService.createSession(request, StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/sessions")
    public ApiResponse<PageResult<ChatSessionView>> listSessions(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("chat:use");
        return ApiResponse.ok(chatService.listSessions(StpUtil.getLoginIdAsLong(), page, pageSize));
    }

    @GetMapping("/sessions/{id}")
    public ApiResponse<ChatSessionView> getSession(@PathVariable long id) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("chat:use");
        return ApiResponse.ok(chatService.getSession(id, StpUtil.getLoginIdAsLong()));
    }

    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> archiveSession(@PathVariable long id) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("chat:use");
        chatService.archiveSession(id, StpUtil.getLoginIdAsLong());
        return ApiResponse.ok(null);
    }

    @PostMapping("/sessions/{id}/close")
    public ApiResponse<ChatSessionView> closeSession(@PathVariable long id) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("chat:use");
        return ApiResponse.ok(chatService.closeSession(id, StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<ChatMessageView>> listMessages(
            @PathVariable long id,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "50") int limit) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("chat:use");
        return ApiResponse.ok(chatService.listMessages(id, StpUtil.getLoginIdAsLong(), cursor, limit));
    }

    @PostMapping(value = "/sessions/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessage(@PathVariable long id,
                                  @Valid @RequestBody SendChatMessageRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("chat:use");
        return chatService.sendMessage(id, request, StpUtil.getLoginIdAsLong(),
                StpUtil.getRoleList(), MDC.get("traceId"));
    }

    @GetMapping("/sessions/{id}/triage-records")
    public ApiResponse<List<TriageRecordView>> triageRecords(@PathVariable long id) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("chat:use");
        return ApiResponse.ok(triageService.listBySession(id, StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/triage-records/{id}/confirm")
    public ApiResponse<TicketDetail> confirmTriage(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ConfirmTriageRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("chat:use");
        StpUtil.checkPermission("ticket:create");
        ConfirmTriageRequest overrides = request != null ? request
                : new ConfirmTriageRequest(null, null, null, null, null, null, null, null);
        return ApiResponse.ok(triageService.confirm(id, overrides, StpUtil.getLoginIdAsLong()));
    }

    @PostMapping("/export-calls/{id}/confirm")
    public ApiResponse<TicketExportDownloadView> confirmExportCall(@PathVariable long id) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("chat:use");
        StpUtil.checkPermission("ticket:read");
        return ApiResponse.ok(toolCallService.confirmExport(
                id, StpUtil.getLoginIdAsLong(), StpUtil.getRoleList()));
    }

    @PostMapping("/tool-calls/{id}/confirm")
    public ApiResponse<TicketDetail> confirmToolCall(
            @PathVariable long id,
            @Valid @RequestBody(required = false) ConfirmToolCallRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("chat:use");
        StpUtil.checkPermission("ticket:create");
        ConfirmToolCallRequest overrides = request != null ? request
                : new ConfirmToolCallRequest(null, null, null, null, null);
        return ApiResponse.ok(toolCallService.confirm(id, overrides, StpUtil.getLoginIdAsLong()));
    }
}
