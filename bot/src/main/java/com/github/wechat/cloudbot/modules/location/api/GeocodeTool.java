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
 * 工具层：地理编码（geocode）。
 */
@Component
public class GeocodeTool implements AgentTool {

    private final LocationGateway locationGateway;
    private final ObjectMapper objectMapper;

    public GeocodeTool(LocationGateway locationGateway, ObjectMapper objectMapper) {
        this.locationGateway = locationGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "geocode",
                "将中国大陆地址或地名解析为经纬度与行政区划，并给出可点击的高德短链（回复用 [查看地图](url)）。"
                        + "用户问「某某地址坐标」「某地在哪个区」时调用。"
                        + "搜周边商户请用 query_poi（可带 street）。",
                """
                {"type":"object","properties":{"address":{"type":"string","description":"地址或地名，如「杭州市余杭区文一西路」"},"city":{"type":"string","description":"可选城市偏置，提高解析准确度"}},"required":["address"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String address = ToolArgs.textAny(args, "address", "query");
            if (address.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "address empty");
            }
            if (!locationGateway.isAvailable()) {
                return ToolResult.fail(
                        call.id(), call.name(), "地理编码未配置（请设置 AMAP_API_KEY）。");
            }
            String summary = locationGateway.geocode(address, ToolArgs.text(args, "city"));
            return ToolResult.ok(call.id(), call.name(), summary);
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), e.getMessage());
        }
    }
}
