package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.AgentTaskEntity;
import com.agentgongdan.adapters.persistence.entity.ChatMessageEntity;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskMapper;
import com.agentgongdan.adapters.persistence.mapper.ChatMessageMapper;
import com.agentgongdan.adapters.persistence.mapper.ChatToolCallMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可观测性聚合：从既有业务表计算成功率、P95、token 与工具调用分布。
 * 不新增采集表；指标实时计算，适合 MVP 与中小数据量，后续可替换为指标库/数仓。
 */
@Service
public class AgentMetricsService {

    private static final long TENANT = 0L;
    private static final List<String> TERMINAL = List.of("SUCCEEDED", "FAILED", "CANCELLED", "REJECTED");
    private static final List<String> ACTIVE = List.of("PENDING", "RUNNING", "WAITING_CONFIRM");

    private final AgentTaskMapper agentTaskMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatToolCallMapper chatToolCallMapper;
    private final int latencySampleLimit;

    public AgentMetricsService(AgentTaskMapper agentTaskMapper,
                               ChatMessageMapper chatMessageMapper,
                               ChatToolCallMapper chatToolCallMapper,
                               @Value("${agent.metrics.latency-sample-limit:10000}") int latencySampleLimit) {
        this.agentTaskMapper = agentTaskMapper;
        this.chatMessageMapper = chatMessageMapper;
        this.chatToolCallMapper = chatToolCallMapper;
        this.latencySampleLimit = Math.max(100, latencySampleLimit);
    }

    public Map<String, Object> summary(LocalDateTime from, LocalDateTime to) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("from", format(from));
        summary.put("to", format(to));
        summary.put("agentTasks", agentTasks(from, to));
        summary.put("chat", chat(from, to));
        summary.put("toolCalls", toolCalls(from, to));
        return summary;
    }

    private Map<String, Object> agentTasks(LocalDateTime from, LocalDateTime to) {
        List<Map<String, Object>> statusRows = agentTaskMapper.selectMaps(
                agentTaskQuery()
                        .select("status AS status", "COUNT(*) AS cnt")
                        .ge("created_at", from).le("created_at", to)
                        .groupBy("status"));
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long total = 0;
        for (Map<String, Object> row : statusRows) {
            long count = longValue(row, "cnt");
            byStatus.put(stringValue(row, "status", "UNKNOWN"), count);
            total += count;
        }

        List<Map<String, Object>> capabilityRows = agentTaskMapper.selectMaps(
                agentTaskQuery()
                        .select("capability AS capability", "COUNT(*) AS cnt")
                        .ge("created_at", from).le("created_at", to)
                        .groupBy("capability"));
        Map<String, Long> byCapability = new LinkedHashMap<>();
        for (Map<String, Object> row : capabilityRows) {
            byCapability.put(stringValue(row, "capability", "UNKNOWN"), longValue(row, "cnt"));
        }

        Map<String, Object> totals = first(agentTaskMapper.selectMaps(
                agentTaskQuery()
                        .select("COALESCE(SUM(input_tokens),0) AS input_tokens",
                                "COALESCE(SUM(output_tokens),0) AS output_tokens",
                                "COALESCE(SUM(cost_micros),0) AS cost_micros")
                        .ge("created_at", from).le("created_at", to)));

        long succeeded = byStatus.getOrDefault("SUCCEEDED", 0L);
        long failed = byStatus.getOrDefault("FAILED", 0L);
        long cancelled = byStatus.getOrDefault("CANCELLED", 0L);
        long rejected = byStatus.getOrDefault("REJECTED", 0L);
        long terminal = succeeded + failed + cancelled + rejected;
        long active = ACTIVE.stream().mapToLong(status -> byStatus.getOrDefault(status, 0L)).sum();

        List<Long> latencies = latencies(agentTaskMapper.selectObjs(
                agentTaskQuery()
                        .select("latency_ms")
                        .ge("created_at", from).le("created_at", to)
                        .in("status", TERMINAL)
                        .isNotNull("latency_ms")
                        .orderByDesc("created_at")
                        .last("LIMIT " + latencySampleLimit)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("active", active);
        result.put("terminal", terminal);
        result.put("successRate", terminal == 0 ? null
                : BigDecimal.valueOf(succeeded)
                .divide(BigDecimal.valueOf(terminal), 4, RoundingMode.HALF_UP)
                .doubleValue());
        result.put("byStatus", byStatus);
        result.put("byCapability", byCapability);
        result.put("rejected", rejected);
        result.put("p95LatencyMs", percentile(latencies, 0.95));
        result.put("latencySamples", latencies.size());
        result.put("inputTokens", longValue(totals, "input_tokens"));
        result.put("outputTokens", longValue(totals, "output_tokens"));
        result.put("costMicros", longValue(totals, "cost_micros"));
        return result;
    }

    private Map<String, Object> chat(LocalDateTime from, LocalDateTime to) {
        Map<String, Object> totals = first(chatMessageMapper.selectMaps(
                new QueryWrapper<ChatMessageEntity>()
                        .select("COUNT(*) AS total",
                                "COALESCE(SUM(token_in),0) AS token_in",
                                "COALESCE(SUM(token_out),0) AS token_out",
                                "COALESCE(AVG(latency_ms),0) AS avg_latency_ms",
                                "COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END),0) AS failed")
                        .eq("tenant_id", TENANT)
                        .eq("role", "ASSISTANT")
                        .isNull("deleted_at")
                        .ge("created_at", from).le("created_at", to)));

        List<Long> latencies = latencies(chatMessageMapper.selectObjs(
                new QueryWrapper<ChatMessageEntity>()
                        .select("latency_ms")
                        .eq("tenant_id", TENANT)
                        .eq("role", "ASSISTANT")
                        .isNull("deleted_at")
                        .ge("created_at", from).le("created_at", to)
                        .isNotNull("latency_ms")
                        .orderByDesc("created_at")
                        .last("LIMIT " + latencySampleLimit)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assistantMessages", longValue(totals, "total"));
        result.put("failed", longValue(totals, "failed"));
        result.put("inputTokens", longValue(totals, "token_in"));
        result.put("outputTokens", longValue(totals, "token_out"));
        result.put("avgLatencyMs", Math.round(doubleValue(totals, "avg_latency_ms")));
        result.put("p95LatencyMs", percentile(latencies, 0.95));
        result.put("latencySamples", latencies.size());
        return result;
    }

    private Map<String, Object> toolCalls(LocalDateTime from, LocalDateTime to) {
        List<Map<String, Object>> rows = chatToolCallMapper.selectMaps(
                new QueryWrapper<com.agentgongdan.adapters.persistence.entity.ChatToolCallEntity>()
                        .select("tool_name AS tool_name", "status AS status", "COUNT(*) AS cnt")
                        .eq("tenant_id", TENANT)
                        .ge("created_at", from).le("created_at", to)
                        .groupBy("tool_name", "status"));
        Map<String, Long> byTool = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        long total = 0;
        for (Map<String, Object> row : rows) {
            long count = longValue(row, "cnt");
            byTool.merge(stringValue(row, "tool_name", "UNKNOWN"), count, Long::sum);
            byStatus.merge(stringValue(row, "status", "UNKNOWN"), count, Long::sum);
            total += count;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("byTool", byTool);
        result.put("byStatus", byStatus);
        return result;
    }

    private QueryWrapper<AgentTaskEntity> agentTaskQuery() {
        return new QueryWrapper<AgentTaskEntity>().eq("tenant_id", TENANT);
    }

    private List<Long> latencies(List<Object> values) {
        List<Long> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Number number) {
                result.add(number.longValue());
            }
        }
        return result;
    }

    private Long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    private Map<String, Object> first(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private long longValue(Map<String, Object> row, String key) {
        if (row == null) {
            return 0L;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                if (entry.getValue() instanceof Number number) {
                    return number.longValue();
                }
                try {
                    return Long.parseLong(String.valueOf(entry.getValue()));
                } catch (NumberFormatException ignored) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    private double doubleValue(Map<String, Object> row, String key) {
        if (row == null) {
            return 0d;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                if (entry.getValue() instanceof Number number) {
                    return number.doubleValue();
                }
                try {
                    return Double.parseDouble(String.valueOf(entry.getValue()));
                } catch (NumberFormatException ignored) {
                    return 0d;
                }
            }
        }
        return 0d;
    }

    private String stringValue(Map<String, Object> row, String key, String fallback) {
        if (row == null) {
            return fallback;
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue() == null ? fallback : String.valueOf(entry.getValue());
            }
        }
        return fallback;
    }

    private String format(LocalDateTime value) {
        return value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
