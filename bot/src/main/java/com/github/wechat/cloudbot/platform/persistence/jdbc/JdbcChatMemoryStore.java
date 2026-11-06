package com.github.wechat.cloudbot.platform.persistence.jdbc;

import com.github.wechat.cloudbot.modules.chat.infra.DeepSeekProperties;
import com.github.wechat.cloudbot.platform.model.ChatMessage;
import com.github.wechat.cloudbot.platform.persistence.ChatMemoryRepository;
import com.github.wechat.cloudbot.platform.persistence.ChatPromptAssembler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JDBC 版对话记忆：按消息行存入关系库（MySQL / H2），支持存取、清空、单条更新/删除与历史截断。
 * {@code cloudbot.persistence.type=jdbc} 时启用。
 */
@Component
@ConditionalOnProperty(prefix = "cloudbot.persistence", name = "type", havingValue = "jdbc")
@DependsOnDatabaseInitialization
public class JdbcChatMemoryStore implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcChatMemoryStore.class);

    private final JdbcTemplate jdbc;
    private final DeepSeekProperties properties;
    private final ChatPromptAssembler promptAssembler;

    /**
     * 单 JVM 内每用户单调递增 seq 计数器（由 DB MAX(seq) 惰性播种）。
     * <p>修复：原来 appendTurn 里 {@code MAX(seq)+1} 无锁，主线程与 media 线程并发追加同一用户时
     * 会取到相同 seq，历史顺序错乱；内存计数器保证同进程内 seq 唯一单调。
     */
    private final ConcurrentHashMap<String, AtomicLong> seqCounters = new ConcurrentHashMap<>();

    public JdbcChatMemoryStore(
            JdbcTemplate jdbc,
            DeepSeekProperties properties,
            ChatPromptAssembler promptAssembler) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.promptAssembler = promptAssembler;
        log.info("对话记忆使用 JDBC 存储");
    }

    @PostConstruct
    public void ensureTableExists() {
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS chat_message ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                        + "user_id VARCHAR(128) NOT NULL, "
                        + "role VARCHAR(32) NOT NULL, "
                        + "content TEXT NOT NULL, "
                        + "seq INT NOT NULL, "
                        + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"
        );
        try {
            jdbc.execute(
                    "CREATE INDEX idx_chat_message_user_seq ON chat_message(user_id, seq)"
            );
        } catch (Exception ex) {
            String message = ex.getMessage();
            Throwable cause = ex.getCause();
            String causeMessage = cause == null ? null : cause.getMessage();
            // MySQL 报 "Duplicate key name"，H2/SQLite 报 "already exists"：都视为索引已存在，忽略
            boolean duplicateIndex = (message != null && (message.contains("Duplicate key name") || message.contains("already exists")))
                    || (causeMessage != null && (causeMessage.contains("Duplicate key name") || causeMessage.contains("already exists")));
            if (!duplicateIndex) {
                throw ex instanceof RuntimeException ? (RuntimeException) ex : new RuntimeException(ex);
            }
        }
    }

    @Override
    public List<ChatMessage> getHistory(String userId) {
        try {
            return jdbc.query(
                    "SELECT role, content FROM chat_message WHERE user_id = ? ORDER BY seq ASC",
                    (rs, rowNum) -> {
                        String role = rs.getString("role");
                        String content = rs.getString("content");
                        if ("user".equals(role)) {
                            return ChatMessage.user(content);
                        }
                        if ("assistant".equals(role)) {
                            return ChatMessage.assistant(content);
                        }
                        return new ChatMessage(role, content);
                    },
                    userId);
        } catch (org.springframework.dao.DataAccessException ex) {
            log.warn("读取聊天历史失败，返回空历史 userId={}, reason={}", userId, ex.getMessage());
            return List.of();
        }
    }

    @Override
    @Transactional
    public void appendTurn(String userId, String userContent, String assistantContent) {
        int nextSeq = nextSeq(userId);
        jdbc.update(
                "INSERT INTO chat_message (user_id, role, content, seq) VALUES (?, ?, ?, ?)",
                userId,
                "user",
                userContent == null ? "" : userContent,
                nextSeq);
        jdbc.update(
                "INSERT INTO chat_message (user_id, role, content, seq) VALUES (?, ?, ?, ?)",
                userId,
                "assistant",
                assistantContent == null ? "" : assistantContent,
                nextSeq + 1);
        trim(userId);
    }

    @Override
    @Transactional
    public void clear(String userId) {
        jdbc.update("DELETE FROM chat_message WHERE user_id = ?", userId);
        seqCounters.remove(userId);
    }

    @Override
    public boolean updateContent(long id, String content) {
        int updated =
                jdbc.update(
                        "UPDATE chat_message SET content = ? WHERE id = ?",
                        content == null ? "" : content,
                        id);
        return updated > 0;
    }

    @Override
    public boolean deleteMessage(long id) {
        int deleted = jdbc.update("DELETE FROM chat_message WHERE id = ?", id);
        return deleted > 0;
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

    /** 供测试：按用户查询消息主键（按 seq 升序）。 */
    List<Long> listIds(String userId) {
        return jdbc.query(
                "SELECT id FROM chat_message WHERE user_id = ? ORDER BY seq ASC",
                (rs, rowNum) -> rs.getLong("id"),
                userId);
    }

    private int nextSeq(String userId) {
        AtomicLong counter = seqCounters.computeIfAbsent(userId, id -> {
            Integer max = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(seq), 0) FROM chat_message WHERE user_id = ?",
                    Integer.class, id);
            return new AtomicLong(max == null ? 0L : max.longValue());
        });
        // 每轮 appendTurn 占用两个 seq（user + assistant），从 1 开始：1,3,5,...
        return (int) (counter.addAndGet(2) - 1);
    }

    private void trim(String userId) {
        int maxMessages = Math.max(1, properties.getMaxHistoryTurns()) * 2;
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM chat_message WHERE user_id = ?",
                        Integer.class,
                        userId);
        if (count == null || count <= maxMessages) {
            return;
        }
        int toDelete = count - maxMessages;
        List<Long> oldestIds =
                jdbc.query(
                        "SELECT id FROM chat_message WHERE user_id = ? ORDER BY seq ASC LIMIT ?",
                        (rs, rowNum) -> rs.getLong("id"),
                        userId,
                        toDelete);
        for (Long id : oldestIds) {
            jdbc.update("DELETE FROM chat_message WHERE id = ?", id);
        }
    }
}
