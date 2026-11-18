package com.agentgongdan.app.ports.security;

import java.util.List;

/**
 * 委托令牌签发/校验端口。MVP 使用 HMAC 签名，未来可替换为 OAuth2/JWT 密钥服务。
 */
public interface DelegationTokenPort {

    boolean enabled();

    IssuedDelegation issue(long actorId, List<String> roles, List<String> scopes, long ttlSeconds);

    DelegationClaims verify(String token);
}
