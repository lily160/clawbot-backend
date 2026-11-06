package com.github.wechat.cloudbot.modules.inspiration.api;

import com.github.wechat.cloudbot.modules.inspiration.service.InspirationService;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class InspirationTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(InspirationTool.class);

    private final InspirationService inspirationService;
    private final ObjectMapper objectMapper;

    public InspirationTool(InspirationService inspirationService, ObjectMapper objectMapper) {
        this.inspirationService = inspirationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        // 定义大模型可见的 Schema，引导模型在用户想保存灵感时调用
        String jsonSchema = """
            {
              "type": "object",
              "properties": {
                "topic": {
                  "type": "string",
                  "description": "为这段笔记提炼一个简短的主题或标题，长度不超过10个字符"
                },
                "content": {
                  "type": "string",
                  "description": "需要保存的完整笔记内容，大模型需要对用户发来的口语化内容进行适当的润色和 Markdown 格式排版"
                }
              },
              "required": ["topic", "content"]
            }
            """;

        return new ToolDefinition(
                "saveInspiration",
                "当用户要求保存、记录笔记、收藏代码片段、存入知识库，或者说‘帮我保存到我的知识库里’时，必须调用此工具。",
                jsonSchema
        );
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = objectMapper.readTree(call.argumentsJson() == null ? "{}" : call.argumentsJson());
            // JSON null / "null" 字符串 / 空串一律回退默认主题，避免把 "null" 当主题保存
            String topic = args.hasNonNull("topic") && !args.get("topic").isNull()
                    ? safeText(args.get("topic").asText(), "默认灵感")
                    : "默认灵感";
            String content = args.hasNonNull("content") ? args.get("content").asText() : "";

            log.info("🤖 [大模型触发工具调用] InspirationTool.execute -> 主题: {}", topic);

            // 执行业务逻辑，将结果推送到 GitHub Gist
            String resultMsg = inspirationService.saveNote(topic, content);

            return ToolResult.ok(call.id(), call.name(), resultMsg);
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "保存灵感失败: " + e.getMessage());
        }
    }

    /** 文本为空 / "null" / "undefined" 时回退默认值。 */
    private static String safeText(String text, String fallback) {
        if (text == null || text.isBlank() || "null".equalsIgnoreCase(text.trim()) || "undefined".equalsIgnoreCase(text.trim())) {
            return fallback;
        }
        return text.trim();
    }
}