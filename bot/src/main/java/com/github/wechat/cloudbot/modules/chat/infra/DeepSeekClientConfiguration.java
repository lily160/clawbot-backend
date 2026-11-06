package com.github.wechat.cloudbot.modules.chat.infra;

import com.github.wechat.cloudbot.platform.config.RestClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * DeepSeek HTTP 客户端装配。
 */
@Configuration
public class DeepSeekClientConfiguration {

    @Bean
    public RestClient deepSeekRestClient(DeepSeekProperties properties, HttpClient sharedHttpClient) {
        return RestClients.builder()
                .httpClient(sharedHttpClient)
                .baseUrl(properties.getBaseUrl(), "https://api.deepseek.com")
                .connectTimeoutMs(properties.getConnectTimeoutMs())
                .readTimeoutMs(properties.getReadTimeoutMs())
                .bearerToken(properties.hasApiKey() ? properties.getApiKey() : null)
                .build();
    }
}
