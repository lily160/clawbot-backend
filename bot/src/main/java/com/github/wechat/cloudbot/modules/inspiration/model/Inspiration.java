package com.github.wechat.cloudbot.modules.inspiration.model;

/**
 * 灵感笔记实体，对应数据库 {@code inspirations} 表。
 */
public class Inspiration {

    private Long id;
    private String topic;
    private String content;
    private String filename;
    private String gistUrl;
    private String createdAt;
    private String updatedAt;

    public Inspiration() {}

    public Inspiration(Long id, String topic, String content, String filename,
                       String gistUrl, String createdAt, String updatedAt) {
        this.id = id;
        this.topic = topic;
        this.content = content;
        this.filename = filename;
        this.gistUrl = gistUrl;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getGistUrl() { return gistUrl; }
    public void setGistUrl(String gistUrl) { this.gistUrl = gistUrl; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
