package com.github.wechat.cloudbot.modules.vision.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.wechat.cloudbot.platform.config.ErrorCode;
import com.github.wechat.cloudbot.platform.config.Strings;
import com.github.wechat.cloudbot.platform.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通义千问 Chat Completions 客户端（DashScope OpenAI 兼容模式，支持 VL 识图）。
 */
@Component
public class QwenClient {

    private static final Logger log = LoggerFactory.getLogger(QwenClient.class);

    private final RestClient restClient;
    private final QwenProperties properties;

    public QwenClient(
            @Qualifier("qwenRestClient") RestClient restClient, QwenProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    public boolean isAvailable() {
        return properties.isEnabled() && properties.hasApiKey();
    }

    /** 文本对话，使用 {@code qwen.model}。 */
    public String chat(List<ChatMessage> messages) {
        return chat(messages, false);
    }

    /** 视觉对话，使用 {@code qwen.vision-model}。 */
    public String chatVision(List<ChatMessage> messages) {
        return chat(messages, true);
    }

    public String chat(List<ChatMessage> messages, boolean vision) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Qwen is disabled");
        }
        if (!properties.hasApiKey()) {
            throw new IllegalStateException("Qwen api-key is empty (set DASHSCOPE_API_KEY)");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }

        String model =
                vision
                        ? properties.getVisionModel()
                        : properties.getModel();

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("temperature", properties.getTemperature());
        body.put("messages", toApiMessages(messages));

        try {
            ChatCompletionResponse response =
                    restClient
                            .post()
                            .uri("/chat/completions")
                            .body(body)
                            .retrieve()
                            .body(ChatCompletionResponse.class);

            String content = extractContent(response);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("Qwen returned empty content");
            }
            return content.trim();
        } catch (RestClientResponseException e) {
            log.error("{}Qwen HTTP {} model={} : {}",
                    ErrorCode.VISION_API_ERROR.prefix(),
                    e.getStatusCode().value(),
                    model,
                   Strings.truncate(e.getResponseBodyAsString(), 300));
            throw new IllegalStateException("Qwen API error: " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Qwen chat failed: " + e.getMessage(), e);
        }
    }

    private static List<Map<String, Object>> toApiMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> list = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            Map<String, Object> item = new HashMap<>(2);
            item.put("role", m.role());
            item.put("content", m.content());
            list.add(item);
        }
        return list;
    }

    private static String extractContent(ChatCompletionResponse response) {
        if (response == null || response.choices == null || response.choices.isEmpty()) {
            return null;
        }
        Choice first = response.choices.get(0);
        if (first == null || first.message == null) {
            return null;
        }
        return first.message.content;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChatCompletionResponse {
        public List<Choice> choices;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Choice {
        public ApiMessage message;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ApiMessage {
        public String role;
        public String content;
    }
}
