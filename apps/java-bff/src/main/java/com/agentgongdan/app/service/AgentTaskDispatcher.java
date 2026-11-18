package com.agentgongdan.app.service;

import com.agentgongdan.adapters.web.dto.AddTicketMaterialRequest;
import com.agentgongdan.adapters.web.dto.AddTicketVisitRequest;
import com.agentgongdan.adapters.web.dto.CreateTicketRequest;
import com.agentgongdan.adapters.web.dto.KnowledgeSearchRequest;
import com.agentgongdan.adapters.web.dto.TransitionRequest;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 子 Agent 能力分发器：只负责把 Task 输入翻译为平台内部用例调用。
 * 持久化、确认、异步调度、事件记录不在此层。
 */
@Service
public class AgentTaskDispatcher {

    private final TicketService ticketService;
    private final KnowledgeService knowledgeService;
    private final TicketVectorService ticketVectorService;
    private final TicketExportJobService ticketExportJobService;
    private final TicketAccessService ticketAccessService;
    private final TicketMaterialService ticketMaterialService;
    private final TicketVisitService ticketVisitService;

    public AgentTaskDispatcher(TicketService ticketService,
                               KnowledgeService knowledgeService,
                               TicketVectorService ticketVectorService,
                               TicketExportJobService ticketExportJobService,
                               TicketAccessService ticketAccessService,
                               TicketMaterialService ticketMaterialService,
                               TicketVisitService ticketVisitService) {
        this.ticketService = ticketService;
        this.knowledgeService = knowledgeService;
        this.ticketVectorService = ticketVectorService;
        this.ticketExportJobService = ticketExportJobService;
        this.ticketAccessService = ticketAccessService;
        this.ticketMaterialService = ticketMaterialService;
        this.ticketVisitService = ticketVisitService;
    }

    public Object dispatch(String capability, Map<String, Object> input,
                           long actorId, List<String> roles, String taskId) {
        Map<String, Object> args = input == null ? Map.of() : input;
        return switch (capability) {
            case "ticket.query" -> ticketService.listInternal(
                    actorId, roles, str(args.get("status")), str(args.get("keyword")),
                    intValue(args.get("limit"), 5, 1, 20));
            case "ticket.detail" -> {
                String ref = firstText(str(args.get("ticketId")), str(args.get("ticketNo")));
                yield ticketService.detailInternalRef(ref, actorId, roles);
            }
            case "ticket.similar" -> ticketVectorService.similarByQuery(
                    required(args, "query"), intValue(args.get("topK"), 5, 1, 20));
            case "knowledge.search" -> knowledgeService.search(new KnowledgeSearchRequest(
                    required(args, "query"), intValue(args.get("topK"), 5, 1, 20),
                    str(args.get("categoryCode"))));
            case "ticket.create" -> {
                requireCreateRole(roles);
                yield ticketService.create(buildCreateRequest(args), actorId, "agent-task:" + taskId);
            }
            case "ticket.export" -> {
                if (roles == null || roles.isEmpty()) {
                    throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "没有导出权限");
                }
                boolean privileged = roles.contains("ADMIN") || roles.contains("DISPATCHER")
                        || roles.contains("ENGINEER") || roles.contains("INSPECTOR");
                yield ticketExportJobService.export(
                        str(args.get("status")), str(args.get("keyword")),
                        str(args.get("priorityCode")), str(args.get("ticketTypeCode")),
                        str(args.get("location")), null,
                        privileged ? null : actorId,
                        parseTime(args.get("startTime")), parseTime(args.get("endTime")));
            }
            case "ticket.assign" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireReadable(ticketId, actorId, roles);
                requireManager(roles);
                Long assigneeId = longValue(args.get("assigneeId"));
                if (assigneeId == null) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "缺少参数: assigneeId");
                }
                yield ticketService.transition(ticketId, "assign",
                        transition(ticket.getVersion(), assigneeId, null, str(args.get("reason")),
                                null, null, null, null), actorId);
            }
            case "ticket.accept" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireHandlerOrUnassigned(ticketId, actorId, roles);
                yield ticketService.transition(ticketId, "accept",
                        transition(ticket.getVersion(), null, null, str(args.get("reason")),
                                null, null, null, null), actorId);
            }
            case "ticket.resolve" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireHandler(ticketId, actorId, roles);
                String serviceItemText = required(args, "serviceItemText");
                yield ticketService.transition(ticketId, "resolve",
                        transition(ticket.getVersion(), null, null, str(args.get("reason")),
                                null, null, str(args.get("onsiteSituation")), serviceItemText), actorId);
            }
            case "ticket.verify" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireReporterOrPrivileged(ticketId, actorId, roles);
                boolean approved = Boolean.parseBoolean(String.valueOf(args.getOrDefault("approved", true)));
                yield ticketService.transition(ticketId, "verify",
                        transition(ticket.getVersion(), null, approved, str(args.get("reason")),
                                null, null, null, null), actorId);
            }
            case "ticket.close" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireReadable(ticketId, actorId, roles);
                requireManager(roles);
                yield ticketService.transition(ticketId, "close",
                        transition(ticket.getVersion(), null, null, str(args.get("reason")),
                                str(args.get("closeReasonCode")), null, null, null), actorId);
            }
            case "ticket.cancel" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireReadable(ticketId, actorId, roles);
                requireManager(roles);
                yield ticketService.transition(ticketId, "cancel",
                        transition(ticket.getVersion(), null, null, str(args.get("reason")),
                                null, str(args.get("cancelReason")), null, null), actorId);
            }
            case "ticket.hold" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireHandler(ticketId, actorId, roles);
                yield ticketService.transition(ticketId, "hold",
                        transition(ticket.getVersion(), null, null, str(args.get("reason")),
                                null, null, null, null), actorId);
            }
            case "ticket.resume" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireHandler(ticketId, actorId, roles);
                yield ticketService.transition(ticketId, "resume",
                        transition(ticket.getVersion(), null, null, str(args.get("reason")),
                                null, null, null, null), actorId);
            }
            case "ticket.finish" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireReadable(ticketId, actorId, roles);
                requireManager(roles);
                yield ticketService.transition(ticketId, "finish",
                        transition(ticket.getVersion(), null, null, str(args.get("reason")),
                                null, null, null, null), actorId);
            }
            case "ticket.comment.add" -> {
                long ticketId = requiredTicketId(args);
                ticketAccessService.requireReadable(ticketId, actorId, roles);
                yield ticketService.addComment(ticketId, required(args, "content"),
                        str(args.getOrDefault("visibility", "PUBLIC")), actorId);
            }
            case "ticket.material.add" -> {
                long ticketId = requiredTicketId(args);
                ticketAccessService.requireReadable(ticketId, actorId, roles);
                requireHandleRole(roles);
                yield ticketMaterialService.add(ticketId, new AddTicketMaterialRequest(
                        required(args, "materialName"),
                        str(args.get("specification")),
                        decimal(args.get("quantity")),
                        str(args.get("unit")),
                        decimal(args.get("amount")),
                        str(args.get("remark"))), actorId);
            }
            case "ticket.visit.add" -> {
                long ticketId = requiredTicketId(args);
                ticketAccessService.requireReadable(ticketId, actorId, roles);
                requireManager(roles);
                yield ticketVisitService.add(ticketId, new AddTicketVisitRequest(
                        boolValue(args.get("required")),
                        str(args.get("visitMethod")),
                        str(args.get("visitedAt")),
                        str(args.get("visitResult")),
                        str(args.getOrDefault("status", "DONE")),
                        str(args.get("remark"))), actorId);
            }
            default -> throw new ApiException(ErrorCode.VALIDATION_ERROR, "不支持的能力: " + capability);
        };
    }

    private CreateTicketRequest buildCreateRequest(Map<String, Object> args) {
        return new CreateTicketRequest(
                str(args.get("title")),
                str(args.get("description")),
                required(args, "ticketTypeCode"),
                str(args.get("priorityCode")),
                str(args.get("locationText")),
                longValue(args.get("customerId")),
                longValue(args.get("roomId")),
                longValue(args.get("equipmentId")),
                longValue(args.get("projectId")),
                longValue(args.get("buildingId")),
                longValue(args.get("serviceItemId")),
                longValue(args.get("workflowId")),
                str(args.get("appointmentAt")),
                str(args.get("contactName")),
                str(args.get("addressText")),
                str(args.get("indoorPublicArea")),
                str(args.get("locationType")),
                str(args.get("serviceItemText"))
        );
    }

    private void requireCreateRole(List<String> roles) {
        if (roles == null || roles.stream().noneMatch(role ->
                List.of("ADMIN", "DISPATCHER", "ENGINEER", "REPORTER").contains(role))) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "没有建单权限");
        }
    }

    private void requireManager(List<String> roles) {
        if (roles == null || roles.stream().noneMatch(role ->
                List.of("ADMIN", "DISPATCHER").contains(role))) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "需要管理/调度权限");
        }
    }

    private void requireHandleRole(List<String> roles) {
        if (roles == null || roles.stream().noneMatch(role ->
                List.of("ADMIN", "DISPATCHER", "ENGINEER", "INSPECTOR").contains(role))) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "需要处理权限");
        }
    }

    private long requiredTicketId(Map<String, Object> args) {
        Long ticketId = longValue(args.get("ticketId"));
        if (ticketId == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "缺少参数: ticketId");
        }
        return ticketId;
    }

    private TransitionRequest transition(Integer version, Long assigneeId, Boolean approved,
                                         String reason, String closeReasonCode, String cancelReason,
                                         String onsiteSituation, String serviceItemText) {
        return new TransitionRequest(version, assigneeId, approved, reason,
                closeReasonCode, cancelReason, onsiteSituation, serviceItemText);
    }

    private java.math.BigDecimal decimal(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean boolValue(Object value) {
        if (value == null) {
            return null;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private String required(Map<String, Object> args, String key) {
        String value = str(args.get(key));
        if (!StringUtils.hasText(value)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "缺少参数: " + key);
        }
        return value;
    }

    private String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private Long longValue(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int intValue(Object value, int defaultValue, int min, int max) {
        try {
            int number = value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
            return Math.min(Math.max(number, min), max);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private LocalDateTime parseTime(Object value) {
        String text = str(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(text);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
