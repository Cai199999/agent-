package com.agentgongdan.adapters.web.controller;

import com.agentgongdan.adapters.web.dto.AgentConfirmRequest;
import com.agentgongdan.adapters.web.dto.AgentTaskEventView;
import com.agentgongdan.adapters.web.dto.AgentTaskRequest;
import com.agentgongdan.adapters.web.dto.AgentTaskView;
import com.agentgongdan.adapters.web.dto.AttachmentView;
import com.agentgongdan.adapters.web.dto.DelegationTokenRequest;
import com.agentgongdan.adapters.web.dto.DelegationTokenView;
import com.agentgongdan.app.dto.AgentTaskEvent;
import com.agentgongdan.app.ports.agent.AgentTaskEventStreamPort;
import com.agentgongdan.app.service.AgentTaskEventService;
import com.agentgongdan.app.service.AgentTaskService;
import com.agentgongdan.app.service.AttachmentService;
import com.agentgongdan.app.service.DelegationService;
import com.agentgongdan.app.service.TicketAccessService;
import com.agentgongdan.common.api.ApiResponse;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 子 Agent Task API。
 * HTTP 适配层：参数/响应映射、SSE 传输；业务状态机在 AgentTaskService。
 */
@RestController
@RequestMapping("/agent/v1")
public class AgentTaskController {

    private final AgentTaskService agentTaskService;
    private final AttachmentService attachmentService;
    private final TicketAccessService ticketAccessService;
    private final DelegationService delegationService;
    private final AgentTaskEventStreamPort eventStreamPort;
    private final long streamTimeoutMs;

    public AgentTaskController(AgentTaskService agentTaskService,
                               AttachmentService attachmentService,
                               TicketAccessService ticketAccessService,
                               DelegationService delegationService,
                               AgentTaskEventStreamPort eventStreamPort,
                               @Value("${agent.task.stream.timeout-ms:600000}") long streamTimeoutMs) {
        this.agentTaskService = agentTaskService;
        this.attachmentService = attachmentService;
        this.ticketAccessService = ticketAccessService;
        this.delegationService = delegationService;
        this.eventStreamPort = eventStreamPort;
        this.streamTimeoutMs = streamTimeoutMs;
    }

    @GetMapping("/manifest")
    public ApiResponse<Map<String, Object>> manifest() {
        return ApiResponse.ok(agentTaskService.manifest());
    }

    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Object>> capabilities() {
        return ApiResponse.ok(agentTaskService.manifest());
    }

    @GetMapping("/card")
    public ApiResponse<Map<String, Object>> card() {
        return ApiResponse.ok(agentTaskService.agentCard());
    }

    /**
     * 签发短时委托令牌；已有委托令牌不能再次委托，避免权限扩散。
     */
    @PostMapping("/delegation/token")
    public ApiResponse<DelegationTokenView> issueDelegation(
            @Valid @RequestBody DelegationTokenRequest request,
            @RequestAttribute(name = "internalDelegationId", required = false)
            String currentDelegationId) {
        if (StringUtils.hasText(currentDelegationId)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "委托令牌不能再次委托");
        }
        return ApiResponse.ok(delegationService.issue(
                request.actorId(), request.roles(), request.scopes(), request.ttlSeconds()));
    }

    @PostMapping("/tasks")
    public ApiResponse<AgentTaskView> submit(
            @Valid @RequestBody AgentTaskRequest request,
            @RequestAttribute("internalActorId") long actorId,
            @RequestAttribute("internalActorRoles") List<String> roles,
            @RequestAttribute(name = "internalDelegationScopes", required = false)
            List<String> delegationScopes,
            @RequestAttribute(name = "internalDelegationId", required = false)
            String delegationId) {
        return ApiResponse.ok(agentTaskService.submit(
                request, actorId, roles, delegationScopes, delegationId, MDC.get("traceId")));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<AgentTaskView> get(
            @PathVariable String taskId,
            @RequestAttribute("internalActorId") long actorId) {
        return ApiResponse.ok(agentTaskService.get(taskId, actorId));
    }

    /**
     * 供 Agent 上传现场照片/附件；ONSITE 照片用于 ticket.resolve。
     */
    @PostMapping(value = "/tickets/{ticketId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AttachmentView> uploadAttachment(
            @PathVariable long ticketId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "GENERAL") String type,
            @RequestAttribute("internalActorId") long actorId,
            @RequestAttribute("internalActorRoles") List<String> roles,
            @RequestAttribute(name = "internalDelegationScopes", required = false)
            List<String> delegationScopes) {
        if (delegationScopes != null && !delegationScopes.contains("*")
                && !delegationScopes.contains("ticket.attachment.upload")) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "委托令牌 scope 不允许附件上传");
        }
        if ("ONSITE".equalsIgnoreCase(type)) {
            ticketAccessService.requireHandler(ticketId, actorId, roles);
        } else {
            ticketAccessService.requireReadable(ticketId, actorId, roles);
        }
        return ApiResponse.ok(attachmentService.upload(ticketId, file, actorId, type));
    }

    @GetMapping("/tasks/{taskId}/events")
    public ApiResponse<List<AgentTaskEventView>> events(
            @PathVariable String taskId,
            @RequestAttribute("internalActorId") long actorId) {
        return ApiResponse.ok(agentTaskService.events(taskId, actorId));
    }

    /**
     * SSE 任务事件流：先回放历史，再增量推送，终态后发送 done 并关闭。
     */
    @GetMapping(value = "/tasks/{taskId}/events/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(
            @PathVariable String taskId,
            @RequestAttribute("internalActorId") long actorId,
            HttpServletResponse response) {
        List<AgentTaskEventView> backlog = agentTaskService.events(taskId, actorId);
        SseEmitter emitter = new SseEmitter(streamTimeoutMs);
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");

        AtomicReference<AgentTaskEventStreamPort.Subscription> subscription = new AtomicReference<>();
        AtomicReference<String> lastStatus = new AtomicReference<>();
        Runnable close = () -> {
            AgentTaskEventStreamPort.Subscription current = subscription.getAndSet(null);
            if (current != null) {
                current.close();
            }
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(error -> close.run());

        String afterEventId = null;
        for (AgentTaskEventView event : backlog) {
            sendEvent(emitter, event);
            afterEventId = event.id();
            lastStatus.set(event.status());
            if (AgentTaskEventService.isTerminal(event.status())) {
                sendDone(emitter, taskId, event.status());
                emitter.complete();
                return emitter;
            }
        }

        subscription.set(eventStreamPort.watch(
                taskId, actorId, afterEventId,
                event -> {
                    lastStatus.set(event.status());
                    sendEvent(emitter, event);
                },
                () -> sendComment(emitter),
                () -> {
                    sendDone(emitter, taskId, lastStatus.get());
                    emitter.complete();
                },
                emitter::completeWithError));
        return emitter;
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ApiResponse<AgentTaskView> cancel(
            @PathVariable String taskId,
            @RequestAttribute("internalActorId") long actorId) {
        return ApiResponse.ok(agentTaskService.cancel(taskId, actorId));
    }

    @PostMapping("/tasks/{taskId}/confirm")
    public ApiResponse<AgentTaskView> confirm(
            @PathVariable String taskId,
            @Valid @RequestBody AgentConfirmRequest request,
            @RequestAttribute("internalActorId") long actorId,
            @RequestAttribute("internalActorRoles") List<String> roles) {
        return ApiResponse.ok(agentTaskService.confirm(
                taskId, request.confirmationToken(), actorId, roles));
    }

    private void sendEvent(SseEmitter emitter, AgentTaskEvent event) {
        sendEvent(emitter, new AgentTaskEventView(event.id(), event.taskId(),
                event.status(), event.message(), event.createdAt()));
    }

    private void sendEvent(SseEmitter emitter, AgentTaskEventView event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.id())
                    .name("task-event")
                    .data(event));
        } catch (IOException | IllegalStateException e) {
            throw new UncheckedIOException(new IOException("SSE 连接已关闭", e));
        }
    }

    private void sendDone(SseEmitter emitter, String taskId, String status) {
        try {
            emitter.send(SseEmitter.event()
                    .name("done")
                    .data(Map.of("taskId", taskId, "status", status == null ? "UNKNOWN" : status)));
        } catch (IOException | IllegalStateException ignored) {
            // 客户端可能已断开，无需再处理
        }
    }

    private void sendComment(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("keep-alive"));
        } catch (IOException | IllegalStateException ignored) {
            // 客户端可能已断开，无需再处理
        }
    }
}
