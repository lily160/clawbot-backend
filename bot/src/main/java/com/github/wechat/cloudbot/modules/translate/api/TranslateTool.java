package com.github.wechat.cloudbot.modules.translate.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.chat.api.LlmGateway;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import com.github.wechat.cloudbot.platform.model.ChatMessage;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具层：翻译（translate），经 {@link LlmGateway} 完成。
 */
@Component
public class TranslateTool implements AgentTool {

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;

    public TranslateTool(@Lazy LlmGateway llmGateway, ObjectMapper objectMapper) {
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "translate",
                "将文本翻译到指定语言。用户明确要求翻译、英译中、中译日等时调用。"
                        + "普通多语闲聊可直接回复，不必调用。",
                """
                {"type":"object","properties":{"text":{"type":"string","description":"待翻译原文"},"target_lang":{"type":"string","description":"目标语言，如「中文」「英文」「日文」"},"source_lang":{"type":"string","description":"可选源语言；空则自动检测"}},"required":["text","target_lang"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String text = ToolArgs.text(args, "text");
            String target = ToolArgs.textAny(args, "target_lang", "target");
            String source = ToolArgs.text(args, "source_lang");
            if (text.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "text empty");
            }
            if (target.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "target_lang empty");
            }
            StringBuilder prompt = new StringBuilder();
            prompt.append("请将下列文本翻译为").append(target).append("。");
            if (!source.isBlank()) {
                prompt.append("源语言：").append(source).append("。");
            }
            prompt.append("只输出译文，不要解释，不要加引号。\n\n")
                    .append(text);
            String translated =
                    llmGateway.chat(
                            List.of(
                                    ChatMessage.system("你是专业翻译助手，只输出译文。"),
                                    ChatMessage.user(prompt.toString())));
            return ToolResult.ok(
                    call.id(),
                    call.name(),
                    "【目标语言】" + target + "\n【译文】\n" + translated.trim());
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), e.getMessage());
        }
    }
}
