package com.github.wechat.cloudbot.modules.fun.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.fun.service.JokeService;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 工具层：讲笑话 / 出谜语 / 一言。
 */
@Component
public class JokeTool implements AgentTool {

    private final JokeService jokeService;
    private final ObjectMapper objectMapper;

    public JokeTool(JokeService jokeService, ObjectMapper objectMapper) {
        this.jokeService = jokeService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("fun_joke",
                "当用户要求讲笑话、讲冷笑话、出谜语、来句名言/一言时调用。不要自己编笑话，用此工具。",
                "{\"type\":\"object\",\"properties\":{\"type\":{\"type\":\"string\",\"description\":\"可选：joke（笑话）、riddle（谜语）、quote（名言）\",\"enum\":[\"joke\",\"riddle\",\"quote\"]}}}");
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = objectMapper.readTree(call.argumentsJson() == null ? "{}" : call.argumentsJson());
            return ToolResult.ok(call.id(), call.name(), jokeService.random(args.path("type").asText(null)));
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "获取娱乐内容失败，请稍后再试");
        }
    }
}
