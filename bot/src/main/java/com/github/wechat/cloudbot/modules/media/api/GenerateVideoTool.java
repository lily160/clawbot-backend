package com.github.wechat.cloudbot.modules.media.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 工具层：文生视频（generate_video）。编排主路径走媒体旁路。
 */
@Component
public class GenerateVideoTool implements AgentTool {

    private final MediaGateway mediaGateway;
    private final ObjectMapper objectMapper;

    public GenerateVideoTool(MediaGateway mediaGateway, ObjectMapper objectMapper) {
        this.mediaGateway = mediaGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "generate_video",
                "当用户明确要求生成视频、做一段视频、拍短片时调用。",
                """
                {"type":"object","properties":{"prompt":{"type":"string","description":"给视频模型的详细提示词，描述画面与镜头，可中英混合"},"caption":{"type":"string","description":"发给用户的简短确认语，例如「好的，正在生成…」"}},"required":["prompt"]}
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
            if (!mediaGateway.canGenerateVideo()) {
                return ToolResult.fail(call.id(), call.name(), "video generation unavailable");
            }
            MediaGateway.GeneratedVideo video = mediaGateway.generateVideo(prompt);
            String caption = args.path("caption").asText("");
            String payload =
                    "caption="
                            + caption
                            + "\nplay_length_ms="
                            + video.playLengthMs()
                            + "\nvideo_base64=(sent to user, "
                            + video.bytes().length
                            + " bytes)";
            return ToolResult.ok(call.id(), call.name(), payload);
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), e.getMessage());
        }
    }
}
