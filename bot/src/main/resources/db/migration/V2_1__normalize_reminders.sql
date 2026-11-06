-- 统一任务表名为 reminders，并补齐状态字段。
-- 该迁移保留 2.1 版本，V2_1__chat_message.sql 已由 V1 覆盖并移除。
-- 注意：MySQL 8.0 不支持 ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS（MariaDB 语法），
-- 因此任务列直接内联在 CREATE TABLE 中，索引无条件创建（Flyway 每个迁移只执行一次）。
CREATE TABLE IF NOT EXISTS reminders (
    id VARCHAR(64) PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    content TEXT NOT NULL,
    trigger_time DATETIME NOT NULL,
    cron_expression VARCHAR(128),
    advance_minutes INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    enabled TINYINT NOT NULL DEFAULT 1,
    task_kind VARCHAR(32) NOT NULL DEFAULT 'ONE_TIME',
    task_state VARCHAR(32) NOT NULL DEFAULT 'PENDING'
);
