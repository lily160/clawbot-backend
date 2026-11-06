package com.github.wechat.cloudbot.modules.calc.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.calc.service.ExpressionEvaluator;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 工具层：本地算术（calculate）。汇率等实时换算请用 web_search。
 */
@Component
public class CalculateTool implements AgentTool {

    private final ObjectMapper objectMapper;

    public CalculateTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "calculate",
                "精确计算数学表达式（加减乘除、括号、幂、取余）。"
                        + "用户要求算一下、口算易错的数值时调用，禁止心算瞎报。"
                        + "汇率/股价等实时换算请用 web_search，不要用本工具猜。",
                """
                {"type":"object","properties":{"expression":{"type":"string","description":"算术表达式，如「(12.5+7)*3」或「2^10」"}},"required":["expression"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String expression = ToolArgs.textAny(args, "expression", "expr");
            if (expression.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "expression empty");
            }
            double value = ExpressionEvaluator.evaluate(expression);
            String formatted = formatNumber(value);
            return ToolResult.ok(
                    call.id(),
                    call.name(),
                    "【表达式】" + expression.trim() + "\n【结果】" + formatted);
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), e.getMessage());
        }
    }

    private static String formatNumber(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Double.toString(value);
        }
        BigDecimal bd = BigDecimal.valueOf(value).setScale(10, RoundingMode.HALF_UP).stripTrailingZeros();
        return bd.toPlainString();
    }
}
