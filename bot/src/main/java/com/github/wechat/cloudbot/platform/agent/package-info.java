/**
 * Agent Function Calling：{@link ToolCall} → {@link ToolRegistry#execute} → {@link ToolResult}
 * → role=tool 回灌 LLM（Agent Loop）。tools 定义由 {@link ToolRegistry#definitions()} 提供。
 */
package com.github.wechat.cloudbot.platform.agent;
