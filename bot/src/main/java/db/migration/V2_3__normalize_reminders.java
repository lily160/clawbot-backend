package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.SQLException;
import java.sql.Statement;

/**
 * 跨数据库（MySQL / H2）的 reminders 表 + 索引迁移。
 *
 * <p>背景：旧版 V2_2 迁移曾是 MySQL 专用 SQL（information_schema.statistics + PREPARE/EXECUTE），
 * H2 测试库无法执行；同时老库可能已存在同名索引。本迁移升版为 V2_3，用纯 JDBC 实现幂等：
 * 建表（IF NOT EXISTS）+ 建索引，索引已存在时忽略重复错误。
 * <p>注意：老库 history 中残留的 V2_2 SQL 条目由 {@code FlywayRepairConfiguration} 过渡期内清理，
 * 迁移收敛后应移除该全局 repair。
 */
public class V2_3__normalize_reminders extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS reminders ("
                            + "id VARCHAR(64) PRIMARY KEY, "
                            + "user_id VARCHAR(128) NOT NULL, "
                            + "content TEXT NOT NULL, "
                            + "trigger_time DATETIME NOT NULL, "
                            + "cron_expression VARCHAR(128), "
                            + "advance_minutes INT NOT NULL DEFAULT 0, "
                            + "created_at DATETIME NOT NULL, "
                            + "enabled TINYINT NOT NULL DEFAULT 1, "
                            + "task_kind VARCHAR(32) NOT NULL DEFAULT 'ONE_TIME', "
                            + "task_state VARCHAR(32) NOT NULL DEFAULT 'PENDING')"
            );
            try {
                statement.execute(
                        "CREATE INDEX idx_reminders_user_enabled_time "
                                + "ON reminders(user_id, enabled, trigger_time)"
                );
            } catch (SQLException ex) {
                if (!isDuplicateIndexError(ex)) {
                    throw ex;
                }
            }
        }
    }

    /** 兼容各数据库的「索引已存在」报错（MySQL: Duplicate key name；H2/SQLite: already exists）。 */
    private static boolean isDuplicateIndexError(SQLException ex) {
        String message = ex.getMessage();
        if (message == null) {
            message = ex.getSQLState();
        }
        return message != null
                && (message.contains("Duplicate key name")
                        || message.contains("duplicate key")
                        || message.contains("already exists"));
    }
}
