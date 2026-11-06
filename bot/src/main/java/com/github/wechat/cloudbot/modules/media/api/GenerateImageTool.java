package com.github.wechat.cloudbot.modules.media.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 工具层：文生图（generate_image）。编排主路径走媒体旁路；本 execute 供 Registry 完整可用。
 */
@Component
public class GenerateImageTool implements AgentTool {

    private final MediaGateway mediaGateway;
    private final ObjectMapper objectMapper;

    public GenerateImageTool(MediaGateway mediaGateway, ObjectMapper objectMapper) {
        this.mediaGateway = mediaGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "generate_image",
                "当用户明确要求画图、生成图片、做一张图时调用。",
                """
                {"type":"object","properties":{"prompt":{"type":"string","description":"给绘画模型的详细提示词，可中英混合，尽量具体"},"caption":{"type":"string","description":"发给用户的简短确认语，例如「好的，正在生成…」"}},"required":["prompt"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args =
                    objectMapper.readTree(call.argumentsJson() == null ? "{}" : call.argumentsJson());
            String prompt = args.path("prompt").asText("").trim();
            if (prompt.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "prompt empty");
            }
            if (!mediaGateway.canGenerateImage()) {
                return ToolResult.fail(call.id(), call.name(), "image generation unavailable");
            }
            byte[] bytes = mediaGateway.generateImage(prompt);
            String caption = args.path("caption").asText("");
            String payload =
                    "caption="
                            + caption
                            + "\nimage_base64=(sent to user, "
                            + bytes.length
                            + " bytes)";
            return ToolResult.ok(call.id(), call.name(), payload);
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), e.getMessage());
        }
    }
}
