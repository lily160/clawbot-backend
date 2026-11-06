-- --------------------------------------------------------
-- 打卡表加入服药记录类型（med_take）
-- 作用：健康打卡从「喝水/运动/早睡」扩展出「服药」记录：
--   kind = 'med_take'，同日一行；remark 存放当日已服药品名（、分隔），count 为当日服药次数
-- 变更：
--   1) remark 从 255 放宽到 500，容纳同日多种药品名
--   2) 增加 kind 取值约束，把 med_take 显式纳入打卡类型
-- MySQL 8.0 兼容：不使用 ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS
-- --------------------------------------------------------
ALTER TABLE health_checkin MODIFY COLUMN remark VARCHAR(500) DEFAULT NULL;

ALTER TABLE health_checkin ADD CONSTRAINT chk_health_checkin_kind
    CHECK (kind IN ('drink_water', 'exercise', 'sleep_early', 'med_take'));
