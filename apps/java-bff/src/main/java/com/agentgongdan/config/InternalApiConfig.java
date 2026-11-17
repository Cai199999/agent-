package com.agentgongdan.config;

import com.agentgongdan.adapters.security.InternalTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InternalApiConfig implements WebMvcConfigurer {

    private final InternalTokenInterceptor internalTokenInterceptor;

    public InternalApiConfig(InternalTokenInterceptor internalTokenInterceptor) {
        this.internalTokenInterceptor = internalTokenInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(internalTokenInterceptor).addPathPatterns("/internal/**");
    }
}
