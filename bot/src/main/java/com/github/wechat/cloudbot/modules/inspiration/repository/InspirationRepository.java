package com.github.wechat.cloudbot.modules.inspiration.repository;

import com.github.wechat.cloudbot.modules.inspiration.model.Inspiration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

/**
 * 灵感笔记 MySQL 存储。
 */
@Repository
public class InspirationRepository {

    private static final Logger log = LoggerFactory.getLogger(InspirationRepository.class);

    private final JdbcTemplate jdbc;

    private static final RowMapper<Inspiration> ROW_MAPPER = (rs, rowNum) -> new Inspiration(
            rs.getLong("id"),
            rs.getString("topic"),
            rs.getString("content"),
            rs.getString("filename"),
            rs.getString("gist_url"),
            rs.getString("created_at"),
            rs.getString("updated_at")
    );

    public InspirationRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * 保存灵感笔记到数据库。
     *
     * @return 自增主键 ID，-1 表示失败
     */
    public long save(Inspiration insp) {
        String sql = """
            INSERT INTO inspirations (topic, content, filename, gist_url)
            VALUES (?, ?, ?, ?);
            """;
        try {
            var keyHolder = new GeneratedKeyHolder();
            jdbc.update(conn -> {
                PreparedStatement ps = conn.prepareStatement(sql, new String[]{"id"});
                ps.setString(1, insp.getTopic());
                ps.setString(2, insp.getContent());
                ps.setString(3, insp.getFilename());
                ps.setString(4, insp.getGistUrl());
                return ps;
            }, keyHolder);
            Number key = keyHolder.getKey();
            return key != null ? key.longValue() : -1;
        } catch (Exception e) {
            log.warn("保存灵感到数据库失败: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * 查询最近 N 条灵感笔记。
     */
    public List<Inspiration> findRecent(int limit) {
        String sql = "SELECT * FROM inspirations ORDER BY created_at DESC LIMIT ?;";
        try {
            return jdbc.query(sql, ROW_MAPPER, limit);
        } catch (Exception e) {
            log.warn("查询灵感列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 按 ID 查询。
     */
    public Optional<Inspiration> findById(long id) {
        String sql = "SELECT * FROM inspirations WHERE id = ?;";
        try {
            return jdbc.query(sql, ROW_MAPPER, id).stream().findFirst();
        } catch (Exception e) {
            log.warn("查询灵感 ID={} 失败: {}", id, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 按 ID 删除。
     *
     * @return 删除的行数
     */
    public int deleteById(long id) {
        String sql = "DELETE FROM inspirations WHERE id = ?;";
        try {
            return jdbc.update(sql, id);
        } catch (Exception e) {
            log.warn("删除灵感 ID={} 失败: {}", id, e.getMessage());
            return 0;
        }
    }
}
