package com.github.wechat.cloudbot.modules.fun.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.fun.service.FortuneService;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 工具层：掷骰子、抽签、今日运势。
 */
@Component
public class LuckTool implements AgentTool {

    private final FortuneService fortuneService;
    private final ObjectMapper objectMapper;

    public LuckTool(FortuneService fortuneService, ObjectMapper objectMapper) {
        this.fortuneService = fortuneService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("fun_luck",
                "用户要求掷骰子、抽签、今日运势时调用。dice=掷骰子；lottery=抽签；fortune=运势。",
                "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"dice\",\"lottery\",\"fortune\"]}},\"required\":[\"action\"]}");
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String action = objectMapper.readTree(
                    call.argumentsJson() == null ? "{}" : call.argumentsJson())
                    .path("action").asText("");
            return switch (action) {
                case "dice" -> ToolResult.ok(call.id(), call.name(), "🎲 掷出了 " + fortuneService.rollDice());
                case "lottery" -> ToolResult.ok(call.id(), call.name(), fortuneService.drawLottery());
                case "fortune" -> ToolResult.ok(call.id(), call.name(), fortuneService.todayFortune());
                default -> ToolResult.fail(call.id(), call.name(), "试试「掷骰子」「抽签」「今日运势」");
            };
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "出错了，请稍后再试。");
        }
    }
}
