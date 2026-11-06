package com.github.wechat.cloudbot.platform.agent.spi;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 插件宿主环境 API：插件可通过此接口受限地访问主程序服务。
 *
 * <p>插件<strong>不能</strong>直接使用 Spring {@code @Autowired} 注入。
 * 所有宿主服务通过此接口提供，以维持 ClassLoader 隔离。
 *
 * <p>注入方式：{@link PluginScanner} 在加载插件时，通过
 * {@link PluginContextAware#setPluginContext(PluginContext)} 注入。
 */
public interface PluginContext {

    /**
     * 获取 Jackson ObjectMapper（JSON 解析）。
     *
     * @return 全局 ObjectMapper 实例
     */
    ObjectMapper objectMapper();

    /**
     * 获取插件配置项。
     *
     * @param key          配置键（如 "plugin.my-tools.api-key"）
     * @param defaultValue 默认值
     * @return 配置值，不存在时返回 defaultValue
     */
    String getConfig(String key, String defaultValue);

    /** 记录 info 日志。 */
    void logInfo(String plugin, String message);

    /** 记录 warn 日志。 */
    void logWarn(String plugin, String message);

    /** 记录 error 日志。 */
    void logError(String plugin, String message, Throwable t);

    /**
     * 获取插件数据目录（用于持久化）。
     *
     * @param pluginName 插件名称
     * @return 插件专属的数据目录路径（自动创建）
     */
    String getDataDir(String pluginName);
}
