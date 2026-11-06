package com.github.wechat.cloudbot.platform.agent.spi;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * 插件类加载器：从指定 JAR 文件中加载插件类。
 *
 * <p>每个插件 JAR 拥有独立的 {@code PluginClassLoader} 实例，实现 ClassLoader 隔离，
 * 避免不同插件间同名类冲突。父加载器设置为系统类加载器，使插件可访问主程序的 API 类
 * （如 {@code AgentTool}、{@code ToolCall} 等）。
 */
public class PluginClassLoader extends URLClassLoader {

    private static final String[] HOST_PACKAGES = {
            "com.github.wechat.cloudbot.platform.agent.",
            "com.github.wechat.cloudbot.platform.model.",
            "com.github.wechat.cloudbot.platform.config.",
            "com.fasterxml.jackson.databind.",
            "com.fasterxml.jackson.annotation.",
            "org.slf4j."
    };

    /**
     * 创建插件类加载器。
     *
     * @param jarUrl 插件 JAR 文件的 URL
     * @param parent 父类加载器（通常为系统类加载器）
     */
    public PluginClassLoader(URL jarUrl, ClassLoader parent) {
        super(new URL[]{jarUrl}, parent);
    }

    /**
     * 优先从自身加载，但宿主核心类始终委托给父加载器。
     */
    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            // 宿主核心类直接委托给父加载器
            if (isHostClass(name)) {
                try {
                    return getParent().loadClass(name);
                } catch (ClassNotFoundException e) {
                    // fall through to local loading
                }
            }
            // 检查已加载的类
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return loaded;
            }
            try {
                // 优先从自身 JAR 加载
                return findClass(name);
            } catch (ClassNotFoundException e) {
                // 自身找不到则委托给父加载器
                return getParent().loadClass(name);
            }
        }
    }

    /**
     * 判断是否为宿主核心包中的类。
     */
    private static boolean isHostClass(String name) {
        for (String prefix : HOST_PACKAGES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        URL[] urls = getURLs();
        if (urls.length > 0) {
            return "PluginClassLoader{" + urls[0] + "}";
        }
        return "PluginClassLoader{empty}";
    }
}
