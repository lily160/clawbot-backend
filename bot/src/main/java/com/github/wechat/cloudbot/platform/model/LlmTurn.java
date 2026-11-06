package com.github.wechat.cloudbot.platform.model;

import com.github.wechat.cloudbot.platform.agent.ToolCall;

import java.util.List;

/**
 * LLM 一轮补全结果：纯文本，和/或一组 tool_calls（供 Agent Loop 执行后回灌）。
 *
 * <p>reasoning_content：DeepSeek 思考模式返回的推理过程，回传给 API 时必须带上，
 * 否则会报 400 错误 "The reasoning_content in the thinking mode must be passed back to the API."</p>
 */
public record LlmTurn(String content, List<ToolCall> toolCalls, String reasoningContent) {

    public LlmTurn {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public LlmTurn(String content, List<ToolCall> toolCalls) {
        this(content, toolCalls, null);
    }

    public static LlmTurn text(String content) {
        return new LlmTurn(content, List.of(), null);
    }

    public static LlmTurn of(String content, List<ToolCall> toolCalls) {
        return new LlmTurn(content, toolCalls, null);
    }

    public static LlmTurn of(String content, List<ToolCall> toolCalls, String reasoningContent) {
        return new LlmTurn(content, toolCalls, reasoningContent);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public boolean hasText() {
        return content != null && !content.isBlank();
    }
}
