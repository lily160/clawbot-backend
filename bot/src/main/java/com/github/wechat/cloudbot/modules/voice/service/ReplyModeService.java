package com.github.wechat.cloudbot.modules.voice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.chat.api.LlmGateway;
import com.github.wechat.cloudbot.modules.chat.infra.DeepSeekProperties;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import com.github.wechat.cloudbot.platform.model.ChatMessage;
import com.github.wechat.cloudbot.platform.persistence.PersistencePaths;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户回复模式：控制是否“用语音回复我”。
 */
@Service
public class ReplyModeService {

    private static final Logger log = LoggerFactory.getLogger(ReplyModeService.class);

    public enum ReplyMode {
        TEXT,
        VOICE
    }

    private static final String[] ENABLE_PATTERNS = {
            "用语音回复我",
            "语音回复我",
            "请语音回复",
            "语音模式",
            "以后都用语音",
            "开启语音回复",
            "改成语音回复",
            "以后都念给我听",
            "以后都播报给我",
            "以后都用语音念出来",
            "以后都用语音说"
    };

    private static final String[] DISABLE_PATTERNS = {
            "改成文字回复",
            "文字回复",
            "关闭语音",
            "关闭语音回复",
            "退出语音模式",
            "取消语音回复",
            "以后都文字回复"
    };

    private static final String[] ONE_SHOT_ENABLE_PATTERNS = {
            "这条用语音回复",
            "这一条用语音回复",
            "只这次用语音",
            "临时语音回复",
            "这次请用语音",
            "这句帮我念出来",
            "这句帮我播报一下",
            "这条帮我念出来",
            "这条帮我播报一下",
            "这一句请用语音",
            "这句请用语音",
            "帮我念出来",
            "帮我播报一下",
            "读出来"
    };

    private static final String[] ALL_COMMAND_PHRASES = {
            "用语音回复我",
            "语音回复我",
            "请语音回复",
            "语音模式",
            "以后都用语音",
            "开启语音回复",
            "改成语音回复",
            "以后都念给我听",
            "以后都播报给我",
            "以后都用语音念出来",
            "以后都用语音说",
            "改成文字回复",
            "文字回复",
            "关闭语音",
            "关闭语音回复",
            "退出语音模式",
            "取消语音回复",
            "以后都文字回复",
            "这条用语音回复",
            "这一条用语音回复",
            "只这次用语音",
            "临时语音回复",
            "这次请用语音",
            "这句帮我念出来",
            "这句帮我播报一下",
            "这条帮我念出来",
            "这条帮我播报一下",
            "这一句请用语音",
            "这句请用语音",
            "帮我念出来",
            "帮我播报一下",
            "读出来"
    };

    private final ConcurrentHashMap<String, ReplyMode> modeByUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> oneShotVoiceByUser = new ConcurrentHashMap<>();
    private final CloudbotProperties cloudbotProperties;
    private final DeepSeekProperties deepSeekProperties;
    private final ObjectMapper objectMapper;
    private final LlmGateway llmGateway;

    public ReplyModeService(
            CloudbotProperties cloudbotProperties,
            DeepSeekProperties deepSeekProperties,
            ObjectMapper objectMapper,
            LlmGateway llmGateway) {
        this.cloudbotProperties = cloudbotProperties;
        this.deepSeekProperties = deepSeekProperties;
        this.objectMapper = objectMapper;
        this.llmGateway = llmGateway;
    }

    @PostConstruct
    void loadFromDisk() {
        if (!cloudbotProperties.getPersistence().isEnabled()) {
            return;
        }
        Path file = PersistencePaths.root(cloudbotProperties).resolve("reply-modes.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Map<String, String> loaded = objectMapper.readValue(file.toFile(), new TypeReference<>() {});
            modeByUser.clear();
            if (loaded != null) {
                loaded.forEach((userId, raw) -> modeByUser.put(userId, parseMode(raw).orElse(ReplyMode.TEXT)));
            }
            log.info("已加载回复模式 users={}, file={}", modeByUser.size(), file);
        } catch (Exception e) {
            log.warn("加载回复模式失败 {}: {}", file, e.getMessage());
        }
    }

    public boolean isVoiceCommand(String userContent) {
        return detectVoiceInstruction(userContent).isPresent();
    }

    public Optional<String> tryHandleCommand(String userId, String userContent) {
        if (userContent == null) {
            return Optional.empty();
        }
        String trimmed = userContent.trim();
        if (matchesAny(trimmed, ENABLE_PATTERNS)) {
            setMode(userId, ReplyMode.VOICE);
            return Optional.of("已切换为语音回复模式。你之后的文字回复会尽量转成语音发给你。");
        }
        if (matchesAny(trimmed, DISABLE_PATTERNS)) {
            setMode(userId, ReplyMode.TEXT);
            return Optional.of("已切换为文字回复模式。之后我会正常发文字。");
        }
        if (matchesAny(trimmed, ONE_SHOT_ENABLE_PATTERNS)) {
            setOneShotVoice(userId);
            return Optional.of("好的，这一条我会用语音回复你。\n如果想一直语音回复，可以直接说：用语音回复我");
        }
        return Optional.empty();
    }

    /**
     * 规则优先，LLM 兜底。
     * 返回结构：
     * - modeChanged: 是否命中了语音/文字控制指令
     * - replyMode: 语音 / 文字
     * - content: 剥离后的正文（若空则表示只有指令）
     * - source: rule / llm / none
     */
    public Optional<IntentResult> detectVoiceInstruction(String userContent) {
        if (userContent == null || userContent.isBlank()) {
            return Optional.empty();
        }
        String trimmed = userContent.trim();

        ParsedReplyCommand rule = parseWholeCommand(trimmed);
        if (rule.modeChanged()) {
            return Optional.of(new IntentResult(rule.replyMode(), rule.content(), true, "rule"));
        }
        ParsedReplyCommand embedded = parseEmbeddedCommand(trimmed);
        if (embedded.modeChanged()) {
            return Optional.of(new IntentResult(embedded.replyMode(), embedded.content(), true, "rule"));
        }

        if (!deepSeekProperties.isEnabled() || !deepSeekProperties.hasApiKey()) {
            return Optional.empty();
        }

        try {
            String prompt =
                    "请判断下面这句中文是否包含‘希望用语音回复/播报/念出来’的意图。\n"
                            + "只输出严格 JSON，不要输出多余文字。格式如下：\n"
                            + "{\"voice\":true/false,\"once\":true/false,\"text\":\"剥离语音指令后的正文\"}\n"
                            + "规则：\n"
                            + "1. voice=true 表示希望用语音回复。\n"
                            + "2. once=true 表示只这一条用语音。\n"
                            + "3. text 是剥离掉语音意图后的剩余正文，没有正文则为空字符串。\n"
                            + "4. 如果用户只是普通提问，没有明显语音意图，则 voice=false。\n"
                            + "句子：" + trimmed;
            List<ChatMessage> messages = List.of(
                    ChatMessage.system("你是一个严格的中文意图分类器，只做语音回复意图识别。"),
                    ChatMessage.user(prompt));
            String raw = llmGateway.chat(messages);
            VoiceIntent intent = parseVoiceIntentJson(raw);
            if (intent == null || !intent.voice) {
                return Optional.empty();
            }
            String content = intent.text == null ? "" : intent.text.trim();
            if (content.isBlank()) {
                content = userContent;
            }
            return Optional.of(new IntentResult(ReplyMode.VOICE, content, intent.once, "llm"));
        } catch (Exception e) {
            log.warn("LLM 语音意图识别失败，回退规则模式: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public ReplyMode currentMode(String userId) {
        return modeByUser.getOrDefault(userId, ReplyMode.TEXT);
    }

    public boolean shouldReplyInVoice(String userId) {
        return consumeOneShotVoice(userId) || currentMode(userId) == ReplyMode.VOICE;
    }

    public ParsedReplyCommand parseEmbeddedCommand(String userContent) {
        if (userContent == null) {
            return ParsedReplyCommand.noChange(userContent);
        }
        String trimmed = userContent.trim();

        ParsedReplyCommand whole = parseWholeCommand(trimmed);
        if (whole.modeChanged()) {
            return whole;
        }

        ParsedReplyCommand prefix = parseAtEdge(trimmed, true);
        if (prefix.modeChanged()) {
            return prefix;
        }

        ParsedReplyCommand suffix = parseAtEdge(trimmed, false);
        if (suffix.modeChanged()) {
            return suffix;
        }

        ParsedReplyCommand middle = parseMiddleCommand(trimmed);
        if (middle.modeChanged()) {
            return middle;
        }

        return ParsedReplyCommand.noChange(userContent);
    }

    private ParsedReplyCommand parseWholeCommand(String text) {
        if (matchesAny(text, ONE_SHOT_ENABLE_PATTERNS)) {
            return ParsedReplyCommand.voice("");
        }
        if (matchesAny(text, ENABLE_PATTERNS)) {
            return ParsedReplyCommand.voice("");
        }
        if (matchesAny(text, DISABLE_PATTERNS)) {
            return ParsedReplyCommand.text("");
        }
        return ParsedReplyCommand.noChange(text);
    }

    private ParsedReplyCommand parseAtEdge(String text, boolean prefix) {
        for (String phrase : ALL_COMMAND_PHRASES) {
            int idx = text.indexOf(phrase);
            if (idx < 0) {
                continue;
            }
            boolean ok = prefix ? idx == 0 : idx + phrase.length() == text.length();
            if (!ok) {
                continue;
            }
            String rest = prefix ? text.substring(phrase.length()).trim() : text.substring(0, idx).trim();
            if (matchesAny(phrase, ONE_SHOT_ENABLE_PATTERNS)) {
                return ParsedReplyCommand.voice(rest);
            }
            if (matchesAny(phrase, ENABLE_PATTERNS)) {
                return ParsedReplyCommand.voice(rest);
            }
            if (matchesAny(phrase, DISABLE_PATTERNS)) {
                return ParsedReplyCommand.text(rest);
            }
        }
        return ParsedReplyCommand.noChange(text);
    }

    private ParsedReplyCommand parseMiddleCommand(String text) {
        for (String phrase : ALL_COMMAND_PHRASES) {
            int idx = text.indexOf(phrase);
            if (idx <= 0) {
                continue;
            }
            int end = idx + phrase.length();
            if (end >= text.length()) {
                continue;
            }
            String before = text.substring(0, idx).trim();
            String after = text.substring(end).trim();
            if (before.isBlank() || after.isBlank()) {
                continue;
            }
            if (matchesAny(phrase, ONE_SHOT_ENABLE_PATTERNS)) {
                return ParsedReplyCommand.voice(before);
            }
            if (matchesAny(phrase, ENABLE_PATTERNS)) {
                return ParsedReplyCommand.voice(before + "\n" + after);
            }
            if (matchesAny(phrase, DISABLE_PATTERNS)) {
                return ParsedReplyCommand.text(before + "\n" + after);
            }
        }
        return ParsedReplyCommand.noChange(text);
    }

    private void setOneShotVoice(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        oneShotVoiceByUser.put(userId, Boolean.TRUE);
    }

    private boolean consumeOneShotVoice(String userId) {
        return userId != null && oneShotVoiceByUser.remove(userId) != null;
    }

    private void setMode(String userId, ReplyMode mode) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        modeByUser.put(userId, mode);
        persist();
    }

    private void persist() {
        if (!cloudbotProperties.getPersistence().isEnabled()) {
            return;
        }
        Path file = PersistencePaths.root(cloudbotProperties).resolve("reply-modes.json");
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), Map.copyOf(modeByUser));
        } catch (IOException e) {
            log.warn("写入回复模式失败 {}: {}", file, e.getMessage());
        }
    }

    private static boolean matchesAny(String text, String[] patterns) {
        if (text == null) {
            return false;
        }
        String trimmed = text.trim();
        for (String pattern : patterns) {
            if (trimmed.equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private VoiceIntent parseVoiceIntentJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return parseVoiceIntentJsonStrict(raw);
        } catch (Exception strictEx) {
            try {
                int jsonStart = raw.indexOf('{');
                int jsonEnd = raw.lastIndexOf('}');
                if (jsonStart >= 0 && jsonEnd > jsonStart) {
                    return parseVoiceIntentJsonStrict(raw.substring(jsonStart, jsonEnd + 1));
                }
            } catch (Exception ignored) {
                return null;
            }
            return null;
        }
    }

    private VoiceIntent parseVoiceIntentJsonStrict(String raw) throws Exception {
        Map<?, ?> map = objectMapper.readValue(raw, Map.class);
        Object voiceObj = map.get("voice");
        if (!(voiceObj instanceof Boolean voice) || !voice) {
            return null;
        }
        boolean once = Boolean.TRUE.equals(map.get("once"));
        String text = map.get("text") == null ? "" : String.valueOf(map.get("text")).trim();
        return new VoiceIntent(true, once, text);
    }

    private static Optional<ReplyMode> parseMode(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        return switch (raw.trim().toUpperCase()) {
            case "VOICE" -> Optional.of(ReplyMode.VOICE);
            case "TEXT" -> Optional.of(ReplyMode.TEXT);
            default -> Optional.empty();
        };
    }

    public record ParsedReplyCommand(ReplyMode replyMode, String content, boolean modeChanged) {
        static ParsedReplyCommand noChange(String content) {
            return new ParsedReplyCommand(null, content, false);
        }

        static ParsedReplyCommand voice(String content) {
            return new ParsedReplyCommand(ReplyMode.VOICE, content, true);
        }

        static ParsedReplyCommand text(String content) {
            return new ParsedReplyCommand(ReplyMode.TEXT, content, true);
        }

        public boolean wantsVoice() {
            return replyMode == ReplyMode.VOICE;
        }
    }

    public record IntentResult(ReplyMode replyMode, String content, boolean once, String source) {
        public boolean wantsVoice() {
            return replyMode == ReplyMode.VOICE;
        }
    }

    private record VoiceIntent(boolean voice, boolean once, String text) {}
}
