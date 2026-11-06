-- --------------------------------------------------------
-- 健康打卡模块：每日健康习惯打卡表
-- 作用：记录用户每日喝水/运动/早睡打卡，同日同类累加次数
-- MySQL版本（8.0 兼容：不使用 ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS）
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS health_checkin (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,

    -- 用户隔离标识 (最重要，千万不能串库)
    wechat_user_id VARCHAR(128) NOT NULL,

    -- 打卡类型：drink_water / exercise / sleep_early
    kind VARCHAR(32) NOT NULL,

    -- 打卡日期
    checkin_date DATE NOT NULL,

    -- 当日累计次数/量（如喝水杯数）
    count INT NOT NULL DEFAULT 1,

    -- 备注
    remark VARCHAR(255) DEFAULT NULL,

    -- 审计字段
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 核心索引：同一用户同一天同一类型的打卡唯一，用于累加
CREATE UNIQUE INDEX uk_user_kind_date ON health_checkin(wechat_user_id, kind, checkin_date);

-- 业务查询通常是「查某用户最近 N 天的打卡」
CREATE INDEX idx_user_date ON health_checkin(wechat_user_id, checkin_date);
