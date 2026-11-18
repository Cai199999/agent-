package com.agentgongdan.adapters.web.controller;

import com.agentgongdan.adapters.web.dto.AgentConfirmRequest;
import com.agentgongdan.adapters.web.dto.AgentTaskRequest;
import com.agentgongdan.adapters.web.dto.AgentTaskView;
import com.agentgongdan.app.service.AgentTaskService;
import com.agentgongdan.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public AgentTaskController(AgentTaskService agentTaskService) {
        this.agentTaskService = agentTaskService;
    }

    @GetMapping("/manifest")
    public ApiResponse<Map<String, Object>> manifest() {
        return ApiResponse.ok(agentTaskService.manifest());
    }

    @GetMapping("/capabilities")
    public ApiResponse<Map<String, Object>> capabilities() {
        return ApiResponse.ok(agentTaskService.manifest());
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
