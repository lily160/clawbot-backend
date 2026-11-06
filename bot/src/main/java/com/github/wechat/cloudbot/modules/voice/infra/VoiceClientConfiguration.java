package com.github.wechat.cloudbot.modules.voice.infra;

import com.github.wechat.cloudbot.modules.vision.infra.QwenProperties;
import com.github.wechat.cloudbot.platform.config.RestClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * 语音 TTS 使用 DashScope 原生 multimodal-generation 接口（与 VL compatible-mode 不同 baseUrl）。
 */
@Configuration
public class VoiceClientConfiguration {

    @Bean
    public RestClient voiceTtsRestClient(VoiceProperties voiceProperties, QwenProperties qwenProperties, HttpClient sharedHttpClient) {
        String apiKey = qwenProperties.getApiKey();
        return RestClients.builder()
                .httpClient(sharedHttpClient)
                .baseUrl(voiceProperties.getTtsApiBaseUrl(), "https://dashscope.aliyuncs.com/api/v1")
                .connectTimeoutMs(voiceProperties.getConnectTimeoutMs())
                .readTimeoutMs(voiceProperties.getReadTimeoutMs())
                .bearerToken(apiKey != null && !apiKey.isBlank() ? apiKey : null)
                .build();
    }
}
