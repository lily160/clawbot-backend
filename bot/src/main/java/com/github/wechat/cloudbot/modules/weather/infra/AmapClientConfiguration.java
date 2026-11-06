package com.github.wechat.cloudbot.modules.weather.infra;

import com.github.wechat.cloudbot.platform.config.RestClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class AmapClientConfiguration {

    @Bean
    public RestClient amapRestClient(AmapProperties properties, HttpClient sharedHttpClient) {
        return RestClients.builder()
                .httpClient(sharedHttpClient)
                .baseUrl(properties.getBaseUrl(), "https://restapi.amap.com")
                .connectTimeoutMs(properties.getConnectTimeoutMs())
                .readTimeoutMs(properties.getReadTimeoutMs())
                .followRedirects(true)
                .build();
    }
}
