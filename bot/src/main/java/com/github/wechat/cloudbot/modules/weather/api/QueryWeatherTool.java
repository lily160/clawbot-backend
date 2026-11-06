package com.github.wechat.cloudbot.modules.weather.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.weather.infra.AmapProperties;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 工具层：天气查询（query_weather）。结果套 refine-prompt 供 Agent Loop 回灌。
 */
@Component
public class QueryWeatherTool implements AgentTool {

    private final WeatherGateway weatherGateway;
    private final AmapProperties amapProperties;
    private final ObjectMapper objectMapper;

    public QueryWeatherTool(
            WeatherGateway weatherGateway,
            AmapProperties amapProperties,
            ObjectMapper objectMapper) {
        this.weatherGateway = weatherGateway;
        this.amapProperties = amapProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "query_weather",
                "当用户询问中国大陆城市天气、气温、是否下雨、天气预报时必须调用。"
                        + "海外城市（如洛杉矶、东京、纽约）请用 web_search，不要用本工具。"
                        + "禁止凭记忆回答天气。",
                """
                {"type":"object","properties":{"city":{"type":"string","description":"要查询的城市名，如「北京」「杭州」「上海市浦东新区」"},"extensions":{"type":"string","description":"可选：base=仅实况；all=预报（含未来几天）。问现在/今天气温用 base，问明天/这周用 all"},"caption":{"type":"string","description":"发给用户的简短确认语，例如「好的，我帮你查一下天气…」"},"question":{"type":"string","description":"用户原问题（可选，编排层可注入）"}},"required":["city"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String city = ToolArgs.textAny(args, "city", "query");
            if (city.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "city empty；请告诉用户要查哪个城市。");
            }
            if (!weatherGateway.isAvailable()) {
                return ToolResult.fail(
                        call.id(),
                        call.name(),
                        "天气查询未配置（请设置 AMAP_API_KEY）。若为海外城市请改用 web_search。");
            }

            String question = ToolArgs.text(args, "question");
            if (question.isBlank()) {
                question = city;
            }
            String extensions = resolveExtensions(ToolArgs.text(args, "extensions"), question);

            String weatherText = weatherGateway.query(city, extensions);
            String today = LocalDate.now().toString();
            String wrapped =
                    amapProperties
                            .getRefinePrompt()
                            .replace("{today}", today)
                            .replace("{weather}", weatherText)
                            .replace("{question}", question);
            return ToolResult.ok(call.id(), call.name(), wrapped);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "weather query failed" : e.getMessage();
            if (msg.contains("仅支持中国大陆")
                    || msg.contains("未能正确定位")
                    || msg.contains("海外")) {
                msg = msg + " 请改用 web_search 查询该地区天气。";
            }
            return ToolResult.fail(call.id(), call.name(), msg);
        }
    }

    private static String resolveExtensions(String modelExtensions, String userContent) {
        if (userContent != null
                && (userContent.contains("明天")
                        || userContent.contains("后天")
                        || userContent.contains("预报")
                        || userContent.contains("这周")
                        || userContent.contains("下周")
                        || userContent.contains("未来"))) {
            return "all";
        }
        if (modelExtensions != null && !modelExtensions.isBlank()) {
            return "base".equalsIgnoreCase(modelExtensions.trim()) ? "base" : "all";
        }
        if (userContent != null
                && (userContent.contains("现在")
                        || userContent.contains("目前")
                        || userContent.contains("实时"))) {
            return "base";
        }
        return "all";
    }
}
