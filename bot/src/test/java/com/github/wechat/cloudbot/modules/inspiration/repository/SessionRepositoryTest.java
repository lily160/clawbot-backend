package com.github.wechat.cloudbot.modules.inspiration.repository;

import com.github.wechat.cloudbot.modules.inspiration.model.BotSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class SessionRepositoryTest {

    private SessionRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:session_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS bot_sessions (
                id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                session_id  VARCHAR(128) NOT NULL UNIQUE,
                user_id     VARCHAR(128) NOT NULL DEFAULT '',
                title       VARCHAR(255),
                state_data  TEXT,
                created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            """);
        repository = new SessionRepository(jdbc);
    }

    @Test
    void testSaveAndFindBySessionId() {
        BotSession session = new BotSession();
        session.setSessionId("session-001");
        session.setUserId("user1");
        session.setTitle("测试会话");
        session.setStateData("{\"key\": \"value\"}");

        int rows = repository.save(session);
        assertEquals(1, rows);

        var found = repository.findBySessionId("session-001");
        assertTrue(found.isPresent());
        assertEquals("user1", found.get().getUserId());
        assertEquals("{\"key\": \"value\"}", found.get().getStateData());
    }

    @Test
    void testSaveDuplicateSessionId() {
        BotSession s1 = new BotSession();
        s1.setSessionId("dup-session");
        s1.setUserId("user1");
        repository.save(s1);

        BotSession s2 = new BotSession();
        s2.setSessionId("dup-session"); // 相同 session_id
        s2.setUserId("user2");
        int rows = repository.save(s2);
        assertEquals(0, rows, "重复 session_id 应返回 0（违反 UNIQUE 约束）");
    }

    @Test
    void testFindBySessionIdNotFound() {
        var result = repository.findBySessionId("non-existent");
        assertFalse(result.isPresent());
    }

    @Test
    void testFindAll() {
        for (int i = 0; i < 3; i++) {
            BotSession s = new BotSession();
            s.setSessionId("s" + i);
            s.setUserId("u" + i);
            s.setTitle("Session " + i);
            repository.save(s);
        }

        var all = repository.findAll();
        assertEquals(3, all.size());
    }

    @Test
    void testFindAllEmpty() {
        var all = repository.findAll();
        assertTrue(all.isEmpty());
    }

    @Test
    void testUpdate() {
        BotSession session = new BotSession();
        session.setSessionId("updatable");
        session.setUserId("user1");
        session.setTitle("旧标题");
        session.setStateData("旧数据");
        repository.save(session);

        int updated = repository.update("updatable", "新标题", "新数据");
        assertEquals(1, updated);

        var found = repository.findBySessionId("updatable");
        assertTrue(found.isPresent());
        assertEquals("新标题", found.get().getTitle());
        assertEquals("新数据", found.get().getStateData());
    }

    @Test
    void testUpdateNotFound() {
        int updated = repository.update("non-existent", "标题", "数据");
        assertEquals(0, updated);
    }

    @Test
    void testDeleteBySessionId() {
        BotSession session = new BotSession();
        session.setSessionId("to-delete");
        session.setUserId("user1");
        repository.save(session);

        int deleted = repository.deleteBySessionId("to-delete");
        assertEquals(1, deleted);

        var found = repository.findBySessionId("to-delete");
        assertFalse(found.isPresent());
    }

    @Test
    void testDeleteBySessionIdNotFound() {
        int deleted = repository.deleteBySessionId("non-existent");
        assertEquals(0, deleted);
    }

    @Test
    void testDeleteOlderThanDays() throws Exception {
        // 插入一条"旧"会话（手动改 created_at）
        BotSession session = new BotSession();
        session.setSessionId("old-session");
        session.setUserId("user1");
        repository.save(session);
        jdbc.update("UPDATE bot_sessions SET created_at = TIMESTAMPADD(DAY, -10, CURRENT_TIMESTAMP), "
                + "updated_at = TIMESTAMPADD(DAY, -10, CURRENT_TIMESTAMP) WHERE session_id = ?",
                "old-session");

        // 插入一条新会话
        BotSession fresh = new BotSession();
        fresh.setSessionId("fresh-session");
        fresh.setUserId("user2");
        repository.save(fresh);

        // 清理 7 天前的
        int deleted = repository.deleteOlderThanDays(7);
        assertEquals(1, deleted);

        assertFalse(repository.findBySessionId("old-session").isPresent());
        assertTrue(repository.findBySessionId("fresh-session").isPresent());
    }
}
