package com.agentgongdan.adapters.ai;

import com.agentgongdan.app.ports.ai.ChatStreamHandler;
import com.agentgongdan.app.ports.ai.ChatStreamPort;
import com.agentgongdan.domain.chat.ChatContextChunk;
import com.agentgongdan.domain.chat.ChatStreamCommand;
import com.agentgongdan.domain.chat.ChatTurn;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python `/v1/agent/chat` SSE 适配器。
 * <p>只负责传输与 SSE 帧解析；事件语义、落库与对外 camelCase 转换在应用层完成。</p>
 */
@Component
public class PythonChatStreamAdapter implements ChatStreamPort {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public PythonChatStreamAdapter(@Qualifier("pythonStreamRestClient") RestClient restClient,
                                   ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public void stream(ChatStreamCommand command, ChatStreamHandler handler) {
        Map<String, Object> payload = buildPayload(command);
        try {
            restClient.post()
                    .uri("/v1/agent/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header("X-Trace-Id", command.traceId() == null ? "" : command.traceId())
                    .body(payload)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status < 200 || status >= 300) {
                            handler.onError("ai_unavailable", "AI 服务返回 " + status);
                            return null;
                        }
                        readStream(response.getBody(), handler);
                        return null;
                    });
        } catch (ResourceAccessException e) {
            handler.onError(isTimeout(e) ? "ai_llm_timeout" : "ai_unavailable",
                    isTimeout(e) ? "模型响应超时" : "AI 服务不可用");
        } catch (Exception e) {
            handler.onError("ai_unavailable", "AI 服务调用失败");
        }
    }

    private Map<String, Object> buildPayload(ChatStreamCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("trace_id", command.traceId());
        payload.put("session_id", command.sessionId());
        payload.put("assistant_message_id", command.assistantMessageId());
        if (command.model() != null && !command.model().isBlank()) {
            payload.put("model", command.model());
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatTurn turn : command.messages()) {
            Map<String, Object> item = new HashMap<>();
            item.put("role", turn.role());
            item.put("content", turn.content());
            messages.add(item);
        }
        payload.put("messages", messages);

        List<Map<String, Object>> chunks = new ArrayList<>();
        for (ChatContextChunk chunk : command.chunks()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("doc_id", chunk.docId());
            item.put("chunk_id", chunk.chunkId());
            item.put("title", chunk.title());
            item.put("content", chunk.content());
            item.put("score", chunk.score());
            item.put("rank", chunk.rank());
            chunks.add(item);
        }
        payload.put("context_chunks", chunks);
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("actor_id", command.actorId());
        actor.put("roles", command.roles() == null ? List.of() : command.roles());
        payload.put("actor", actor);
        return payload;
    }

    private void readStream(InputStream body, ChatStreamHandler handler) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (handler.isCancelled()) {
                    return;
                }
                if (line.isEmpty()) {
                    dispatch(event, data, handler);
                    event = null;
                    data = null;
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data == null) {
                        data = new StringBuilder();
                    } else {
                        data.append('\n');
                    }
                    data.append(line.substring(5).trim());
                }
            }
            dispatch(event, data, handler);
        }
    }

    private void dispatch(String event, StringBuilder data, ChatStreamHandler handler) {
        if (event == null || data == null || data.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(data.toString(), MAP_TYPE);
            handler.onFrame(event, parsed);
        } catch (Exception ignored) {
            // 单帧协议异常不终止整条流；由上游 done/error 或超时兜底。
        }
    }

    private boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
