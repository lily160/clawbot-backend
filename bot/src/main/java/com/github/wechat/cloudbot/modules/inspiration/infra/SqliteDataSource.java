package com.github.wechat.cloudbot.modules.inspiration.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * SQLite 数据源配置与表结构初始化。
 *
 * <p>仅在 {@code cloudbot.datasource.enabled=true} 时生效，避免与主 MySQL 数据源冲突。
 * 连接 URL 由 {@link BotDatasourceProperties} 提供（对应 {@code cloudbot.datasource.url}）。
 */
@Configuration
@ConditionalOnProperty(name = "cloudbot.datasource.enabled", havingValue = "true", matchIfMissing = false)
public class SqliteDataSource {

    private static final Logger log = LoggerFactory.getLogger(SqliteDataSource.class);

    /**
     * SQLite DataSource（非连接池，单连接够用即可）。
     */
    @Bean
    DataSource botDataSource(BotDatasourceProperties properties) {
        String url = properties.getUrl();
        log.info("SQLite 数据源: {}", url);

        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl(url);
        initTables(ds);
        return ds;
    }

    // ---------- 建表 ----------

    private void initTables(DataSource dataSource) {
        String createInspirations = """
            CREATE TABLE IF NOT EXISTS inspirations (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                topic       TEXT    NOT NULL,
                content     TEXT    NOT NULL,
                filename    TEXT    NOT NULL,
                gist_url    TEXT,
                created_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
                updated_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
            );
            """;

        String createSessions = """
            CREATE TABLE IF NOT EXISTS bot_sessions (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id  TEXT    NOT NULL UNIQUE,
                user_id     TEXT    NOT NULL DEFAULT '',
                title       TEXT,
                state_data  TEXT,
                created_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
                updated_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
            );
            """;

        String createChatMessage = """
            CREATE TABLE IF NOT EXISTS chat_message (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id     TEXT    NOT NULL,
                role        TEXT    NOT NULL,
                content     TEXT    NOT NULL,
                seq         INTEGER NOT NULL,
                created_at  TEXT    NOT NULL DEFAULT (datetime('now','localtime'))
            );
            """;

        String createChatMessageIndex = """
            CREATE INDEX IF NOT EXISTS idx_chat_message_user_seq
            ON chat_message(user_id, seq);
            """;

        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute(createInspirations);
            stmt.execute(createSessions);
            // Flyway 可能因为 baseline-on-migrate 跳过 V1，但聊天记录表在 SQLite 启动时仍必须存在。
            stmt.execute(createChatMessage);
            stmt.execute(createChatMessageIndex);
            log.info("SQLite 表结构初始化完成");
        } catch (Exception e) {
            log.warn("SQLite 建表失败: {}", e.getMessage());
        }
    }
}
