package com.github.wechat.cloudbot.modules.vision.infra;

import com.github.wechat.cloudbot.platform.config.RestClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class QwenClientConfiguration {

    @Bean
    public RestClient qwenRestClient(QwenProperties properties, HttpClient sharedHttpClient) {
        return RestClients.builder()
                .httpClient(sharedHttpClient)
                .baseUrl(properties.getBaseUrl(), "https://dashscope.aliyuncs.com/compatible-mode/v1")
                .connectTimeoutMs(properties.getConnectTimeoutMs())
                .readTimeoutMs(properties.getReadTimeoutMs())
                .bearerToken(properties.hasApiKey() ? properties.getApiKey() : null)
                .build();
    }
}
