package com.github.wechat.cloudbot.platform.persistence.file;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.chat.infra.DeepSeekProperties;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import com.github.wechat.cloudbot.platform.model.ChatMessage;
import com.github.wechat.cloudbot.platform.persistence.ChatMemoryRepository;
import com.github.wechat.cloudbot.platform.persistence.ChatPromptAssembler;
import com.github.wechat.cloudbot.platform.persistence.PersistencePaths;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按用户保存最近多轮对话（归属 DeepSeek 主会话）。一轮 = user + assistant。
 *
 * <p>可选落盘到 {@code ./data/chat-memory/}；图片/视频历史只存文字占位，不写 Base64。
 * {@code cloudbot.persistence.type=file}（默认）时启用。
 */
@Component
@ConditionalOnProperty(
        prefix = "cloudbot.persistence",
        name = "type",
        havingValue = "file",
        matchIfMissing = true)
public class FileChatMemoryStore implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(FileChatMemoryStore.class);

    private final ConcurrentHashMap<String, Deque<ChatMessage>> store = new ConcurrentHashMap<>();
    private final DeepSeekProperties properties;
    private final CloudbotProperties cloudbotProperties;
    private final ChatPromptAssembler promptAssembler;
    private final ObjectMapper objectMapper;

    public FileChatMemoryStore(
            DeepSeekProperties properties,
            CloudbotProperties cloudbotProperties,
            ChatPromptAssembler promptAssembler,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.cloudbotProperties = cloudbotProperties;
        this.promptAssembler = promptAssembler;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadFromDisk() {
        if (!cloudbotProperties.getPersistence().isEnabled()) {
            return;
        }
        Path dir = PersistencePaths.chatMemoryDir(cloudbotProperties);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("无法创建对话记忆目录 {}: {}", dir, e.getMessage());
            return;
        }
        int users = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                if (loadUserFile(file)) {
                    users++;
                }
            }
        } catch (IOException e) {
            log.warn("扫描对话记忆目录失败: {}", e.getMessage());
            return;
        }
        if (users > 0) {
            log.info("已从本地加载对话记忆 users={}, dir={}", users, dir);
        }
    }

    @Override
    public List<ChatMessage> getHistory(String userId) {
        Deque<ChatMessage> deque = store.get(userId);
        if (deque == null || deque.isEmpty()) {
            return List.of();
        }
        return List.copyOf(deque);
    }

    @Override
    public void appendTurn(String userId, String userContent, String assistantContent) {
        Deque<ChatMessage> deque = store.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(ChatMessage.user(userContent));
            deque.addLast(ChatMessage.assistant(assistantContent));
            trim(deque);
            persistLocked(userId, deque);
        }
    }

    @Override
    public void clear(String userId) {
        store.remove(userId);
        if (!cloudbotProperties.getPersistence().isEnabled()) {
            return;
        }
        Path file = chatFile(userId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除对话记忆失败 userId={}: {}", userId, e.getMessage());
        }
    }

    @Override
    public boolean updateContent(long id, String content) {
        throw new UnsupportedOperationException("文件存储不支持按 id 更新消息，请使用 cloudbot.persistence.type=jdbc");
    }

    @Override
    public boolean deleteMessage(long id) {
        throw new UnsupportedOperationException("文件存储不支持按 id 删除消息，请使用 cloudbot.persistence.type=jdbc");
    }

    @Override
    public List<ChatMessage> buildMessages(String userId, String currentUserContent) {
        return promptAssembler.buildMessages(userId, getHistory(userId), currentUserContent);
    }

    @Override
    public List<ChatMessage> buildSearchRefineMessages(String userId, String refineUserContent) {
        return promptAssembler.buildSearchRefineMessages(userId, refineUserContent);
    }

    @Override
    public List<ChatMessage> buildVisionOnlyMessages(String dataUrl, String imagePrompt) {
        return promptAssembler.buildVisionOnlyMessages(dataUrl, imagePrompt);
    }

    @Override
    public List<ChatMessage> buildVideoVisionOnlyMessages(
            String dataUrl, String videoPrompt, double fps) {
        return promptAssembler.buildVideoVisionOnlyMessages(dataUrl, videoPrompt, fps);
    }

    private void trim(Deque<ChatMessage> deque) {
        int maxMessages = Math.max(1, properties.getMaxHistoryTurns()) * 2;
        while (deque.size() > maxMessages) {
            deque.pollFirst();
        }
    }

    private void persistLocked(String userId, Deque<ChatMessage> deque) {
        if (!cloudbotProperties.getPersistence().isEnabled()) {
            return;
        }
        Path file = chatFile(userId);
        try {
            Files.createDirectories(file.getParent());
            List<StoredMessage> rows = new ArrayList<>(deque.size());
            for (ChatMessage m : deque) {
                if (!(m.content() instanceof String text)) {
                    continue;
                }
                rows.add(new StoredMessage(m.role(), text));
            }
            StoredHistory payload = new StoredHistory(userId, rows);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), payload);
        } catch (IOException e) {
            log.warn("写入对话记忆失败 userId={}: {}", userId, e.getMessage());
        }
    }

    private boolean loadUserFile(Path file) {
        try {
            StoredHistory payload = objectMapper.readValue(file.toFile(), StoredHistory.class);
            if (payload == null
                    || payload.userId() == null
                    || payload.userId().isBlank()
                    || payload.messages() == null
                    || payload.messages().isEmpty()) {
                return false;
            }
            Deque<ChatMessage> deque = new ArrayDeque<>();
            for (StoredMessage row : payload.messages()) {
                if (row == null || row.role() == null || row.content() == null) {
                    continue;
                }
                String role = row.role().trim();
                if ("user".equals(role)) {
                    deque.addLast(ChatMessage.user(row.content()));
                } else if ("assistant".equals(role)) {
                    deque.addLast(ChatMessage.assistant(row.content()));
                }
            }
            if (deque.isEmpty()) {
                return false;
            }
            trim(deque);
            store.put(payload.userId(), deque);
            return true;
        } catch (Exception e) {
            log.warn("加载对话记忆失败 {}: {}", file, e.getMessage());
            return false;
        }
    }

    private Path chatFile(String userId) {
        return PersistencePaths.chatMemoryDir(cloudbotProperties)
                .resolve(PersistencePaths.safeFileName(userId) + ".json");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoredHistory(String userId, List<StoredMessage> messages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoredMessage(String role, String content) {}
}
