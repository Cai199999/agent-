package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.AgentTaskEventEntity;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskEventMapper;
import com.agentgongdan.app.dto.AgentTaskEvent;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 任务事件读写。事件是任务状态的可审计历史，SSE/轮询均读取此层。
 * <p>seq 为任务内单调序号：当前以“任务内最大值 + 1”分配，并以唯一键兜底并发冲突；
 * 升级时可替换为独立序列服务/Redis INCR，对外 afterSeq 契约不变。</p>
 */
@Service
public class AgentTaskEventService {

    private static final long TENANT = 0L;
    private static final int SEQ_RETRY_ATTEMPTS = 3;

    private final AgentTaskEventMapper eventMapper;

    public AgentTaskEventService(AgentTaskEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    public void record(String taskId, String status, String message) {
        record(taskId, typeFor(status), status, message);
    }

    public void record(String taskId, String type, String status, String message) {
        RuntimeException lastError = null;
        for (int attempt = 0; attempt < SEQ_RETRY_ATTEMPTS; attempt++) {
            AgentTaskEventEntity event = new AgentTaskEventEntity();
            event.setId(IdWorker.getId());
            event.setTenantId(TENANT);
            event.setTaskId(taskId);
            event.setSeq(nextSeq(taskId));
            event.setType(type == null ? "TASK_STATUS_CHANGED" : type);
            event.setStatus(status);
            event.setMessage(message);
            event.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
            try {
                eventMapper.insert(event);
                return;
            } catch (DuplicateKeyException ex) {
                // 并发追加事件时可能抢到同一个 seq，重新取号后再试。
                lastError = ex;
            }
        }
        throw lastError == null ? new IllegalStateException("事件写入失败") : lastError;
    }

    public List<AgentTaskEvent> list(String taskId) {
        return listAfter(taskId, null);
    }

    public List<AgentTaskEvent> listAfter(String taskId, Long afterSeq) {
        return eventMapper.selectList(Wrappers.<AgentTaskEventEntity>lambdaQuery()
                        .eq(AgentTaskEventEntity::getTenantId, TENANT)
                        .eq(AgentTaskEventEntity::getTaskId, taskId)
                        .gt(afterSeq != null, AgentTaskEventEntity::getSeq, afterSeq)
                        .orderByAsc(AgentTaskEventEntity::getSeq))
                .stream()
                .map(this::toView)
                .toList();
    }

    public static boolean isTerminal(String status) {
        return List.of("SUCCEEDED", "FAILED", "CANCELLED", "REJECTED").contains(status);
    }

    public static String typeFor(String status) {
        if (status == null) {
            return "TASK_STATUS_CHANGED";
        }
        return switch (status) {
            case "PENDING" -> "TASK_QUEUED";
            case "WAITING_CONFIRM" -> "TASK_CONFIRM_REQUIRED";
            case "RUNNING" -> "TASK_STARTED";
            case "SUCCEEDED" -> "TASK_SUCCEEDED";
            case "FAILED" -> "TASK_FAILED";
            case "CANCELLED" -> "TASK_CANCELLED";
            case "REJECTED" -> "TASK_REJECTED";
            default -> "TASK_STATUS_CHANGED";
        };
    }

    private long nextSeq(String taskId) {
        AgentTaskEventEntity last = eventMapper.selectOne(
                Wrappers.<AgentTaskEventEntity>lambdaQuery()
                        .eq(AgentTaskEventEntity::getTenantId, TENANT)
                        .eq(AgentTaskEventEntity::getTaskId, taskId)
                        .orderByDesc(AgentTaskEventEntity::getSeq)
                        .last("LIMIT 1"));
        if (last == null || last.getSeq() == null) {
            return 1L;
        }
        return last.getSeq() + 1;
    }

    private AgentTaskEvent toView(AgentTaskEventEntity item) {
        return new AgentTaskEvent(
                String.valueOf(item.getId()),
                item.getSeq() == null ? 0L : item.getSeq(),
                item.getTaskId(),
                item.getType(),
                item.getStatus(),
                item.getMessage(),
                item.getCreatedAt() == null ? null
                        : item.getCreatedAt().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }
}
