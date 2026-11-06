package com.github.wechat.cloudbot.modules.fun.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.fun.service.GameService;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 工具层：猜数字等互动游戏。
 */
@Component
public class GameTool implements AgentTool {

    private final GameService gameService;
    private final ObjectMapper objectMapper;

    public GameTool(GameService gameService, ObjectMapper objectMapper) {
        this.gameService = gameService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("fun_game",
                "当用户要玩猜数字等互动游戏时调用。流程：用户说「猜数字」→ action=start → 用户猜数字 → action=guess&value=数字。",
                "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"start\",\"guess\"]},\"game\":{\"type\":\"string\",\"enum\":[\"guess_number\"]},\"value\":{\"type\":\"number\"}},\"required\":[\"action\",\"game\"]}");
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = objectMapper.readTree(call.argumentsJson() == null ? "{}" : call.argumentsJson());
            String action = args.path("action").asText("");
            String userId = call.userId() != null ? call.userId() : "unknown";

            if ("start".equals(action))
                return ToolResult.ok(call.id(), call.name(), gameService.startGuessNumber(userId));
            if ("guess".equals(action)) {
                int v = args.path("value").asInt(Integer.MIN_VALUE);
                if (v == Integer.MIN_VALUE)
                    return ToolResult.fail(call.id(), call.name(), "请告诉我你猜的数字。");
                return ToolResult.ok(call.id(), call.name(), gameService.guess(userId, v));
            }
            return ToolResult.fail(call.id(), call.name(), "试试「猜数字」开始游戏。");
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "游戏出错，请重试。");
        }
    }
}
