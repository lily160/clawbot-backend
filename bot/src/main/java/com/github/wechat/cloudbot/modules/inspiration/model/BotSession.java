package com.github.wechat.cloudbot.modules.inspiration.model;

/**
 * Bot 会话实体，对应数据库 {@code bot_sessions} 表。
 *
 * <p>{@code stateData} 为 JSON 字符串，存放任意会话上下文。
 */
public class BotSession {

    private Long id;
    private String sessionId;
    private String userId;
    private String title;
    private String stateData;
    private String createdAt;
    private String updatedAt;

    public BotSession() {}

    public BotSession(Long id, String sessionId, String userId, String title,
                      String stateData, String createdAt, String updatedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.userId = userId;
        this.title = title;
        this.stateData = stateData;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getStateData() { return stateData; }
    public void setStateData(String stateData) { this.stateData = stateData; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
