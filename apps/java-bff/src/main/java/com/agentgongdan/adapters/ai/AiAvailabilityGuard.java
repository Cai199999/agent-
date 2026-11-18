package com.agentgongdan.adapters.ai;

import com.agentgongdan.app.ports.ai.AiAvailabilityPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量自愈熔断器（MVP）。
 * <p>按 scope 统计连续失败；达到阈值后打开一段时间，到期放行一次探测，
 * 成功即关闭，失败则等待时长翻倍（上限可配）。不引入额外依赖，P1 可替换为 Resilience4j。</p>
 */
@Component
public class AiAvailabilityGuard implements AiAvailabilityPort {

    private static final Logger log = LoggerFactory.getLogger(AiAvailabilityGuard.class);

    private enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final Map<String, ScopeState> scopes = new ConcurrentHashMap<>();
    private final int failureThreshold;
    private final int openSeconds;
    private final int maxOpenSeconds;

    public AiAvailabilityGuard(
            @Value("${AI_GUARD_FAILURE_THRESHOLD:3}") int failureThreshold,
            @Value("${AI_GUARD_OPEN_SECONDS:15}") int openSeconds,
            @Value("${AI_GUARD_MAX_OPEN_SECONDS:60}") int maxOpenSeconds) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openSeconds = Math.max(1, openSeconds);
        this.maxOpenSeconds = Math.max(this.openSeconds, maxOpenSeconds);
    }

    @Override
    public boolean allow(String scope) {
        ScopeState state = state(scope);
        synchronized (state) {
            long now = System.currentTimeMillis();
            if (state.state == State.OPEN) {
                if (now < state.openUntilMillis) {
                    return false;
                }
                state.state = State.HALF_OPEN;
                state.probeInFlight = true;
                log.info("ai guard half-open probe, scope={}", scope);
                return true;
            }
            if (state.state == State.HALF_OPEN) {
                if (state.probeInFlight) {
                    return false;
                }
                state.probeInFlight = true;
                return true;
            }
            return true;
        }
    }

    @Override
    public void success(String scope) {
        ScopeState state = state(scope);
        synchronized (state) {
            if (state.state != State.CLOSED) {
                log.info("ai guard closed after probe success, scope={}", scope);
            }
            state.state = State.CLOSED;
            state.consecutiveFailures = 0;
            state.probeInFlight = false;
            state.openDurationSeconds = openSeconds;
            state.openUntilMillis = 0L;
        }
    }

    @Override
    public void failure(String scope) {
        ScopeState state = state(scope);
        synchronized (state) {
            state.consecutiveFailures++;
            if (state.state == State.HALF_OPEN || state.state == State.OPEN) {
                state.openDurationSeconds = Math.min(state.openDurationSeconds * 2, maxOpenSeconds);
                open(state);
                log.warn("ai guard re-opened, scope={}, wait={}s", scope, state.openDurationSeconds);
                return;
            }
            if (state.consecutiveFailures >= failureThreshold) {
                state.openDurationSeconds = openSeconds;
                open(state);
                log.warn("ai guard opened, scope={}, failures={}, wait={}s",
                        scope, state.consecutiveFailures, state.openDurationSeconds);
            }
        }
    }

    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        scopes.forEach((scope, state) -> result.put(scope, stateSnapshot(scope, state)));
        return result;
    }

    private void open(ScopeState state) {
        state.state = State.OPEN;
        state.probeInFlight = false;
        state.openUntilMillis = System.currentTimeMillis() + state.openDurationSeconds * 1000L;
    }

    private Map<String, Object> stateSnapshot(String scope, ScopeState state) {
        Map<String, Object> item = new LinkedHashMap<>();
        synchronized (state) {
            item.put("scope", scope);
            item.put("state", state.state.name());
            item.put("failures", state.consecutiveFailures);
            item.put("openSeconds", state.openDurationSeconds);
            long remaining = state.state == State.OPEN
                    ? Math.max(0L, (state.openUntilMillis - System.currentTimeMillis()) / 1000L)
                    : 0L;
            item.put("remainingSeconds", remaining);
        }
        return item;
    }

    private ScopeState state(String scope) {
        return scopes.computeIfAbsent(normalize(scope), ignored -> new ScopeState(openSeconds));
    }

    private String normalize(String scope) {
        return scope == null || scope.isBlank() ? "default" : scope;
    }

    private static final class ScopeState {
        private State state = State.CLOSED;
        private int consecutiveFailures;
        private int openDurationSeconds;
        private long openUntilMillis;
        private boolean probeInFlight;

        private ScopeState(int openSeconds) {
            this.openDurationSeconds = openSeconds;
        }
    }
}
