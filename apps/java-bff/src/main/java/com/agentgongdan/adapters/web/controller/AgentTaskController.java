package com.agentgongdan.adapters.web.controller;

import com.agentgongdan.adapters.web.dto.AgentConfirmRequest;
import com.agentgongdan.adapters.web.dto.AgentTaskEventView;
import com.agentgongdan.adapters.web.dto.AgentTaskRequest;
import com.agentgongdan.adapters.web.dto.AgentTaskView;
import com.agentgongdan.adapters.web.dto.AttachmentView;
import com.agentgongdan.app.service.AgentTaskService;
import com.agentgongdan.app.service.AttachmentService;
import com.agentgongdan.app.service.TicketAccessService;
import com.agentgongdan.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 子 Agent P0：Manifest + 只读 Task API。
 * 由 InternalTokenInterceptor 鉴权，通过 X-Actor-* 传递 actor。
 */
@RestController
@RequestMapping("/agent/v1")
public class AgentTaskController {

    private final AgentTaskService agentTaskService;
    private final AttachmentService attachmentService;
    private final TicketAccessService ticketAccessService;

    public AgentTaskController(AgentTaskService agentTaskService,
                               AttachmentService attachmentService,
                               TicketAccessService ticketAccessService) {
        this.agentTaskService = agentTaskService;
        this.attachmentService = attachmentService;
        this.ticketAccessService = ticketAccessService;
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

    @PostMapping("/tasks")
    public ApiResponse<AgentTaskView> submit(
            @Valid @RequestBody AgentTaskRequest request,
            @RequestAttribute("internalActorId") long actorId,
            @RequestAttribute("internalActorRoles") List<String> roles) {
        return ApiResponse.ok(agentTaskService.submit(request, actorId, roles, MDC.get("traceId")));
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
            @RequestAttribute("internalActorRoles") List<String> roles) {
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
}
