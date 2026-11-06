package com.github.wechat.cloudbot.modules.inspiration.repository;

import com.github.wechat.cloudbot.modules.inspiration.model.Inspiration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class InspirationRepositoryTest {

    private InspirationRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:insp_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS inspirations (
                id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                topic       VARCHAR(255) NOT NULL,
                content     TEXT NOT NULL,
                filename    VARCHAR(255) NOT NULL,
                gist_url    VARCHAR(512),
                created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            """);
        repository = new InspirationRepository(ds);
        repository.save(new Inspiration(0L, "bootstrap", "bootstrap", "bootstrap.md", null, null, null));
        jdbc.execute("DELETE FROM inspirations");
    }

    @Test
    void testSaveAndFindById() {
        Inspiration insp = new Inspiration();
        insp.setTopic("测试主题");
        insp.setContent("测试内容");
        insp.setFilename("test.md");
        insp.setGistUrl("https://gist.github.com/test");

        long id = repository.save(insp);
        assertTrue(id >= 0, "保存后应返回非负 ID");

        var found = repository.findById(id);
        assertTrue(found.isPresent());
        assertEquals("测试主题", found.get().getTopic());
        assertEquals("test.md", found.get().getFilename());
        assertEquals("https://gist.github.com/test", found.get().getGistUrl());
    }

    @Test
    void testSaveWithoutGistUrl() {
        Inspiration insp = new Inspiration();
        insp.setTopic("本地笔记");
        insp.setContent("仅本地保存");
        insp.setFilename("local.md");

        long id = repository.save(insp);
        assertTrue(id >= 0);

        var found = repository.findById(id);
        assertTrue(found.isPresent());
        assertNull(found.get().getGistUrl());
    }

    @Test
    void testFindRecent() {
        for (int i = 0; i < 5; i++) {
            Inspiration insp = new Inspiration();
            insp.setTopic("主题" + i);
            insp.setContent("内容" + i);
            insp.setFilename(i + ".md");
            repository.save(insp);
        }

        var result = repository.findRecent(3);
        assertEquals(3, result.size(), "应返回限制的 3 条");
    }

    @Test
    void testFindRecentLimitLargerThanData() {
        for (int i = 0; i < 3; i++) {
            Inspiration insp = new Inspiration();
            insp.setTopic("t" + i);
            insp.setContent("c" + i);
            insp.setFilename(i + ".md");
            repository.save(insp);
        }

        var result = repository.findRecent(10);
        assertEquals(3, result.size(), "数据不足时返回全部");
    }

    @Test
    void testFindRecentEmpty() {
        var result = repository.findRecent(10);
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindByIdNotFound() {
        var result = repository.findById(999);
        assertFalse(result.isPresent());
    }

    @Test
    void testDeleteById() {
        Inspiration insp = new Inspiration();
        insp.setTopic("待删除");
        insp.setContent("delete me");
        insp.setFilename("del.md");
        long id = repository.save(insp);

        int deleted = repository.deleteById(id);
        assertEquals(1, deleted);

        var found = repository.findById(id);
        assertFalse(found.isPresent());
    }

    @Test
    void testDeleteByIdNotFound() {
        int deleted = repository.deleteById(999);
        assertEquals(0, deleted);
    }
}
