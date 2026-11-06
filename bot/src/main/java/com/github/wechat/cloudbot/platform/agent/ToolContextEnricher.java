package com.github.wechat.cloudbot.platform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具上下文注入器：根据工具名称/类型，在 {@code execute()} 前向 {@link ToolCall} 的参数中
 * 注入额外的上下文信息，实现「根据工具类整合上下文」。
 *
 * <p>内置实现通过 {@link ToolRegistry#addEnricher(ToolContextEnricher)} 注册。
 * 插件工具也可通过 SPI 提供自定义 Enricher。
 *
 * @see ToolRegistry
 * @see com.github.wechat.cloudbot.platform.agent.spi.ToolPluginProvider
 */
public interface ToolContextEnricher {

    /**
     * 判断此注入器是否处理指定的工具。
     *
     * @param toolName   工具名称（如 "query_weather"）
     * @param definition 工具的完整定义（可读取 description/parametersJsonSchema）
     * @return true 表示将对此工具调用 {@link #enrich(String, String, String)}
     */
    default boolean supports(String toolName, ToolDefinition definition) {
        return supports(toolName);
    }

    /**
     * 按工具名匹配的简化重载。
     *
     * @param toolName 工具名称
     * @return true 表示处理此工具
     */
    boolean supports(String toolName);

    /**
     * 向工具调用参数中注入上下文信息。
     *
     * @param argumentsJson 当前参数 JSON 字符串（可能为空字符串 "{}"）
     * @param userId        当前用户 ID
     * @param userContent   用户本次输入的原问题
     * @return 注入后的参数 JSON 字符串（可能是原字符串的增强版本）
     */
    String enrich(String argumentsJson, String userId, String userContent);

    /**
     * 直接操作 {@link ObjectNode} 的上下文注入，避免字符串序列化往返。
     *
     * <p>默认实现通过 {@link #enrich(String, String, String)} 桥接（序列化 → 调用 → 再解析），
     * 新实现应直接重写此方法以消除冗余解析。
     *
     * @param args        已解析的参数 ObjectNode
     * @param userId      当前用户 ID
     * @param userContent 用户本次输入的原问题
     * @param mapper      用于可能需要的 JSON 操作
     * @return 注入后的 ObjectNode（通常就是传入的 args 自身，可变操作后返回）
     */
    default ObjectNode enrichNode(ObjectNode args, String userId, String userContent,
                                  ObjectMapper mapper) {
        String result = enrich(
                args == null ? "{}" : args.toString(),
                userId, userContent);
        try {
            return (ObjectNode) mapper.readTree(result);
        } catch (Exception e) {
            return args;
        }
    }
}
