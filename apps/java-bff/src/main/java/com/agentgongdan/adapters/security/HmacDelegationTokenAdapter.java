package com.agentgongdan.adapters.security;

import com.agentgongdan.app.ports.security.DelegationClaims;
import com.agentgongdan.app.ports.security.DelegationTokenPort;
import com.agentgongdan.app.ports.security.IssuedDelegation;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HMAC-SHA256 委托令牌适配器。
 * 令牌不落库，短时有效；密钥独立配置，未配置时回退 JWT_SECRET（仅限 MVP）。
 */
@Component
public class HmacDelegationTokenAdapter implements DelegationTokenPort {

    private static final String HEADER = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
    private static final String ISSUER = "agent-gongdan";

    private final ObjectMapper objectMapper;
    private final byte[] secret;

    public HmacDelegationTokenAdapter(
            ObjectMapper objectMapper,
            @Value("${AGENT_DELEGATION_SECRET:}") String delegationSecret,
            @Value("${JWT_SECRET:}") String jwtSecret) {
        this.objectMapper = objectMapper;
        String configured = StringUtils.hasText(delegationSecret) ? delegationSecret : jwtSecret;
        this.secret = configured == null ? new byte[0]
                : configured.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean enabled() {
        return secret.length >= 16;
    }

    @Override
    public IssuedDelegation issue(long actorId, List<String> roles,
                                  List<String> scopes, long ttlSeconds) {
        if (!enabled()) {
            throw new ApiException(ErrorCode.SYS_INTERNAL_ERROR, "未配置委托令牌密钥");
        }
        long now = Instant.now().getEpochSecond();
        long ttl = Math.min(Math.max(ttlSeconds, 60), 3600);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", ISSUER);
        payload.put("jti", UUID.randomUUID().toString().replace("-", ""));
        payload.put("sub", String.valueOf(actorId));
        payload.put("roles", roles == null ? List.of() : roles);
        payload.put("scopes", scopes == null ? List.of("*") : scopes);
        payload.put("iat", now);
        payload.put("exp", now + ttl);
        String body = HEADER + "." + base64Url(toJson(payload));
        String token = body + "." + sign(body);
        DelegationClaims claims = new DelegationClaims(
                String.valueOf(payload.get("jti")),
                actorId,
                roles == null ? List.of() : roles,
                scopes == null ? List.of("*") : scopes,
                now,
                now + ttl);
        return new IssuedDelegation(token, claims);
    }

    @Override
    public DelegationClaims verify(String token) {
        if (!enabled()) {
            throw new ApiException(ErrorCode.AUTH_UNAUTHORIZED, "委托令牌未启用");
        }
        if (!StringUtils.hasText(token)) {
            throw new ApiException(ErrorCode.AUTH_UNAUTHORIZED, "委托令牌无效");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new ApiException(ErrorCode.AUTH_UNAUTHORIZED, "委托令牌无效");
        }
        String expected = sign(parts[0] + "." + parts[1]);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.AUTH_UNAUTHORIZED, "委托令牌签名无效");
        }
        Map<String, Object> payload = parse(parts[1]);
        if (!ISSUER.equals(String.valueOf(payload.get("iss")))) {
            throw new ApiException(ErrorCode.AUTH_UNAUTHORIZED, "委托令牌签发方无效");
        }
        long exp = number(payload.get("exp"));
        if (exp <= Instant.now().getEpochSecond()) {
            throw new ApiException(ErrorCode.AUTH_TOKEN_EXPIRED, "委托令牌已过期");
        }
        long actorId = number(payload.get("sub"));
        if (actorId <= 0) {
            throw new ApiException(ErrorCode.AUTH_UNAUTHORIZED, "委托令牌缺少 Actor");
        }
        return new DelegationClaims(
                String.valueOf(payload.getOrDefault("jti", "")),
                actorId,
                strings(payload.get("roles")),
                strings(payload.get("scopes")),
                number(payload.get("iat")),
                exp);
    }

    private String sign(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.SYS_INTERNAL_ERROR, "委托令牌签名失败");
        }
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.SYS_INTERNAL_ERROR, "委托令牌序列化失败");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String body) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(body);
            return objectMapper.readValue(decoded, Map.class);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AUTH_UNAUTHORIZED, "委托令牌载荷无效");
        }
    }

    private long number(Object value) {
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
