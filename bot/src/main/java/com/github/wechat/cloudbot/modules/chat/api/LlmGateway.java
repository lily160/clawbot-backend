package com.github.wechat.cloudbot.modules.chat.api;

import com.github.wechat.cloudbot.platform.model.ChatMessage;
import com.github.wechat.cloudbot.platform.model.LlmTurn;

import java.util.List;

/**
 * LLM 网关（领域防腐）：上层只依赖本接口，不感知 DeepSeek HTTP 细节。
 *
 * <p>Function Calling：{@link #chatTurn} 可返回 tool_calls，由编排层执行后以 role=tool 回灌。
 */
public interface LlmGateway {

    /** 纯文本补全（关闭 tools）。 */
    String chat(List<ChatMessage> messages);

    /**
     * 一轮推理。
     *
     * @param enableTools {@code true} 时附带 {@code ToolRegistry} 中的 tools
     */
    LlmTurn chatTurn(List<ChatMessage> messages, boolean enableTools);
}
