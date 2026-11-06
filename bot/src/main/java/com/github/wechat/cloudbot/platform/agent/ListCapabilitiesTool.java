package com.github.wechat.cloudbot.platform.agent;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 用户询问「有什么功能 / 你会什么」时返回能力清单。
 */
@Component
public class ListCapabilitiesTool implements AgentTool {

    public static final String TOOL_NAME = "list_capabilities";

    private final ObjectProvider<ToolRegistry> toolRegistry;

    public ListCapabilitiesTool(ObjectProvider<ToolRegistry> toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                TOOL_NAME,
                "当用户询问你有什么功能、你会什么、能做什么、功能列表、帮助、菜单时必须调用；"
                        + "返回当前 Bot 能力清单，禁止凭空编造未列出的能力。",
                """
                {"type":"object","properties":{},"additionalProperties":false}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        ToolRegistry registry = toolRegistry.getIfAvailable();
        String summary =
                registry == null
                        ? CapabilityCatalog.userFacingSummary()
                        : CapabilityCatalog.userFacingSummary(registry.enabledNames());
        return ToolResult.ok(call.id(), call.name(), summary);
    }
}
