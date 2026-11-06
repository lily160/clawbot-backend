package com.github.wechat.cloudbot.modules.browse.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 工具层：打开链接并抽取正文（browse_url）。
 */
@Component
public class BrowseUrlTool implements AgentTool {

    private final BrowseGateway browseGateway;
    private final ObjectMapper objectMapper;

    public BrowseUrlTool(BrowseGateway browseGateway, ObjectMapper objectMapper) {
        this.browseGateway = browseGateway;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "browse_url",
                "当用户给出具体网页链接、要求「打开/看看这个链接/总结这篇文章」时调用。"
                        + "不要用于笼统搜索（用 web_search）；不要用于天气或地点。",
                """
                {"type":"object","properties":{"url":{"type":"string","description":"完整的 http/https 链接"}},"required":["url"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String url = ToolArgs.textAny(args, "url", "link");
            if (url.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "url empty");
            }
            if (!browseGateway.isAvailable()) {
                return ToolResult.fail(call.id(), call.name(), "browse 已关闭");
            }
            return ToolResult.ok(call.id(), call.name(), browseGateway.fetchPage(url));
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), e.getMessage());
        }
    }
}
