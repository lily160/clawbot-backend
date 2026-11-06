package com.github.wechat.cloudbot.modules.calc.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CalculateToolTest {

    @Test
    void evaluatesSimpleExpression() {
        CalculateTool tool = new CalculateTool(new ObjectMapper());
        ToolResult result =
                tool.execute(new ToolCall("c1", "calculate", "{\"expression\":\"(1+2)*3\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("9"));
    }
}
