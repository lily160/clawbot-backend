package com.github.wechat.cloudbot.platform.agent.spi;

import com.github.wechat.cloudbot.platform.agent.AgentTool;

import java.util.List;

/**
 * SPI 接口：插件工具提供者。
 *
 * <p>外部工具 JAR 需要实现此接口，并在 {@code META-INF/services/}
 * com.github.wechat.cloudbot.platform.agent.spi.ToolPluginProvider 文件中注册实现类全限定名。
 *
 * <p>插件 JAR 的依赖：
 * <pre>{@code
 * <dependency>
 *     <groupId>com.github.wechat</groupId>
 *     <artifactId>cloudbot-bot</artifactId>
 *     <scope>provided</scope>
 * </dependency>
 * }</pre>
 *
 * <p>示例实现：
 * <pre>{@code
 * public class MyPluginProvider implements ToolPluginProvider {
 *     public String pluginName() { return "my-tools"; }
 *     public List<AgentTool> provideTools() { return List.of(new MyTool()); }
 * }
 * }</pre>
 *
 * @see PluginScanner
 * @see PluginClassLoader
 */
public interface ToolPluginProvider {

    /**
     * 返回插件名称，用于日志和标识。
     *
     * @return 插件唯一名称
     */
    String pluginName();

    /**
     * 返回此插件提供的所有工具。
     *
     * @return 工具实例列表（不可为 null）
     */
    List<AgentTool> provideTools();

    /**
     * 返回插件版本号（可选，默认 "0.0.0"）。
     *
     * @return 语义化版本号
     */
    default String pluginVersion() {
        return "0.0.0";
    }

    /**
     * 返回插件描述（可选）。
     *
     * @return 简短描述
     */
    default String description() {
        return "";
    }
}
