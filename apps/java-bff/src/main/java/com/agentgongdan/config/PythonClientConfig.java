package com.agentgongdan.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class PythonClientConfig {

    @Bean("pythonRestClient")
    public RestClient pythonRestClient(
            RestClient.Builder builder,
            @Value("${PYTHON_BASE_URL:http://127.0.0.1:8300}") String baseUrl,
            @Value("${INTERNAL_TOKEN_J2P_CURRENT:}") String internalToken) {
        return builder
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    /**
     * 分诊专用客户端：连接 1s，读 10s；失败快速降级，不阻塞对话。
     */
    @Bean("pythonTriageRestClient")
    public RestClient pythonTriageRestClient(
            RestClient.Builder builder,
            @Value("${PYTHON_BASE_URL:http://127.0.0.1:8300}") String baseUrl,
            @Value("${INTERNAL_TOKEN_J2P_CURRENT:}") String internalToken,
            @Value("${TRIAGE_CONNECT_TIMEOUT_MS:1000}") int connectTimeoutMs,
            @Value("${TRIAGE_READ_TIMEOUT_MS:10000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    /**
     * 工单分析专用客户端：连接 1s，读 20s。
     */
    @Bean("pythonAnalysisRestClient")
    public RestClient pythonAnalysisRestClient(
            RestClient.Builder builder,
            @Value("${PYTHON_BASE_URL:http://127.0.0.1:8300}") String baseUrl,
            @Value("${INTERNAL_TOKEN_J2P_CURRENT:}") String internalToken,
            @Value("${ANALYSIS_READ_TIMEOUT_MS:20000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(readTimeoutMs);
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    /**
     * 对话流专用客户端：连接 1s，读空闲 60s；逐行读取不整体缓冲。
     */
    @Bean("pythonStreamRestClient")
    public RestClient pythonStreamRestClient(
            RestClient.Builder builder,
            @Value("${PYTHON_BASE_URL:http://127.0.0.1:8300}") String baseUrl,
            @Value("${INTERNAL_TOKEN_J2P_CURRENT:}") String internalToken,
            @Value("${PYTHON_STREAM_CONNECT_TIMEOUT_MS:1000}") int connectTimeoutMs,
            @Value("${PYTHON_STREAM_READ_TIMEOUT_MS:60000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }
}
