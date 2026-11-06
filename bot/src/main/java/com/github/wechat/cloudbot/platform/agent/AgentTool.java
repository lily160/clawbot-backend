package com.github.wechat.cloudbot.platform.agent;

/**
 * 可执行 Tool。基础设施层提供具体实现（搜索 / 生图 / …），由 {@link ToolRegistry} 注册。
 */
public interface AgentTool {

    ToolDefinition definition();

    ToolResult execute(ToolCall call);
}
