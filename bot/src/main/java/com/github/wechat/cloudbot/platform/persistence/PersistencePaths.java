package com.github.wechat.cloudbot.platform.persistence;

import com.github.wechat.cloudbot.platform.config.CloudbotProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地持久化路径工具（对话 / 人格 / 登录凭证）。
 */
public final class PersistencePaths {

    private PersistencePaths() {}

    /** root 路径缓存（double-checked locking，结果在运行时不变）。 */
    private static volatile Path rootCache;
    private static final Object ROOT_LOCK = new Object();

    public static Path root(CloudbotProperties properties) {
        Path cached = rootCache;
        if (cached != null) {
            return cached;
        }
        synchronized (ROOT_LOCK) {
            if (rootCache != null) {
                return rootCache;
            }
            String dir = properties.getPersistence().getDir();
            if (dir == null || dir.isBlank()) {
                dir = "./data";
            }
            rootCache = Paths.get(dir.trim()).toAbsolutePath().normalize();
            return rootCache;
        }
    }

    public static Path chatMemoryDir(CloudbotProperties properties) {
        return root(properties).resolve("chat-memory");
    }

    public static Path personasFile(CloudbotProperties properties) {
        return root(properties).resolve("personas.json");
    }

    public static Path authFile(CloudbotProperties properties) {
        return root(properties).resolve("auth").resolve("login-context.json");
    }

    public static Path customVoicesFile(CloudbotProperties properties) {
        return root(properties).resolve("custom-voices.json");
    }

    /** 每用户选择的系统 TTS 音色（覆盖 yml 默认）。 */
    public static Path systemVoicesFile(CloudbotProperties properties) {
        return root(properties).resolve("system-voices.json");
    }

    /** 将 userId 转为安全文件名。 */
    public static String safeFileName(String userId) {
        if (userId == null || userId.isBlank()) {
            return "_unknown";
        }
        return userId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
