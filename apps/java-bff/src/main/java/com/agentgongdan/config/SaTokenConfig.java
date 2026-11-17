package com.agentgongdan.config;

import cn.dev33.satoken.jwt.StpLogicJwtForStateless;
import cn.dev33.satoken.stp.StpLogic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token 显式 JWT 无状态模式。
 */
@Configuration
public class SaTokenConfig {

    @Bean
    public StpLogic stpLogicJwt() {
        return new StpLogicJwtForStateless();
    }
}
