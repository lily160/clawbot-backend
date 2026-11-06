-- --------------------------------------------------------
-- 体检报告持久化：存储解析结果 + 指标历史，支持趋势对比
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS health_report_record (
    id              BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         VARCHAR(128) NOT NULL,
    source_filename VARCHAR(255) NOT NULL,
    page_count      INT NOT NULL DEFAULT 0,
    indicator_count INT NOT NULL DEFAULT 0,
    abnormal_count  INT NOT NULL DEFAULT 0,
    high_count      INT NOT NULL DEFAULT 0,
    low_count       INT NOT NULL DEFAULT 0,
    unknown_count   INT NOT NULL DEFAULT 0,
    report_data     TEXT DEFAULT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- MySQL 8.x 不支持 CREATE INDEX IF NOT EXISTS（MariaDB 语法），用存储过程做条件判断。
CREATE PROCEDURE IF NOT EXISTS _create_hrr_indexes()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'health_report_record' AND index_name = 'idx_hrr_user') THEN
        CREATE INDEX idx_hrr_user ON health_report_record(user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'health_report_record' AND index_name = 'idx_hrr_created') THEN
        CREATE INDEX idx_hrr_created ON health_report_record(user_id, created_at DESC);
    END IF;
END;
CALL _create_hrr_indexes();
DROP PROCEDURE IF EXISTS _create_hrr_indexes;

-- 单项指标历史（支持同指标跨报告对比）
CREATE TABLE IF NOT EXISTS health_indicator_history (
    id              BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    report_id       BIGINT NOT NULL,
    user_id         VARCHAR(128) NOT NULL,
    name            VARCHAR(128) NOT NULL,
    `value`         VARCHAR(64) NOT NULL,
    unit            VARCHAR(64) DEFAULT '',
    reference_range VARCHAR(128) DEFAULT '',
    status          VARCHAR(16) NOT NULL,
    page_no         INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE PROCEDURE IF NOT EXISTS _create_hih_indexes()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'health_indicator_history' AND index_name = 'idx_hih_user_name') THEN
        CREATE INDEX idx_hih_user_name ON health_indicator_history(user_id, name, created_at DESC);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'health_indicator_history' AND index_name = 'idx_hih_report') THEN
        CREATE INDEX idx_hih_report ON health_indicator_history(report_id);
    END IF;
END;
CALL _create_hih_indexes();
DROP PROCEDURE IF EXISTS _create_hih_indexes;
