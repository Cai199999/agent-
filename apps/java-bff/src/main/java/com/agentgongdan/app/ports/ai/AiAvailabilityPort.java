package com.agentgongdan.app.ports.ai;

import java.util.Map;

/**
 * AI 依赖可用性端口：轻量熔断 + 自愈探测。
 * <p>MVP 由适配器侧实现；P1 可替换为 Resilience4j，业务层不感知。</p>
 */
public interface AiAvailabilityPort {

    String SCOPE_CHAT = "chat";
    String SCOPE_TRIAGE = "triage";
    String SCOPE_ANALYSIS = "analysis";
    String SCOPE_RETRIEVAL = "retrieval";

    /**
     * 是否允许发起一次上游调用。打开状态下返回 false；到期后放行一次半开探测。
     */
    boolean allow(String scope);

    /**
     * 上游调用成功：关闭熔断并重置失败计数。
     */
    void success(String scope);

    /**
     * 上游调用失败：累计失败；达到阈值后打开熔断。
     */
    void failure(String scope);

    /**
     * 当前各 scope 状态，供健康检查与前端提示使用。
     */
    Map<String, Object> snapshot();
}
