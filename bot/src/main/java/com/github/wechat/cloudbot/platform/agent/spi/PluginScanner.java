package com.github.wechat.cloudbot.platform.agent.spi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件扫描器：扫描 {@code plugins/} 目录，通过 SPI 发现和加载外部工具 JAR。
 *
 * <p>生命周期：
 * <ol>
 *   <li>应用启动时自动扫描 plugins 目录下的所有 *.jar</li>
 *   <li>运行时通过 {@link #reload()} 触发重新扫描（对应 {code /tool reload} 命令）</li>
 * </ol>
 *
 * <p>每个 JAR 文件使用独立的 {@link PluginClassLoader} 加载，实现 ClassLoader 隔离。
 */
@Component
public class PluginScanner {

    private static final Logger log = LoggerFactory.getLogger(PluginScanner.class);

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final Path pluginDir;

    /** 已加载的 JAR 路径 → 对应的 ClassLoader（用于隔离和卸载） */
    private final Map<String, PluginClassLoader> loadedJars = new ConcurrentHashMap<>();

    public PluginScanner(
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            @Value("${cloudbot.plugins.directory:plugins}") String pluginDir) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.pluginDir = Paths.get(pluginDir);
    }

    /**
     * 启动时扫描插件目录，加载所有 JAR。
     */
    @PostConstruct
    public void scanOnStartup() {
        try {
            Files.createDirectories(pluginDir);
        } catch (IOException e) {
            log.warn("无法创建插件目录 {}: {}", pluginDir, e.getMessage());
            return;
        }
        int count = reload();
        if (count > 0) {
            log.info("启动时已加载 {} 个插件工具", count);
        }
    }

    /**
     * 重新扫描插件目录：发现新 JAR、移除已删除 JAR 对应的工具。
     *
     * @return 本次新加载的工具数量
     */
    public synchronized int reload() {
        List<Path> jars = listJarFiles();
        int newTools = 0;

        // 加载新的 JAR
        for (Path jar : jars) {
            String jarName = jar.getFileName().toString();
            if (loadedJars.containsKey(jarName)) {
                continue; // 已加载
            }
            try {
                int loaded = loadJar(jar);
                newTools += loaded;
            } catch (Exception e) {
                log.error("加载插件 JAR 失败 {}: {}", jarName, e.getMessage(), e);
            }
        }

        // 卸载已被删除的 JAR
        loadedJars.keySet().removeIf(jarName -> {
            boolean exists = jars.stream().anyMatch(p -> p.getFileName().toString().equals(jarName));
            if (!exists) {
                log.info("插件 JAR 已删除，对应的工具需手动注销: {}", jarName);
                // 关闭 ClassLoader 以释放 JAR 文件句柄（Windows 上不 close 会锁文件无法删除/替换）
                closeQuietly(loadedJars.get(jarName));
                return true; // 从 loadedJars 中移除记录
            }
            return false;
        });

        return newTools;
    }

    /**
     * 加载单个 JAR 文件。
     *
     * @param jarPath JAR 文件的绝对路径
     * @return 此 JAR 提供的工具数量
     */
    public synchronized int loadJar(Path jarPath) {
        String jarName = jarPath.getFileName().toString();
        if (!jarName.endsWith(".jar")) {
            log.warn("跳过非 JAR 文件: {}", jarName);
            return 0;
        }

        log.info("正在加载插件 JAR: {}", jarName);
        PluginClassLoader classLoader;
        try {
            classLoader = new PluginClassLoader(
                    jarPath.toUri().toURL(),
                    getClass().getClassLoader());
        } catch (MalformedURLException e) {
            log.error("插件 JAR URL 格式错误 {}: {}", jarName, e.getMessage());
            return 0;
        }

        ServiceLoader<ToolPluginProvider> serviceLoader =
                ServiceLoader.load(ToolPluginProvider.class, classLoader);

        List<ToolPluginProvider> providers = new ArrayList<>();
        for (ToolPluginProvider provider : serviceLoader) {
            providers.add(provider);
        }

        if (providers.isEmpty()) {
            log.warn("插件 JAR {} 中未找到 ToolPluginProvider 实现", jarName);
            return 0;
        }

        int toolCount = 0;
        for (ToolPluginProvider provider : providers) {
            PluginContext ctx = createPluginContext(provider.pluginName());
            List<AgentTool> tools = provider.provideTools();
            if (tools == null || tools.isEmpty()) {
                log.warn("插件 {} 未提供任何工具", provider.pluginName());
                continue;
            }
            for (AgentTool tool : tools) {
                String name = tool.definition() != null ? tool.definition().name() : null;
                if (name == null || name.isBlank()) {
                    log.warn("插件 {} 的工具缺少名称，已跳过", provider.pluginName());
                    continue;
                }
                // 注入 PluginContext
                if (tool instanceof PluginContextAware aware) {
                    aware.setPluginContext(ctx);
                }
                toolRegistry.register(name, tool);
                toolCount++;
            }
            log.info("插件加载完成 name={}, version={}, tools={}",
                    provider.pluginName(), provider.pluginVersion(), tools.size());
        }

        loadedJars.put(jarName, classLoader);
        return toolCount;
    }

    /**
     * 卸载指定 JAR 加载的所有工具。
     *
     * @param jarName JAR 文件名
     */
    public synchronized void unloadJar(String jarName) {
        PluginClassLoader removed = loadedJars.remove(jarName);
        if (removed != null) {
            // 关闭 ClassLoader 释放 JAR 文件句柄；已加载的类实例仍可继续使用
            closeQuietly(removed);
            log.info("已移除并关闭插件 JAR 的 ClassLoader: {}（工具需手动注销）", jarName);
        }
    }

    private static void closeQuietly(PluginClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException e) {
            log.warn("关闭插件 ClassLoader 失败: {}", e.getMessage());
        }
    }

    /**
     * 列出插件目录下的所有 JAR 文件。
     */
    private List<Path> listJarFiles() {
        List<Path> jars = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(pluginDir, "*.jar")) {
            for (Path entry : stream) {
                jars.add(entry);
            }
        } catch (IOException e) {
            log.warn("扫描插件目录失败: {}", e.getMessage());
        }
        jars.sort(Path::compareTo);
        return jars;
    }

    /**
     * 为插件创建宿主环境 API。
     */
    private PluginContext createPluginContext(String pluginName) {
        return new PluginContext() {
            @Override
            public ObjectMapper objectMapper() {
                return objectMapper;
            }

            @Override
            public String getConfig(String key, String defaultValue) {
                // 可扩展为从 Spring Environment 读取
                return defaultValue;
            }

            @Override
            public void logInfo(String plugin, String message) {
                log.info("[{}] {}", plugin, message);
            }

            @Override
            public void logWarn(String plugin, String message) {
                log.warn("[{}] {}", plugin, message);
            }

            @Override
            public void logError(String plugin, String message, Throwable t) {
                log.error("[{}] {}", plugin, message, t);
            }

            @Override
            public String getDataDir(String pluginName) {
                Path dir = pluginDir.resolve("data").resolve(pluginName);
                try {
                    Files.createDirectories(dir);
                } catch (IOException e) {
                    log.warn("无法创建插件数据目录 {}: {}", dir, e.getMessage());
                }
                return dir.toAbsolutePath().toString();
            }

            @Override
            public String toString() {
                return "PluginContext{" + pluginName + "}";
            }
        };
    }
}
