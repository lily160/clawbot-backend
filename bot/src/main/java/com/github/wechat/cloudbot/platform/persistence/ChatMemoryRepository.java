package com.github.wechat.cloudbot.platform.persistence;

import com.github.wechat.cloudbot.platform.model.ChatMessage;

import java.util.List;

/**
 * 短期对话记忆仓储。实现可选文件（{@code FileChatMemoryStore}）或 JDBC（{@code JdbcChatMemoryStore}）。
 */
public interface ChatMemoryRepository {

    /** 图片历史占位（不持久化 Base64）。 */
    String IMAGE_HISTORY_PLACEHOLDER = "[用户发送了一张图片]";

    /** 视频历史占位（不持久化 Base64）。 */
    String VIDEO_HISTORY_PLACEHOLDER = "[用户发送了一段视频]";

    List<ChatMessage> getHistory(String userId);

    void appendTurn(String userId, String userContent, String assistantContent);

    void clear(String userId);

    /**
     * 按数据库主键更新消息正文。文件实现可不支持（抛出 {@link UnsupportedOperationException}）。
     *
     * @return 是否更新到行
     */
    boolean updateContent(long id, String content);

    /**
     * 按数据库主键删除单条消息。文件实现可不支持（抛出 {@link UnsupportedOperationException}）。
     *
     * @return 是否删除到行
     */
    boolean deleteMessage(long id);

    List<ChatMessage> buildMessages(String userId, String currentUserContent);

    List<ChatMessage> buildSearchRefineMessages(String userId, String refineUserContent);

    List<ChatMessage> buildVisionOnlyMessages(String dataUrl, String imagePrompt);

    List<ChatMessage> buildVideoVisionOnlyMessages(String dataUrl, String videoPrompt, double fps);
}
