package com.github.wechat.cloudbot.modules.location.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 工具层：地点 / 周边检索（query_poi）。结果含可点击的高德 URI。
 */
@Component
public class QueryPoiTool implements AgentTool {

    private final LocationGateway locationGateway;
    private final ObjectMapper objectMapper;

    public QueryPoiTool(LocationGateway locationGateway, ObjectMapper objectMapper) {
        this.locationGateway = locationGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "query_poi",
                "查询中国大陆地点、周边商户、景点等（高德），结果含可点击的高德地图链接。"
                        + "用户问附近吃喝玩乐、某街道/小区附近有什么时调用。"
                        + "尽量填写 street（街道/地标/小区）以精确到街道周边；city 填市或区。"
                        + "回复用户时按此排版（稳定美观）：每店一行标题（可用 1️⃣ 等编号），"
                        + "子项为「• 📍地址与距离」「• 📞电话（有则写）」「• 🔗 [查看地图](https://uri.amap.com/...)」；"
                        + "禁止粘贴裸 URL。"
                        + "天气用 query_weather；新闻用 web_search。",
                """
                {"type":"object","properties":{"keywords":{"type":"string","description":"检索关键词，如「咖啡」「火锅」「地铁站」"},"city":{"type":"string","description":"城市或区县，如「杭州」「杭州市余杭区」"},"street":{"type":"string","description":"可选街道/地标/小区/详细地址，如「文一西路」「五常大道」「西溪湿地」，用于周边精确检索"}},"required":["keywords"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String keywords = ToolArgs.textAny(args, "keywords", "query");
            if (keywords.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "keywords empty");
            }
            if (!locationGateway.isAvailable()) {
                return ToolResult.fail(
                        call.id(), call.name(), "地点查询未配置（请设置 AMAP_API_KEY）。");
            }
            String street = ToolArgs.textAny(args, "street", "address");
            String summary =
                    locationGateway.searchPoi(keywords, ToolArgs.text(args, "city"), street);
            return ToolResult.ok(call.id(), call.name(), summary);
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), e.getMessage());
        }
    }
}
