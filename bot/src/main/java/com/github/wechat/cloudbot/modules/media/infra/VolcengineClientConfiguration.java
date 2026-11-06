package com.github.wechat.cloudbot.modules.media.infra;

import com.github.wechat.cloudbot.platform.config.RestClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 火山方舟 HTTP 客户端 + 媒体生成异步线程池。
 */
@Configuration
public class VolcengineClientConfiguration {

    @Bean
    public RestClient volcengineRestClient(VolcengineProperties properties, HttpClient sharedHttpClient) {
        return RestClients.builder()
                .httpClient(sharedHttpClient)
                .baseUrl(properties.getBaseUrl(), "https://ark.cn-beijing.volces.com/api/v3")
                .connectTimeoutMs(properties.getConnectTimeoutMs())
                .readTimeoutMs(properties.getReadTimeoutMs())
                .followRedirects(true)
                .bearerToken(properties.hasApiKey() ? properties.getApiKey() : null)
                .build();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService mediaGenerationExecutor(VolcengineProperties properties) {
        AtomicInteger n = new AtomicInteger();
        ThreadFactory factory =
                r -> {
                    Thread t = new Thread(r, "volc-media-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                };
        return Executors.newFixedThreadPool(properties.getThreadPoolSize(), factory);
    }
}
