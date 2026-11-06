package com.github.wechat.cloudbot.platform.agent;

/**
 * 模型发出的一次 tool call。
 */
public record ToolCall(String id, String name, String argumentsJson, String userId) {

    public ToolCall(String id, String name, String argumentsJson) {
        this(id, name, argumentsJson, null);
    }
}
