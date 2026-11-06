package com.github.wechat.cloudbot.platform.agent.spi;

/**
 * 可注入 {@link PluginContext} 的标记接口。
 *
 * <p>插件如果想让 {@link PluginScanner} 自动注入宿主环境 API，
 * 让工具类实现此接口即可：
 * <pre>{@code
 * public class MyTool implements AgentTool, PluginContextAware {
 *     private PluginContext ctx;
 *
 *     public void setPluginContext(PluginContext ctx) { this.ctx = ctx; }
 *
 *     public ToolResult execute(ToolCall call) {
 *         ctx.logInfo("my-tools", "executing...");
 *         // ...
 *     }
 * }
 * }</pre>
 */
public interface PluginContextAware {

    /**
     * 设置插件上下文。
     *
     * @param pluginContext 宿主环境 API
     */
    void setPluginContext(PluginContext pluginContext);
}
