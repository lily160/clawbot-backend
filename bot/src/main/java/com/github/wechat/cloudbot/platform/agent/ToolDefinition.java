package com.github.wechat.cloudbot.platform.agent;

/**
 * Agent Tool 定义（供 LLM Function Calling 注册，由 {@link ToolRegistry#definitions()} 汇总）。
 */
public record ToolDefinition(
        String name, String description, String parametersJsonSchema) {}
