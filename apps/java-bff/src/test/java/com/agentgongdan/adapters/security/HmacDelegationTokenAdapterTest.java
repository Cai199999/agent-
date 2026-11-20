package com.agentgongdan.adapters.security;

import com.agentgongdan.app.ports.security.DelegationClaims;
import com.agentgongdan.app.ports.security.IssuedDelegation;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 委托令牌契约测试：签发/校验、防篡改、过期、TTL 钳制与未启用保护。
 */
class HmacDelegationTokenAdapterTest {

    private static final String SECRET = "contract-test-secret-0123456789";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HmacDelegationTokenAdapter adapter =
            new HmacDelegationTokenAdapter(objectMapper, SECRET, "");

    @Test
    @DisplayName("签发后可校验，Actor/角色/scope 原样保留")
    void issueAndVerifyRoundTrip() {
        IssuedDelegation issued = adapter.issue(10001L, List.of("ADMIN"),
                List.of("ticket.query", "ticket.create"), 120);

        DelegationClaims claims = adapter.verify(issued.token());

        assertThat(adapter.enabled()).isTrue();
        assertThat(claims.delegationId()).isNotBlank();
        assertThat(claims.actorId()).isEqualTo(10001L);
        assertThat(claims.roles()).containsExactly("ADMIN");
        assertThat(claims.scopes()).containsExactly("ticket.query", "ticket.create");
        assertThat(claims.allows("ticket.create")).isTrue();
        assertThat(claims.allows("ticket.export")).isFalse();
        assertThat(claims.expiresAt() - claims.issuedAt()).isEqualTo(120);
    }

    @Test
    @DisplayName("TTL 钳制到 [60, 3600] 秒")
    void ttlIsClamped() {
        DelegationClaims shortLived = adapter.issue(1L, List.of(), List.of("*"), 1).claims();
        assertThat(shortLived.expiresAt() - shortLived.issuedAt()).isEqualTo(60);
        IssuedDelegation longLived = adapter.issue(1L, List.of(), List.of("*"), 99999);
        assertThat(longLived.claims().expiresAt() - longLived.claims().issuedAt()).isEqualTo(3600);
    }

    @Test
    @DisplayName("篡改签名或格式非法均被拒绝")
    void tamperedTokenRejected() {
        String token = adapter.issue(10001L, List.of("ADMIN"), List.of("*"), 120).token();

        assertThatThrownBy(() -> adapter.verify(token + "x"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
        assertThatThrownBy(() -> adapter.verify("not-a-token"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
        assertThatThrownBy(() -> adapter.verify(""))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
    }

    @Test
    @DisplayName("过期令牌返回 10002")
    void expiredTokenRejected() {
        String expired = sign(payload(10001L, Instant.now().getEpochSecond() - 60));

        assertThatThrownBy(() -> adapter.verify(expired))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
    }

    @Test
    @DisplayName("签发方不符或缺少 Actor 被拒绝")
    void issuerAndActorValidated() {
        Map<String, Object> wrongIssuer = payload(10001L, Instant.now().getEpochSecond() + 60);
        wrongIssuer.put("iss", "other-issuer");
        assertUnauthorized(sign(wrongIssuer));

        Map<String, Object> noActor = payload(0L, Instant.now().getEpochSecond() + 60);
        assertUnauthorized(sign(noActor));
    }

    @Test
    @DisplayName("密钥过短视为未启用，签发与校验均失败")
    void disabledWhenSecretTooShort() {
        HmacDelegationTokenAdapter disabled =
                new HmacDelegationTokenAdapter(objectMapper, "short", "");

        assertThat(disabled.enabled()).isFalse();
        assertThatThrownBy(() -> disabled.issue(1L, List.of(), List.of("*"), 120))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.SYS_INTERNAL_ERROR);
        assertThatThrownBy(() -> disabled.verify("a.b.c"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
    }

    private void assertUnauthorized(String token) {
        assertThatThrownBy(() -> adapter.verify(token))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
    }

    private Map<String, Object> payload(long actorId, long expiresAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", "agent-gongdan");
        payload.put("jti", "test-jti");
        payload.put("sub", String.valueOf(actorId));
        payload.put("roles", List.of("ADMIN"));
        payload.put("scopes", List.of("*"));
        payload.put("iat", Instant.now().getEpochSecond() - 120);
        payload.put("exp", expiresAt);
        return payload;
    }

    private String sign(Map<String, Object> payload) {
        try {
            String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            String body = header + "." + base64Url(objectMapper.writeValueAsString(payload));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return body + "." + base64Url(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
