package com.github.wechat.cloudbot.modules.browse.infra;

import com.github.wechat.cloudbot.platform.config.RestClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class BrowseClientConfiguration {

    @Bean
    public RestClient browseRestClient(BrowseProperties properties, HttpClient sharedHttpClient) {
        return RestClients.builder()
                .httpClient(sharedHttpClient)
                .connectTimeoutMs(properties.getConnectTimeoutMs())
                .readTimeoutMs(properties.getReadTimeoutMs())
                .followRedirects(true)
                .userAgent(
                        "Mozilla/5.0 (compatible; CloudbotBrowse/0.1; +https://github.com/wechat/cloudbot)")
                .build();
    }
}
