package com.github.wechat.cloudbot.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 提供共享的 {@link HttpClient} bean，避免每个 RestClient 各自创建独立的连接池。
 *
 * <p>各业务模块在装配 RestClient 时可注入此 bean 并通过
 * {@link RestClients.Builder#httpClient(HttpClient)} 传入。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient sharedHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
