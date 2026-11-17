package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.TicketEntity;
import com.agentgongdan.adapters.persistence.entity.TicketEventEntity;
import com.agentgongdan.adapters.persistence.entity.VectorObjectEntity;
import com.agentgongdan.adapters.persistence.mapper.TicketEventMapper;
import com.agentgongdan.adapters.persistence.mapper.TicketMapper;
import com.agentgongdan.adapters.persistence.mapper.VectorObjectMapper;
import com.agentgongdan.adapters.web.dto.SimilarTicketView;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单历史向量：建单/流转后异步索引，相似工单检索。
 * Python 负责 Embedding 与 Milvus；Java 负责业务数据、trace 与降级。
 */
@Service
public class TicketVectorService {

    private static final Logger log = LoggerFactory.getLogger(TicketVectorService.class);
    private static final long TENANT = 0L;

    private final TicketMapper ticketMapper;
    private final TicketEventMapper eventMapper;
    private final VectorObjectMapper vectorObjectMapper;
    private final RestClient pythonRestClient;
    private final TaskExecutor ticketVectorExecutor;
    private final String vectorCollection;

    public TicketVectorService(TicketMapper ticketMapper,
                               TicketEventMapper eventMapper,
                               VectorObjectMapper vectorObjectMapper,
                               @Qualifier("pythonRestClient") RestClient pythonRestClient,
                               @Qualifier("ticketVectorExecutor") TaskExecutor ticketVectorExecutor,
                               @Value("${MILVUS_COLLECTION_TICKET:agent_gongdan_ticket_bge_m3_1024_v1}")
                               String vectorCollection) {
        this.ticketMapper = ticketMapper;
        this.eventMapper = eventMapper;
        this.vectorObjectMapper = vectorObjectMapper;
        this.pythonRestClient = pythonRestClient;
        this.ticketVectorExecutor = ticketVectorExecutor;
        this.vectorCollection = vectorCollection;
    }

    public void indexAsync(TicketEntity ticket) {
        if (ticket == null) {
            return;
        }
        try {
            ticketVectorExecutor.execute(() -> indexSync(ticket));
        } catch (Exception e) {
            log.warn("ticket vector submit failed, ticket={}, reason={}", ticket.getId(), e.getMessage());
        }
    }

    public boolean indexSync(TicketEntity ticket) {
        if (ticket == null) {
            return false;
        }
        String vectorId = "ticket_" + ticket.getId();
        upsertVectorObject(ticket, vectorId, "PENDING");

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("ticket_id", ticket.getId());
        item.put("ticket_no", ticket.getTicketNo());
        item.put("ticket_version", ticket.getVersion());
        item.put("title", ticket.getTitle());
        item.put("description", ticket.getDescription());
        item.put("solution", latestSolution(ticket.getId()));
        item.put("status_code", ticket.getStatusCode());
        item.put("ticket_type_code", ticket.getTicketTypeCode());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("object_type", "TICKET");
        payload.put("items", List.of(item));
        try {
            JsonNode response = pythonRestClient.post()
                    .uri("/v1/vector/index")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
            boolean ok = response != null && response.path("indexed").asInt(0) > 0;
            updateVectorStatus(ticket.getId(), vectorId, ok ? "ACTIVE" : "FAILED");
            return ok;
        } catch (Exception e) {
            log.warn("ticket vector index failed, ticket={}, reason={}", ticket.getId(), e.getMessage());
            updateVectorStatus(ticket.getId(), vectorId, "FAILED");
            return false;
        }
    }

    public List<SimilarTicketView> similarForTicket(long ticketId, int topK) {
        TicketEntity ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || ticket.getDeletedAt() != null) {
            throw new ApiException(ErrorCode.TICKET_NOT_FOUND);
        }
        String query = ((ticket.getTitle() == null ? "" : ticket.getTitle()) + "\n"
                + (ticket.getDescription() == null ? "" : ticket.getDescription())).trim();
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        return search(query, topK, ticketId);
    }

    public List<SimilarTicketView> similarByQuery(String query, int topK) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        return search(query, topK, null);
    }

    public int reindexAll(int limit) {
        int size = Math.min(Math.max(limit, 1), 500);
        List<TicketEntity> tickets = ticketMapper.selectList(Wrappers.<TicketEntity>lambdaQuery()
                .eq(TicketEntity::getTenantId, TENANT)
                .isNull(TicketEntity::getDeletedAt)
                .orderByDesc(TicketEntity::getCreatedAt)
                .last("LIMIT " + size));
        int indexed = 0;
        for (TicketEntity ticket : tickets) {
            if (indexSync(ticket)) {
                indexed++;
            }
        }
        log.info("ticket vector reindex finished, total={}, indexed={}", tickets.size(), indexed);
        return indexed;
    }

    private List<SimilarTicketView> search(String query, int topK, Long excludeTicketId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("top_k", Math.min(Math.max(topK, 1), 20));
        if (excludeTicketId != null) {
            payload.put("exclude_ticket_id", excludeTicketId);
        }
        JsonNode response;
        try {
            response = pythonRestClient.post()
                    .uri("/v1/ai/similar/tickets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("similar tickets degraded, reason={}", e.getMessage());
            return List.of();
        }
        if (response == null || response.path("hits").isEmpty()) {
            return List.of();
        }

        List<Long> ids = new ArrayList<>();
        Map<Long, Double> scores = new HashMap<>();
        for (JsonNode hit : response.path("hits")) {
            long id = hit.path("ticket_id").asLong();
            if (id > 0) {
                ids.add(id);
                scores.put(id, hit.path("score").asDouble(0.0));
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, TicketEntity> tickets = new HashMap<>();
        for (TicketEntity ticket : ticketMapper.selectBatchIds(ids)) {
            if (ticket.getDeletedAt() == null) {
                tickets.put(ticket.getId(), ticket);
            }
        }
        List<SimilarTicketView> views = new ArrayList<>();
        for (Long id : ids) {
            TicketEntity ticket = tickets.get(id);
            if (ticket == null) {
                continue;
            }
            views.add(new SimilarTicketView(
                    String.valueOf(ticket.getId()),
                    ticket.getTicketNo(),
                    ticket.getTitle(),
                    ticket.getStatusCode(),
                    ticket.getPriorityCode(),
                    ticket.getTicketTypeCode(),
                    scores.getOrDefault(id, 0.0)
            ));
        }
        return views;
    }

    private String latestSolution(long ticketId) {
        TicketEventEntity event = eventMapper.selectOne(Wrappers.<TicketEventEntity>lambdaQuery()
                .eq(TicketEventEntity::getTicketId, ticketId)
                .eq(TicketEventEntity::getEventType, "RESOLVE")
                .orderByDesc(TicketEventEntity::getId)
                .last("LIMIT 1"));
        return event == null ? null : event.getReason();
    }

    private void upsertVectorObject(TicketEntity ticket, String vectorId, String status) {
        VectorObjectEntity existing = vectorObjectMapper.selectOne(
                Wrappers.<VectorObjectEntity>lambdaQuery()
                        .eq(VectorObjectEntity::getTenantId, TENANT)
                        .eq(VectorObjectEntity::getObjectType, "TICKET")
                        .eq(VectorObjectEntity::getObjectId, ticket.getId())
                        .last("LIMIT 1"));
        if (existing != null) {
            existing.setObjectVersion(ticket.getVersion());
            existing.setVectorId(vectorId);
            existing.setVectorCollection(vectorCollection);
            existing.setStatus(status);
            vectorObjectMapper.updateById(existing);
            return;
        }
        VectorObjectEntity entity = new VectorObjectEntity();
        entity.setId(IdWorker.getId());
        entity.setTenantId(TENANT);
        entity.setObjectType("TICKET");
        entity.setObjectId(ticket.getId());
        entity.setObjectVersion(ticket.getVersion());
        entity.setSeqNo(0);
        entity.setVectorId(vectorId);
        entity.setVectorCollection(vectorCollection);
        entity.setStatus(status);
        vectorObjectMapper.insert(entity);
    }

    private void updateVectorStatus(long ticketId, String vectorId, String status) {
        vectorObjectMapper.update(null, Wrappers.<VectorObjectEntity>lambdaUpdate()
                .eq(VectorObjectEntity::getTenantId, TENANT)
                .eq(VectorObjectEntity::getObjectType, "TICKET")
                .eq(VectorObjectEntity::getObjectId, ticketId)
                .eq(VectorObjectEntity::getVectorId, vectorId)
                .set(VectorObjectEntity::getStatus, status));
    }
}
