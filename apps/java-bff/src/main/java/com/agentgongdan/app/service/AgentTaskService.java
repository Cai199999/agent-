package com.agentgongdan.app.service;

import com.agentgongdan.adapters.web.dto.AgentTaskRequest;
import com.agentgongdan.adapters.web.dto.AgentTaskView;
import com.agentgongdan.adapters.web.dto.KnowledgeSearchRequest;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 子 Agent P0：只读 Task API。
 * 现阶段任务状态保存在内存，P1 再落 agent_task 表。
 */
@Service
public class AgentTaskService {

    private static final String AGENT_ID = "property-maintenance";
    private static final int MAX_TASKS = 500;

    private final TicketService ticketService;
    private final KnowledgeService knowledgeService;
    private final TicketVectorService ticketVectorService;
    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();

    public AgentTaskService(TicketService ticketService,
                            KnowledgeService knowledgeService,
                            TicketVectorService ticketVectorService) {
        this.ticketService = ticketService;
        this.knowledgeService = knowledgeService;
        this.ticketVectorService = ticketVectorService;
    }

    public Map<String, Object> manifest() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("agentId", AGENT_ID);
        manifest.put("name", "物业报修与工单 Agent");
        manifest.put("version", "0.1.0");
        manifest.put("protocol", "agent-task-v1");
        manifest.put("taskEndpoint", "/agent/v1/tasks");
        manifest.put("auth", Map.of("type", "internal-token", "header", "X-Internal-Token"));
        manifest.put("capabilities", List.of(
                capability("ticket.query", "查询当前 Actor 可见工单", "ticket:read"),
                capability("ticket.detail", "按工单 ID 或工单号查询详情", "ticket:read"),
                capability("ticket.similar", "按描述检索相似历史工单", "ticket:read"),
                capability("knowledge.search", "检索知识库片段", "knowledge:read")
        ));
        return manifest;
    }

    public AgentTaskView submit(AgentTaskRequest request, long actorId,
                                List<String> roles, String traceId) {
        if (request == null || !StringUtils.hasText(request.taskType())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "taskType 不能为空");
        }
        String idemKey = StringUtils.hasText(request.clientTaskId())
                ? actorId + ":" + request.clientTaskId().trim() : null;
        if (idemKey != null) {
            String existingId = idempotency.get(idemKey);
            if (existingId != null) {
                TaskRecord existing = tasks.get(existingId);
                if (existing != null) {
                    return existing.view();
                }
            }
        }

        if (tasks.size() >= MAX_TASKS) {
            throw new ApiException(ErrorCode.SYS_RATE_LIMITED, "任务缓存已满，请稍后重试");
        }

        String taskId = UUID.randomUUID().toString();
        long started = System.currentTimeMillis();
        String status = "SUCCEEDED";
        Object result = null;
        String errorCode = null;
        String errorMessage = null;
        try {
            result = dispatch(request.taskType(), request.input(), actorId, roles);
        } catch (ApiException ex) {
            status = "FAILED";
            errorCode = String.valueOf(ex.getErrorCode().getCode());
            errorMessage = ex.getMessage();
        } catch (Exception ex) {
            status = "FAILED";
            errorCode = String.valueOf(ErrorCode.SYS_INTERNAL_ERROR.getCode());
            errorMessage = ErrorCode.SYS_INTERNAL_ERROR.getMessage();
        }
        AgentTaskView view = new AgentTaskView(
                taskId,
                AGENT_ID,
                request.taskType(),
                status,
                result,
                errorCode,
                errorMessage,
                traceId,
                System.currentTimeMillis() - started,
                OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );
        tasks.put(taskId, new TaskRecord(view, actorId));
        if (idemKey != null) {
            idempotency.put(idemKey, taskId);
        }
        return view;
    }

    public AgentTaskView get(String taskId, long actorId) {
        TaskRecord record = tasks.get(taskId);
        if (record == null) {
            throw new ApiException(ErrorCode.TICKET_NOT_FOUND, "任务不存在");
        }
        if (record.actorId() != actorId) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "无权访问该任务");
        }
        return record.view();
    }

    private Object dispatch(String taskType, Map<String, Object> input,
                            long actorId, List<String> roles) {
        Map<String, Object> args = input == null ? Map.of() : input;
        return switch (taskType) {
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
            default -> throw new ApiException(ErrorCode.VALIDATION_ERROR, "不支持的能力: " + taskType);
        };
    }

    private Map<String, Object> capability(String name, String description, String permission) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("description", description);
        item.put("permission", permission);
        item.put("requiresConfirm", false);
        item.put("mode", "READ_ONLY");
        return item;
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

    private int intValue(Object value, int defaultValue, int min, int max) {
        try {
            int number = value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
            return Math.min(Math.max(number, min), max);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private record TaskRecord(AgentTaskView view, long actorId) {
    }
}
