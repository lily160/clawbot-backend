-- 灵感笔记 + 会话管理表
-- inspirations: 灵感笔记本地持久化（saveInspiration 工具）
CREATE TABLE IF NOT EXISTS inspirations (
    id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    topic       VARCHAR(255) NOT NULL,
    content     TEXT NOT NULL,
    filename    VARCHAR(255) NOT NULL,
    gist_url    VARCHAR(512),
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- bot_sessions: 会话状态持久化
CREATE TABLE IF NOT EXISTS bot_sessions (
    id          BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(128) NOT NULL,
    user_id     VARCHAR(128) NOT NULL,
    title       VARCHAR(255),
    state_data  TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 索引在部分环境中可能已经存在，避免重复创建导致启动失败。
