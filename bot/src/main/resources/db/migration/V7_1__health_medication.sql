-- --------------------------------------------------------
-- 用药管理表：从本地 JSON 文件持久化统一到 MySQL（与打卡/档案模块一致）
-- 说明：
--   id 为应用生成的 UUID 字符串（保持用药提醒内容中的 [记录ID:xxx] 标记兼容）
--   reminder_times 存自定义每日提醒时间（HH:mm，逗号分隔，可为 NULL）
--   enabled=0 表示停用（删除/疗程到期）
-- MySQL 8.0 兼容：不使用 ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS
-- --------------------------------------------------------
CREATE TABLE health_medication (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    wechat_user_id VARCHAR(128) NOT NULL,
    drug_name VARCHAR(128) NOT NULL,
    dosage VARCHAR(64) NOT NULL,
    frequency VARCHAR(32) NOT NULL,
    course_days INT NOT NULL DEFAULT 0,
    notes VARCHAR(255) DEFAULT NULL,
    reminder_times VARCHAR(128) DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    enabled TINYINT(1) NOT NULL DEFAULT 1
);

CREATE INDEX idx_med_user_enabled ON health_medication(wechat_user_id, enabled);
