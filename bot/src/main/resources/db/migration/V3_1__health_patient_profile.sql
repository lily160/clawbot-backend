-- --------------------------------------------------------
-- 档案模块：就诊人健康档案表
-- 作用：存储用户及其家属的基础健康数据
-- MySQL 版本
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS health_patient_profile (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,

    -- 用户隔离标识
    wechat_user_id VARCHAR(128) NOT NULL,

    -- 基础属性
    relation VARCHAR(64) NOT NULL,
    name VARCHAR(255) DEFAULT NULL,
    gender VARCHAR(16) DEFAULT NULL,
    birth_year INT DEFAULT NULL,
    height INT DEFAULT NULL,
    weight DECIMAL(10,2) DEFAULT NULL,

    -- 二期真实身份信息（真实姓名/身份证/手机号），与 PatientProfileEntity 对齐
    real_name VARCHAR(128) DEFAULT NULL,
    id_card VARCHAR(64) DEFAULT NULL,
    phone VARCHAR(64) DEFAULT NULL,

    -- 健康特征 (JSON文本存储数组)
    allergies TEXT DEFAULT NULL,
    chronic_diseases TEXT DEFAULT NULL,
    medical_history TEXT DEFAULT NULL,
    -- 状态控制字段
    status INT NOT NULL DEFAULT 0,

    -- 审计与控制字段
    is_deleted INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 核心索引
CREATE INDEX idx_wechat_user ON health_patient_profile(wechat_user_id);

-- 唯一防重索引
CREATE UNIQUE INDEX uk_wechat_relation_name_status ON health_patient_profile(wechat_user_id, relation, name, status);
