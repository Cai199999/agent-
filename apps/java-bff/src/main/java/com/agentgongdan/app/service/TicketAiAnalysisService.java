package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.TicketAiAnalysisEntity;
import com.agentgongdan.adapters.persistence.entity.TicketEntity;
import com.agentgongdan.adapters.persistence.mapper.TicketAiAnalysisMapper;
import com.agentgongdan.adapters.persistence.mapper.TicketMapper;
import com.agentgongdan.adapters.web.dto.AnalysisCitationView;
import com.agentgongdan.adapters.web.dto.KnowledgeHitView;
import com.agentgongdan.adapters.web.dto.KnowledgeSearchRequest;
import com.agentgongdan.adapters.web.dto.TicketAiAnalysisView;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单 AI 初步分析：知识检索 + LLM 结构化建议 + 结果留存。
 */
@Service
public class TicketAiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(TicketAiAnalysisService.class);
    private static final long TENANT = 0L;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {
    };

    private final TicketMapper ticketMapper;
    private final TicketAiAnalysisMapper analysisMapper;
    private final KnowledgeService knowledgeService;
    private final RestClient analysisClient;
    private final ObjectMapper objectMapper;

    public TicketAiAnalysisService(TicketMapper ticketMapper,
                                   TicketAiAnalysisMapper analysisMapper,
                                   KnowledgeService knowledgeService,
                                   @Qualifier("pythonAnalysisRestClient") RestClient analysisClient,
                                   ObjectMapper objectMapper) {
        this.ticketMapper = ticketMapper;
        this.analysisMapper = analysisMapper;
        this.knowledgeService = knowledgeService;
        this.analysisClient = analysisClient;
        this.objectMapper = objectMapper;
    }

    public TicketAiAnalysisView analyze(long ticketId, long userId) {
        TicketEntity ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || ticket.getDeletedAt() != null) {
            throw new ApiException(ErrorCode.TICKET_NOT_FOUND);
        }
        List<KnowledgeHitView> hits = retrieveHits(ticket);

        Map<String, Object> ticketInfo = new LinkedHashMap<>();
        ticketInfo.put("ticket_no", ticket.getTicketNo());
        ticketInfo.put("title", ticket.getTitle());
        ticketInfo.put("description", ticket.getDescription());
        ticketInfo.put("ticket_type_code", ticket.getTicketTypeCode());
        ticketInfo.put("priority_code", ticket.getPriorityCode());
        ticketInfo.put("status_code", ticket.getStatusCode());
        ticketInfo.put("location_text", ticket.getLocationText());

        List<Map<String, Object>> chunks = new ArrayList<>();
        int rank = 1;
        for (KnowledgeHitView hit : hits) {
            Map<String, Object> chunk = new LinkedHashMap<>();
            chunk.put("doc_id", Long.parseLong(hit.docId()));
            chunk.put("chunk_id", Long.parseLong(hit.chunkId()));
            chunk.put("title", hit.title());
            chunk.put("content", hit.content());
            chunk.put("score", hit.score());
            chunk.put("rank", rank++);
            chunks.add(chunk);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trace_id", MDC.get("traceId"));
        payload.put("ticket", ticketInfo);
        payload.put("context_chunks", chunks);

        JsonNode response;
        try {
            response = analysisClient.post()
                    .uri("/v1/ai/ticket-analysis")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("ticket analysis failed, ticket={}, reason={}", ticketId, e.getMessage());
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, "AI 分析服务不可用");
        }
        if (response == null) {
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, "AI 分析服务无响应");
        }

        List<String> causes = readStrings(response.path("causes"));
        List<String> actions = readStrings(response.path("actions"));
        String summary = response.path("summary").asText("");
        String riskLevel = response.path("risk_level").asText("MEDIUM");
        String model = response.path("model").asText("");
        String promptVersion = response.path("prompt_version").asText("v1");
        int latencyMs = response.path("latency_ms").asInt(0);

        TicketAiAnalysisEntity entity = new TicketAiAnalysisEntity();
        entity.setId(IdWorker.getId());
        entity.setTenantId(TENANT);
        entity.setTicketId(ticketId);
        entity.setTraceId(MDC.get("traceId"));
        entity.setSummary(summary);
        entity.setCausesJson(toJson(causes));
        entity.setActionsJson(toJson(actions));
        entity.setRiskLevel(riskLevel);
        entity.setCitationsJson(toJson(citations(hits)));
        entity.setModel(model);
        entity.setPromptVersion(promptVersion);
        entity.setLatencyMs(latencyMs);
        entity.setCreatedBy(userId);
        analysisMapper.insert(entity);
        return toView(analysisMapper.selectById(entity.getId()));
    }

    public TicketAiAnalysisView latest(long ticketId) {
        TicketAiAnalysisEntity entity = analysisMapper.selectOne(
                Wrappers.<TicketAiAnalysisEntity>lambdaQuery()
                        .eq(TicketAiAnalysisEntity::getTenantId, TENANT)
                        .eq(TicketAiAnalysisEntity::getTicketId, ticketId)
                        .orderByDesc(TicketAiAnalysisEntity::getCreatedAt)
                        .orderByDesc(TicketAiAnalysisEntity::getId)
                        .last("LIMIT 1"));
        return entity == null ? null : toView(entity);
    }

    private List<KnowledgeHitView> retrieveHits(TicketEntity ticket) {
        String query = ((ticket.getTitle() == null ? "" : ticket.getTitle()) + "\n"
                + (ticket.getDescription() == null ? "" : ticket.getDescription())).trim();
        if (query.isEmpty()) {
            return List.of();
        }
        try {
            return knowledgeService.search(new KnowledgeSearchRequest(query, 5, null));
        } catch (Exception e) {
            log.warn("analysis knowledge retrieval degraded, ticket={}, reason={}",
                    ticket.getId(), e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> citations(List<KnowledgeHitView> hits) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (KnowledgeHitView hit : hits) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("docId", hit.docId());
            item.put("docNo", hit.docNo());
            item.put("title", hit.title());
            item.put("score", hit.score());
            result.add(item);
        }
        return result;
    }

    private TicketAiAnalysisView toView(TicketAiAnalysisEntity entity) {
        if (entity == null) {
            return null;
        }
        List<String> causes = fromJson(entity.getCausesJson(), STRING_LIST, List.of());
        List<String> actions = fromJson(entity.getActionsJson(), STRING_LIST, List.of());
        List<Map<String, Object>> rawCitations = fromJson(entity.getCitationsJson(), MAP_LIST, List.of());
        List<AnalysisCitationView> citations = rawCitations.stream()
                .map(item -> new AnalysisCitationView(
                        str(item.get("docId")),
                        str(item.get("docNo")),
                        str(item.get("title")),
                        item.get("score") instanceof Number number ? number.doubleValue() : null))
                .toList();
        return new TicketAiAnalysisView(
                String.valueOf(entity.getId()),
                String.valueOf(entity.getTicketId()),
                entity.getSummary(),
                causes,
                actions,
                entity.getRiskLevel(),
                citations,
                entity.getModel(),
                formatTime(entity.getCreatedAt())
        );
    }

    private List<String> readStrings(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String text = item.asText("");
                if (!text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String formatTime(LocalDateTime value) {
        return value == null ? null
                : value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
