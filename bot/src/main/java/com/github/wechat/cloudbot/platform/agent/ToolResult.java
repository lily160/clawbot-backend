package com.github.wechat.cloudbot.platform.agent;

/**
 * Tool 执行结果，以 role=tool 回灌模型，形成 Agent Loop。
 */
public record ToolResult(String toolCallId, String name, String content, boolean success) {

    public static ToolResult ok(String toolCallId, String name, String content) {
        return new ToolResult(toolCallId, name, content, true);
    }

    public static ToolResult fail(String toolCallId, String name, String error) {
        return new ToolResult(toolCallId, name, error, false);
    }
}
