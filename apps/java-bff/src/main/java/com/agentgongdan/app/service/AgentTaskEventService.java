package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.AgentTaskEventEntity;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskEventMapper;
import com.agentgongdan.app.dto.AgentTaskEvent;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 任务事件读写。事件是任务状态的可审计历史，SSE/轮询均读取此层。
 */
@Service
public class AgentTaskEventService {

    private static final long TENANT = 0L;

    private final AgentTaskEventMapper eventMapper;

    public AgentTaskEventService(AgentTaskEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    public void record(String taskId, String status, String message) {
        AgentTaskEventEntity event = new AgentTaskEventEntity();
        event.setId(IdWorker.getId());
        event.setTenantId(TENANT);
        event.setTaskId(taskId);
        event.setStatus(status);
        event.setMessage(message);
        event.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        eventMapper.insert(event);
    }

    public List<AgentTaskEvent> list(String taskId) {
        return listAfter(taskId, null);
    }

    public List<AgentTaskEvent> listAfter(String taskId, String afterEventId) {
        Long afterId = parseId(afterEventId);
        return eventMapper.selectList(Wrappers.<AgentTaskEventEntity>lambdaQuery()
                        .eq(AgentTaskEventEntity::getTenantId, TENANT)
                        .eq(AgentTaskEventEntity::getTaskId, taskId)
                        .gt(afterId != null, AgentTaskEventEntity::getId, afterId)
                        .orderByAsc(AgentTaskEventEntity::getId))
                .stream()
                .map(this::toView)
                .toList();
    }

    public static boolean isTerminal(String status) {
        return List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(status);
    }

    private AgentTaskEvent toView(AgentTaskEventEntity item) {
        return new AgentTaskEvent(
                String.valueOf(item.getId()),
                item.getTaskId(),
                item.getStatus(),
                item.getMessage(),
                item.getCreatedAt() == null ? null
                        : item.getCreatedAt().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }

    private Long parseId(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
