package com.github.wechat.cloudbot.modules.persona.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import com.github.wechat.cloudbot.modules.chat.infra.DeepSeekProperties;
import com.github.wechat.cloudbot.platform.persistence.PersistencePaths;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按用户保存当前人格；解析「变身：xxx」咒语。可选落盘到 {@code ./data/personas.json}。
 */
@Service
public class PersonaService {

    private static final Logger log = LoggerFactory.getLogger(PersonaService.class);

    private static final Pattern SWITCH_PATTERN =
            Pattern.compile("^\\s*变身\\s*[:：]\\s*(.+?)\\s*$");

    private final ConcurrentHashMap<String, String> personaByUser = new ConcurrentHashMap<>();
    /** 系统提示缓存：userId → prompt，persona 切换时失效。 */
    private final ConcurrentHashMap<String, String> systemPromptCache = new ConcurrentHashMap<>();
    private final CloudbotProperties cloudbotProperties;
    private final DeepSeekProperties deepSeekProperties;
    private final ObjectMapper objectMapper;

    public PersonaService(
            CloudbotProperties cloudbotProperties,
            DeepSeekProperties deepSeekProperties,
            ObjectMapper objectMapper) {
        this.cloudbotProperties = cloudbotProperties;
        this.deepSeekProperties = deepSeekProperties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadFromDisk() {
        if (!cloudbotProperties.getPersistence().isEnabled()) {
            return;
        }
        Path file = PersistencePaths.personasFile(cloudbotProperties);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            if (Files.size(file) == 0L) {
                return;
            }
            Map<String, String> loaded =
                    objectMapper.readValue(file.toFile(), new TypeReference<>() {});
            if (loaded == null || loaded.isEmpty()) {
                return;
            }
            personaByUser.clear();
            personaByUser.putAll(loaded);
            systemPromptCache.clear();
            log.info("已从本地加载人格映射 users={}, file={}", personaByUser.size(), file);
        } catch (Exception e) {
            log.warn("加载人格文件失败 {}: {}", file, e.getMessage());
        }
    }

    public boolean isEnabled() {
        return cloudbotProperties.getFun().isPersonaSwitchEnabled();
    }

    /** 当前用户生效的 system prompt（无人格则回落配置默认）。结果缓存到 persona 切换。 */
    public String resolveSystemPrompt(String userId) {
        String cached = systemPromptCache.get(userId);
        if (cached != null) {
            return cached;
        }
        String personaId = personaByUser.get(userId);
        Optional<String> custom = PersonaCatalog.promptOf(personaId);
        String result;
        if (custom.isPresent()) {
            result = custom.get();
        } else {
            String fallback = deepSeekProperties.getSystemPrompt();
            result = fallback == null ? "" : fallback;
        }
        systemPromptCache.put(userId, result);
        return result;
    }

    public Optional<String> currentPersonaId(String userId) {
        return Optional.ofNullable(personaByUser.get(userId));
    }

    /**
     * 当前用户 TTS 语气指令：有人格则用人设专用指令，否则回落 yml 默认 {@code voice.tts-instructions}。
     */
    public Optional<String> resolveTtsInstructions(String userId) {
        return currentPersonaId(userId).flatMap(PersonaCatalog::ttsInstructionsOf);
    }

    /** 是否像变身咒语（仅检测，不改状态），供消息合并层短路处理。 */
    public boolean looksLikeSwitchCommand(String userContent) {
        if (!isEnabled() || userContent == null) {
            return false;
        }
        String trimmed = userContent.trim();
        if ("变身".equals(trimmed) || "变身：".equals(trimmed) || "变身:".equals(trimmed)) {
            return true;
        }
        return SWITCH_PATTERN.matcher(trimmed).matches();
    }

    /**
     * 若是变身咒语则执行并返回对用户的确认文案；否则 empty。
     */
    public Optional<String> tryHandleSwitchCommand(String userId, String userContent) {
        if (!isEnabled() || userContent == null) {
            return Optional.empty();
        }
        String trimmed = userContent.trim();
        if ("变身".equals(trimmed) || "变身：".equals(trimmed) || "变身:".equals(trimmed)) {
            return Optional.of(PersonaCatalog.listHint());
        }

        Matcher matcher = SWITCH_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            return Optional.empty();
        }

        String rawId = matcher.group(1).trim();
        String personaId = PersonaCatalog.canonicalId(rawId);
        if (!PersonaCatalog.isKnown(personaId)) {
            return Optional.of("未知人格「" + rawId + "」。" + PersonaCatalog.listHint());
        }

        if (PersonaCatalog.promptOf(personaId).isEmpty()) {
            personaByUser.remove(userId);
            systemPromptCache.remove(userId);
            persist();
            return Optional.of("已恢复默认人格，继续正常聊天吧～");
        }

        personaByUser.put(userId, personaId);
        systemPromptCache.remove(userId);
        persist();
        return Optional.of("变身成功：【" + personaId + "】已上线，之后回复会换成这套语气。");
    }

    private void persist() {
        if (!cloudbotProperties.getPersistence().isEnabled()) {
            return;
        }
        Path file = PersistencePaths.personasFile(cloudbotProperties);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            // 先写临时文件再原子替换，避免并发切换人格时两次写相互截断、写坏 JSON
            objectMapper.writeValue(tmp.toFile(), Map.copyOf(personaByUser));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("写入人格文件失败 {}: {}", file, e.getMessage());
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }
}
