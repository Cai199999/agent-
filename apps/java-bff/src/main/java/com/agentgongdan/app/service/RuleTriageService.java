package com.agentgongdan.app.service;

import com.agentgongdan.domain.chat.ChatTurn;
import com.agentgongdan.domain.triage.TriageResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则分诊兜底（MVP 降级能力）。
 * <p>仅在 Python 分诊不可用/超时时使用：生成低置信度草稿，仍由用户在界面确认后建单，
 * 不替代 LLM 分诊，也不做自动建单。</p>
 */
@Service
public class RuleTriageService {

    private static final int TITLE_MAX = 40;
    private static final int DESCRIPTION_MAX = 2000;
    private static final double RULE_CONFIDENCE = 0.45;

    private static final String[] FAULT_WORDS = {
            "报修", "故障", "坏了", "不制冷", "不制热", "漏水", "渗水", "堵塞", "停电", "跳闸",
            "漏电", "困人", "卡住", "异响", "冒烟", "起火", "燃气", "停水", "打不开", "无法",
            "失灵", "脱落", "破损", "噪音", "不工作", "异常"
    };
    private static final String[] QUERY_WORDS = {
            "怎么", "如何", "查询", "进度", "多久", "流程", "规范", "电话", "是什么", "为什么"
    };
    private static final String[] URGENT_WORDS = {
            "困人", "漏电", "触电", "起火", "火情", "冒烟", "燃气", "受伤", "坠落", "坍塌"
    };
    private static final String[] HIGH_WORDS = {
            "停水", "停电", "大面积", "漏水", "水浸", "门禁失效", "无法关门", "电梯"
    };
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "([A-Za-z0-9一二三四五六七八九十百]+(?:栋|幢|号楼|单元|层|楼|室|房|区|号|期))");

    public TriageResult fallback(List<ChatTurn> messages) {
        String content = lastUserMessage(messages);
        if (content == null || content.isBlank()) {
            return result("OTHER", "ANSWER", null, Map.of(), 0.0);
        }
        String text = content.trim();
        boolean fault = containsAny(text, FAULT_WORDS);
        boolean query = containsAny(text, QUERY_WORDS);
        String intent = fault ? "FAULT_REPORT" : (query ? "QUERY" : "OTHER");
        String action = fault ? "CREATE_DRAFT" : "ANSWER";
        Map<String, Object> draft = fault ? draftOf(text) : null;
        Map<String, Object> entities = entitiesOf(text, draft);
        return result(intent, action, draft, entities, RULE_CONFIDENCE);
    }

    private TriageResult result(String intent, String action, Map<String, Object> draft,
                                Map<String, Object> entities, double confidence) {
        return new TriageResult(
                intent,
                entities,
                confidence,
                action,
                confidence < 0.75,
                draft,
                "rule-fallback",
                "rule-v1",
                0L
        );
    }

    private Map<String, Object> draftOf(String text) {
        String type = ticketTypeOf(text);
        String priority = priorityOf(text);
        String location = locationOf(text);
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("title", titleOf(text));
        draft.put("description", text.length() > DESCRIPTION_MAX
                ? text.substring(0, DESCRIPTION_MAX) : text);
        draft.put("ticket_type_code", type);
        draft.put("priority_code", priority);
        draft.put("location_text", location);
        return draft;
    }

    private Map<String, Object> entitiesOf(String text, Map<String, Object> draft) {
        Map<String, Object> entities = new LinkedHashMap<>();
        entities.put("symptom", titleOf(text));
        entities.put("urgency", draft == null ? "" : draft.get("priority_code"));
        entities.put("location", draft == null ? locationOf(text) : draft.get("location_text"));
        entities.put("equipment", typeLabel(draft == null ? "GENERAL" : String.valueOf(draft.get("ticket_type_code"))));
        return entities;
    }

    private String lastUserMessage(List<ChatTurn> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatTurn turn = messages.get(i);
            if (turn != null && "user".equalsIgnoreCase(turn.role())
                    && turn.content() != null && !turn.content().isBlank()) {
                return turn.content();
            }
        }
        return null;
    }

    private String titleOf(String text) {
        String oneLine = text.replaceAll("\\s+", " ").trim();
        if (oneLine.length() <= TITLE_MAX) {
            return oneLine;
        }
        return oneLine.substring(0, TITLE_MAX) + "…";
    }

    private String ticketTypeOf(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (containsAny(lower, new String[]{"电梯", "扶梯", "升降", "号梯"})) {
            return "ELEVATOR";
        }
        if (containsAny(lower, new String[]{"消防", "火警", "烟感", "喷淋", "灭火"})) {
            return "FIRE";
        }
        if (containsAny(lower, new String[]{"水泵", "泵", "供水", "排水"})) {
            return "PUMP";
        }
        if (containsAny(lower, new String[]{"空调", "制冷", "制热", "新风", "风机盘管"})) {
            return "HVAC";
        }
        if (containsAny(lower, new String[]{"门禁", "门锁", "闸机", "道闸"})) {
            return "ACCESS";
        }
        if (containsAny(lower, new String[]{"网络", "宽带", "路由", "交换机", "wifi", "断网"})) {
            return "NETWORK";
        }
        if (containsAny(lower, new String[]{"漏电", "跳闸", "插座", "配电", "灯", "停电"})) {
            return "ELECTRICAL";
        }
        return "GENERAL";
    }

    private String priorityOf(String text) {
        if (containsAny(text, URGENT_WORDS)) {
            return "URGENT";
        }
        if (containsAny(text, HIGH_WORDS)) {
            return "HIGH";
        }
        return "NORMAL";
    }

    private String locationOf(String text) {
        Matcher matcher = LOCATION_PATTERN.matcher(text);
        StringBuilder location = new StringBuilder();
        int count = 0;
        while (matcher.find() && count < 2) {
            if (location.length() > 0) {
                location.append(' ');
            }
            location.append(matcher.group(1));
            count++;
        }
        return location.toString();
    }

    private String typeLabel(String type) {
        return switch (type) {
            case "ELEVATOR" -> "电梯";
            case "FIRE" -> "消防";
            case "PUMP" -> "水泵";
            case "HVAC" -> "空调";
            case "ACCESS" -> "门禁";
            case "NETWORK" -> "网络";
            case "ELECTRICAL" -> "电气";
            default -> "通用";
        };
    }

    private boolean containsAny(String text, String[] words) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
