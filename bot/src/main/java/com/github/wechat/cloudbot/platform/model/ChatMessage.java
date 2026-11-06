package com.github.wechat.cloudbot.platform.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单条对话消息。{@code content} 可为纯文本 String，或 OpenAI 多模态 content 数组。
 *
 * <p>Function Calling：assistant 可带 {@link #toolCalls}；tool 结果消息用 {@link #toolCallId}。
 * <p>reasoningContent：DeepSeek 思考模式的推理过程，回传 API 时必须带上。</p>
 */
public record ChatMessage(
        String role, Object content, String toolCallId, List<FunctionCall> toolCalls,
        String reasoningContent) {

    /** OpenAI 兼容的 function tool_call 片段（避免 model 依赖 agent 包）。 */
    public record FunctionCall(String id, String name, String argumentsJson) {}

    public ChatMessage(String role, Object content) {
        this(role, content, null, null, null);
    }

    public ChatMessage(String role, Object content, String toolCallId, List<FunctionCall> toolCalls) {
        this(role, content, toolCallId, toolCalls, null);
    }

    public static ChatMessage system(String text) {
        return new ChatMessage("system", text);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage("user", text);
    }

    public static ChatMessage assistant(String text) {
        return new ChatMessage("assistant", text);
    }

    /** assistant 发出 tool_calls（content 可为空）。 */
    public static ChatMessage assistantWithToolCalls(String content, List<FunctionCall> toolCalls) {
        return new ChatMessage(
                "assistant",
                content == null || content.isBlank() ? null : content,
                null,
                List.copyOf(toolCalls),
                null);
    }

    /** assistant 发出 tool_calls 且带 reasoning_content（DeepSeek 思考模式）。 */
    public static ChatMessage assistantWithToolCallsAndReasoning(
            String content, List<FunctionCall> toolCalls, String reasoningContent) {
        return new ChatMessage(
                "assistant",
                content == null || content.isBlank() ? null : content,
                null,
                List.copyOf(toolCalls),
                reasoningContent);
    }

    /** role=tool，回灌某次 tool_call 的执行结果。 */
    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage("tool", content == null ? "" : content, toolCallId, null);
    }

    /**
     * 构造带图片的 user 消息（DashScope / OpenAI 兼容 image_url + text）。
     *
     * @param dataUrl {@code data:image/jpeg;base64,...}
     * @param prompt  配图说明文字
     */
    public static ChatMessage userWithImage(String dataUrl, String prompt) {
        List<Map<String, Object>> parts = new ArrayList<>(2);
        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        Map<String, String> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", dataUrl);
        imagePart.put("image_url", imageUrl);
        parts.add(imagePart);

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", prompt == null || prompt.isBlank() ? "请描述这张图片并给出有帮助的回复。" : prompt);
        parts.add(textPart);

        return new ChatMessage("user", parts);
    }

    /**
     * 构造带视频的 user 消息（DashScope OpenAI 兼容 {@code video_url} + text）。
     *
     * @param dataUrl {@code data:video/mp4;base64,...}
     * @param prompt  配视频说明文字
     * @param fps     抽帧频率（越大越密，费用/耗时越高）
     */
    public static ChatMessage userWithVideo(String dataUrl, String prompt, double fps) {
        List<Map<String, Object>> parts = new ArrayList<>(2);
        Map<String, Object> videoPart = new LinkedHashMap<>();
        videoPart.put("type", "video_url");
        Map<String, Object> videoUrl = new LinkedHashMap<>();
        videoUrl.put("url", dataUrl);
        videoPart.put("video_url", videoUrl);
        videoPart.put("fps", fps <= 0 ? 2.0 : fps);
        parts.add(videoPart);

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put(
                "text",
                prompt == null || prompt.isBlank()
                        ? "请描述这段视频的主要内容，并给出简洁、有帮助的回复。"
                        : prompt);
        parts.add(textPart);

        return new ChatMessage("user", parts);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
