package com.github.wechat.cloudbot.modules.chat.service;

import com.github.wechat.cloudbot.platform.model.ChatMessage;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import com.github.wechat.cloudbot.platform.config.Strings;
import com.github.wechat.cloudbot.modules.chat.infra.DeepSeekClient;
import com.github.wechat.cloudbot.platform.persistence.ChatMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 识别用户是否开启新话题；是则清空历史，避免把旧话题硬拽进新回复。
 */
@Component
public class  TopicShiftDetector {

    private static final Logger log = LoggerFactory.getLogger(TopicShiftDetector.class);

    private static final Pattern EXPLICIT_NEW_TOPIC =
            Pattern.compile(
                    ".*(换个话题|换话题|新话题|说点别的|说别的|不说这个了|换件事|另一个问题|换个问题).*");

    private static final String CLASSIFY_SYSTEM =
            "你是对话话题边界判定器。根据「最近对话」与「最新用户消息」，判断用户是继续旧话题还是开启全新话题。"
                    + "补充说明、追问、表情/语气延续、指代上文（这个/那个/刚才）→ CONTINUE；"
                    + "主题明显切换、与上文无关的新问题 → NEW。"
                    + "只输出 NEW 或 CONTINUE，不要其他文字。";

    private final CloudbotProperties cloudbotProperties;
    private final ChatMemoryRepository chatMemoryStore;
    private final DeepSeekClient deepSeekClient;

    public TopicShiftDetector(
            CloudbotProperties cloudbotProperties,
            ChatMemoryRepository chatMemoryStore,
            DeepSeekClient deepSeekClient) {
        this.cloudbotProperties = cloudbotProperties;
        this.chatMemoryStore = chatMemoryStore;
        this.deepSeekClient = deepSeekClient;
    }

    /**
     * 若判定为新话题则清空该用户历史。
     *
     * @return true 表示已清空历史（新话题）
     */
    public boolean clearHistoryIfNewTopic(String userId, String userContent) {
        if (!cloudbotProperties.isTopicShiftDetectionEnabled()) {
            return false;
        }
        if (userContent == null || userContent.isBlank()) {
            return false;
        }

        List<ChatMessage> history = chatMemoryStore.getHistory(userId);
        if (history.isEmpty()) {
            return false;
        }

        if (isExplicitNewTopic(userContent)) {
            chatMemoryStore.clear(userId);
            log.info("显式新话题，已清空历史 userId={}, msg={}", userId, Strings.summarizeOneLine(userContent));
            return true;
        }

        try {
            if (classifyAsNewTopic(history, userContent)) {
                chatMemoryStore.clear(userId);
                log.info("识别为新话题，已清空历史 userId={}, msg={}", userId, Strings.summarizeOneLine(userContent));
                return true;
            }
            log.debug("识别为继续旧话题 userId={}", userId);
            return false;
        } catch (Exception e) {
            // 判定失败时保留历史，避免误清空
            log.warn("话题切换识别失败，保留历史 userId={}: {}", userId, e.getMessage());
            return false;
        }
    }

    static boolean isExplicitNewTopic(String userContent) {
        return EXPLICIT_NEW_TOPIC.matcher(userContent.replace('\n', ' ')).matches();
    }

    private boolean classifyAsNewTopic(List<ChatMessage> history, String userContent) {
        String recent = formatRecentHistory(history, 6);
        String userPrompt =
                "【最近对话】\n"
                        + recent
                        + "\n\n【最新用户消息】\n"
                        + userContent.trim()
                        + "\n\n请判定：";

        List<ChatMessage> messages =
                List.of(ChatMessage.system(CLASSIFY_SYSTEM), ChatMessage.user(userPrompt));
        String answer = deepSeekClient.chat(messages);
        if (answer == null || answer.isBlank()) {
            return false;
        }
        String normalized = answer.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("NEW") && !normalized.contains("CONTINUE")) {
            return true;
        }
        if (normalized.startsWith("NEW")) {
            return true;
        }
        return false;
    }

    private static String formatRecentHistory(List<ChatMessage> history, int maxMessages) {
        int from = Math.max(0, history.size() - maxMessages);
        List<String> lines = new ArrayList<>();
        for (int i = from; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            String role =
                    switch (m.role()) {
                        case "user" -> "用户";
                        case "assistant" -> "助手";
                        default -> m.role();
                    };
            String contentText =
                    m.content() == null ? "" : String.valueOf(m.content());
            lines.add(role + "：" + Strings.summarizeOneLine(contentText, 120));
        }
        return String.join("\n", lines);
    }
}
