package com.agentgongdan.adapters.security;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量登录限流：单 IP 60 秒内最多 10 次尝试。
 * 仅用于 v1 防暴力破解，不替代网关/Redis 限流。
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 10;
    private static final long WINDOW_MS = 60_000L;

    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> queue = attempts.computeIfAbsent(key == null ? "unknown" : key,
                ignored -> new ArrayDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst() > WINDOW_MS) {
                queue.pollFirst();
            }
            if (queue.size() >= MAX_ATTEMPTS) {
                return false;
            }
            queue.addLast(now);
            return true;
        }
    }

    public void reset(String key) {
        attempts.remove(key == null ? "unknown" : key);
    }
}
