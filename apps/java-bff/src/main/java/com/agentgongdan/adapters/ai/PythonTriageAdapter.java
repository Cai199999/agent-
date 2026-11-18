package com.agentgongdan.adapters.ai;

import com.agentgongdan.app.ports.ai.AiAvailabilityPort;
import com.agentgongdan.app.ports.ai.TriagePort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.agentgongdan.domain.chat.ChatTurn;
import com.agentgongdan.domain.triage.TriageResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python `/v1/ai/triage` 同步适配器。
 */
@Component
public class PythonTriageAdapter implements TriagePort {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AiAvailabilityPort availability;

    public PythonTriageAdapter(@Qualifier("pythonTriageRestClient") RestClient restClient,
                               ObjectMapper objectMapper,
                               AiAvailabilityPort availability) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.availability = availability;
    }

    @Override
    public TriageResult triage(String traceId, List<ChatTurn> messages) {
        if (!availability.allow(AiAvailabilityPort.SCOPE_TRIAGE)) {
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, "AI 分诊暂时不可用（自动熔断中）");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trace_id", traceId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ChatTurn turn : messages) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", turn.role());
            item.put("content", turn.content());
            items.add(item);
        }
        payload.put("messages", items);

        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/v1/ai/triage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            availability.failure(AiAvailabilityPort.SCOPE_TRIAGE);
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, "分诊服务不可用");
        }
        if (response == null) {
            availability.failure(AiAvailabilityPort.SCOPE_TRIAGE);
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, "分诊服务无响应");
        }
        availability.success(AiAvailabilityPort.SCOPE_TRIAGE);

        Map<String, Object> entities = toMap(response.path("entities"));
        Map<String, Object> draft = response.path("draft").isNull() || response.path("draft").isMissingNode()
                ? null : toMap(response.path("draft"));
        return new TriageResult(
                response.path("intent_code").asText("OTHER"),
                entities,
                response.path("confidence").asDouble(0.0),
                response.path("action").asText("ANSWER"),
                response.path("low_confidence").asBoolean(true),
                draft,
                response.path("model").asText(""),
                response.path("prompt_version").asText("v1"),
                response.path("latency_ms").asLong(0)
        );
    }

    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(node, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
