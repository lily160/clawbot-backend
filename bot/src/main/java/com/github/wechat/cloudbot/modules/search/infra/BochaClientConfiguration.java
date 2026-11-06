package com.github.wechat.cloudbot.modules.search.infra;

import com.github.wechat.cloudbot.platform.config.RestClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class BochaClientConfiguration {

    @Bean
    public RestClient bochaRestClient(BochaProperties properties, HttpClient sharedHttpClient) {
        return RestClients.builder()
                .httpClient(sharedHttpClient)
                .baseUrl(properties.getBaseUrl(), "https://api.bochaai.com/v1")
                .connectTimeoutMs(properties.getConnectTimeoutMs())
                .readTimeoutMs(properties.getReadTimeoutMs())
                .followRedirects(true)
                .bearerToken(properties.hasApiKey() ? properties.getApiKey() : null)
                .build();
    }
}
