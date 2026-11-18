package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.SysUserEntity;
import com.agentgongdan.adapters.persistence.entity.TicketEntity;
import com.agentgongdan.adapters.persistence.mapper.SysUserMapper;
import com.agentgongdan.adapters.persistence.mapper.TicketMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工单导出（Phase A）：仅导出当前数据模型可直接提供的 16 列。
 * <p>不做 JOIN；先查工单，再按用户 ID 批量取显示名。</p>
 */
@Service
public class TicketExportService {

    public static final List<String> HEADERS = List.of(
            "单号", "登记时间", "工单类型", "处理状态", "登记人", "问题描述",
            "紧急程度", "数据来源", "接单时间", "接单人", "维修人员", "满意度",
            "当前节点", "当前节点人", "未处理", "完成时间"
    );

    private static final int MAX_ROWS = 10_000;
    private static final Set<String> TERMINAL = Set.of("CLOSED", "CANCELLED");
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Map<String, String> TYPE_LABELS = Map.of(
            "ELEVATOR", "电梯故障",
            "HVAC", "空调故障",
            "PUMP", "水泵故障",
            "FIRE", "消防故障",
            "ACCESS", "门禁故障",
            "NETWORK", "网络故障",
            "ELECTRICAL", "电气故障",
            "GENERAL", "通用",
            "OTHER", "其他"
    );
    private static final Map<String, String> STATUS_LABELS = Map.of(
            "PENDING", "待受理",
            "PENDING_ASSIGN", "待派单",
            "PENDING_ACCEPT", "待接单",
            "PROCESSING", "处理中",
            "SUSPENDED", "挂起",
            "PENDING_VERIFY", "待验证",
            "RESOLVED", "已解决",
            "CLOSED", "已关闭",
            "CANCELLED", "已取消"
    );
    private static final Map<String, String> PRIORITY_LABELS = Map.of(
            "LOW", "低",
            "NORMAL", "普通",
            "HIGH", "高",
            "URGENT", "紧急"
    );
    private static final Map<String, String> SOURCE_LABELS = Map.of(
            "FORM", "表单",
            "CHAT", "对话",
            "PHONE", "电话",
            "INSPECTION", "巡检",
            "AGENT", "Agent"
    );

    private final TicketMapper ticketMapper;
    private final SysUserMapper userMapper;

    public TicketExportService(TicketMapper ticketMapper, SysUserMapper userMapper) {
        this.ticketMapper = ticketMapper;
        this.userMapper = userMapper;
    }

    public List<List<String>> exportRows(String status, String keyword,
                                         String priorityCode, String ticketTypeCode,
                                         String location, Long assigneeId, Long scopeUserId,
                                         LocalDateTime startTime, LocalDateTime endTime) {
        var query = Wrappers.<TicketEntity>lambdaQuery()
                .eq(TicketEntity::getTenantId, 0L)
                .isNull(TicketEntity::getDeletedAt)
                .orderByDesc(TicketEntity::getCreatedAt)
                .last("LIMIT " + MAX_ROWS);
        if (StringUtils.hasText(status)) {
            query.eq(TicketEntity::getStatusCode, status);
        }
        if (StringUtils.hasText(keyword)) {
            String like = keyword.trim();
            query.and(wrapper -> wrapper.like(TicketEntity::getTitle, like)
                    .or().like(TicketEntity::getTicketNo, like));
        }
        if (StringUtils.hasText(priorityCode)) {
            query.eq(TicketEntity::getPriorityCode, priorityCode);
        }
        if (StringUtils.hasText(ticketTypeCode)) {
            query.eq(TicketEntity::getTicketTypeCode, ticketTypeCode);
        }
        if (StringUtils.hasText(location)) {
            query.like(TicketEntity::getLocationText, location.trim());
        }
        if (assigneeId != null) {
            query.eq(TicketEntity::getAssigneeId, assigneeId);
        }
        if (scopeUserId != null) {
            query.and(wrapper -> wrapper.eq(TicketEntity::getReporterId, scopeUserId)
                    .or().eq(TicketEntity::getAssigneeId, scopeUserId));
        }
        if (startTime != null) {
            query.ge(TicketEntity::getCreatedAt, startTime);
        }
        if (endTime != null) {
            query.le(TicketEntity::getCreatedAt, endTime);
        }

        List<TicketEntity> tickets = ticketMapper.selectList(query);
        Map<Long, String> userNames = userNames(tickets);
        return tickets.stream().map(ticket -> row(ticket, userNames)).toList();
    }

    private Map<Long, String> userNames(List<TicketEntity> tickets) {
        Set<Long> ids = tickets.stream()
                .flatMap(ticket -> java.util.stream.Stream.of(ticket.getReporterId(), ticket.getAssigneeId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (SysUserEntity user : userMapper.selectBatchIds(ids)) {
            names.put(user.getId(), user.getDisplayName());
        }
        return names;
    }

    private List<String> row(TicketEntity ticket, Map<Long, String> userNames) {
        String status = safe(label(STATUS_LABELS, ticket.getStatusCode()));
        String assignee = safe(userNames.getOrDefault(ticket.getAssigneeId(), ""));
        LocalDateTime completedAt = ticket.getClosedAt() != null ? ticket.getClosedAt() : ticket.getResolvedAt();
        List<String> row = new ArrayList<>(HEADERS.size());
        row.add(safe(ticket.getTicketNo()));
        row.add(formatDbLocal(ticket.getCreatedAt()));
        row.add(safe(label(TYPE_LABELS, ticket.getTicketTypeCode())));
        row.add(status);
        row.add(safe(userNames.getOrDefault(ticket.getReporterId(), "")));
        row.add(safe(ticket.getDescription()));
        row.add(safe(label(PRIORITY_LABELS, ticket.getPriorityCode())));
        row.add(safe(label(SOURCE_LABELS, ticket.getSourceCode())));
        row.add(formatUtc(ticket.getAcceptedAt()));
        row.add(assignee);
        row.add(assignee);
        row.add(ticket.getSatisfactionScore() == null ? "" : ticket.getSatisfactionScore() + " 星");
        row.add(status);
        row.add(assignee);
        row.add(TERMINAL.contains(ticket.getStatusCode()) ? "否" : "是");
        row.add(formatUtc(completedAt));
        return row;
    }

    private String label(Map<String, String> labels, String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        return labels.getOrDefault(code, code);
    }

    /**
     * created_at/updated_at 由 MySQL CURRENT_TIMESTAMP 写入，当前库为本地时区；直接按库值展示。
     */
    private String formatDbLocal(LocalDateTime value) {
        return value == null ? "" : TIME_FORMAT.format(value);
    }

    /**
     * accepted_at/resolved_at/closed_at 由应用以 UTC 写入；转换为展示时区。
     */
    private String formatUtc(LocalDateTime value) {
        if (value == null) {
            return "";
        }
        return TIME_FORMAT.format(value.atOffset(ZoneOffset.UTC).atZoneSameInstant(DISPLAY_ZONE));
    }

    /**
     * 防止 Excel 公式注入；同时过滤空字符。
     */
    private String safe(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace("\u0000", "");
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            return "'" + text;
        }
        return text;
    }
}
