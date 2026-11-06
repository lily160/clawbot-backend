package com.github.wechat.cloudbot.modules.inspiration.repository;

import com.github.wechat.cloudbot.modules.inspiration.model.BotSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Bot 会话持久化存储（增删改查 + 过期清理）。
 */
@Repository
public class SessionRepository {

    private static final Logger log = LoggerFactory.getLogger(SessionRepository.class);

    private final JdbcTemplate jdbc;

    private static final RowMapper<BotSession> ROW_MAPPER = (rs, rowNum) -> new BotSession(
            rs.getLong("id"),
            rs.getString("session_id"),
            rs.getString("user_id"),
            rs.getString("title"),
            rs.getString("state_data"),
            rs.getString("created_at"),
            rs.getString("updated_at")
    );

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 保存新会话。
     */
    public int save(BotSession session) {
        String sql = """
            INSERT INTO bot_sessions (session_id, user_id, title, state_data)
            VALUES (?, ?, ?, ?);
            """;
        try {
            return jdbc.update(sql, session.getSessionId(), session.getUserId(),
                    session.getTitle(), session.getStateData());
        } catch (Exception e) {
            log.warn("保存会话失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 按 sessionId 查询。
     */
    public Optional<BotSession> findBySessionId(String sessionId) {
        String sql = "SELECT * FROM bot_sessions WHERE session_id = ?;";
        try {
            return jdbc.query(sql, ROW_MAPPER, sessionId).stream().findFirst();
        } catch (Exception e) {
            log.warn("查询会话 {} 失败: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 查询所有会话，按更新时间倒序。
     */
    public List<BotSession> findAll() {
        String sql = "SELECT * FROM bot_sessions ORDER BY updated_at DESC;";
        try {
            return jdbc.query(sql, ROW_MAPPER);
        } catch (Exception e) {
            log.warn("查询所有会话失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 更新会话标题和状态数据。
     *
     * @return 更新的行数（0 表示未找到）
     */
    public int update(String sessionId, String title, String stateData) {
        String sql = """
            UPDATE bot_sessions
            SET title = ?, state_data = ?, updated_at = CURRENT_TIMESTAMP
            WHERE session_id = ?;
            """;
        try {
            return jdbc.update(sql, title, stateData, sessionId);
        } catch (Exception e) {
            log.warn("更新会话 {} 失败: {}", sessionId, e.getMessage());
            return 0;
        }
    }

    /**
     * 按 sessionId 删除。
     */
    public int deleteBySessionId(String sessionId) {
        String sql = "DELETE FROM bot_sessions WHERE session_id = ?;";
        try {
            return jdbc.update(sql, sessionId);
        } catch (Exception e) {
            log.warn("删除会话 {} 失败: {}", sessionId, e.getMessage());
            return 0;
        }
    }

    /**
     * 删除 N 天前未更新的旧会话。
     *
     * @return 删除的行数
     */
    public int deleteOlderThanDays(int days) {
        String sql = "DELETE FROM bot_sessions WHERE updated_at < ?;";
        try {
            Timestamp cutoff = Timestamp.valueOf(LocalDateTime.now().minusDays(days));
            int deleted = jdbc.update(sql, cutoff);
            if (deleted > 0) {
                log.info("已清理 {} 条过期会话（超过 {} 天）", deleted, days);
            }
            return deleted;
        } catch (Exception e) {
            log.warn("清理过期会话失败: {}", e.getMessage());
            return 0;
        }
    }
}
