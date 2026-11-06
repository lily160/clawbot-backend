package com.github.wechat.cloudbot.platform.config;

/**
 * 统一错误码 —— 所有 {@code log.error()} 必须使用此枚举前缀，格式：{@code [E001]}。
 *
 * <p>分类规则：
 * <pre>
 *   E0xx — Platform / 基础设施
 *   E1xx — Chat / DeepSeek
 *   E2xx — Search / 博查
 *   E3xx — Weather / 高德
 *   E4xx — Vision / 千问 VL
 *   E5xx — Media / 火山方舟
 *   E6xx — Voice / 语音
 *   E7xx — Persistence / 持久化
 *   E8xx — Channel / 微信通道
 *   E9xx — Agent / 编排
 *   E999 — 未知错误
 * </pre>
 */
public enum ErrorCode {

    // ===== Platform (E0xx) =====
    /** 配置缺失或格式错误 */
    PLATFORM_CONFIG("E001"),
    /** 初始化失败（如依赖未就绪） */
    PLATFORM_INIT("E002"),
    /** 登录 / 认证失败 */
    PLATFORM_AUTH("E003"),

    // ===== Chat / DeepSeek (E1xx) =====
    /** DeepSeek 未启用或 api-key 为空 */
    CHAT_DISABLED("E101"),
    /** DeepSeek API HTTP 调用失败 */
    CHAT_API_ERROR("E102"),
    /** DeepSeek 响应解析失败 */
    CHAT_PARSE_ERROR("E103"),
    /** DeepSeek 返回空内容 */
    CHAT_EMPTY_CONTENT("E104"),

    // ===== Search / 博查 (E2xx) =====
    /** 搜索未启用或 api-key 为空 */
    SEARCH_DISABLED("E201"),
    /** 搜索 API HTTP 调用失败 */
    SEARCH_API_ERROR("E202"),
    /** 搜索返回空结果 */
    SEARCH_EMPTY_RESULT("E203"),

    // ===== Weather / 高德 (E3xx) =====
    /** 天气未启用或 api-key 为空 */
    WEATHER_DISABLED("E301"),
    /** 天气 API HTTP 调用失败 */
    WEATHER_API_ERROR("E302"),
    /** 城市识别或查询失败 */
    WEATHER_CITY_ERROR("E303"),

    // ===== Vision / 千问 VL (E4xx) =====
    /** 视觉识别未启用或 api-key 为空 */
    VISION_DISABLED("E401"),
    /** 视觉 API HTTP 调用失败 */
    VISION_API_ERROR("E402"),
    /** 视觉识别返回空内容 */
    VISION_EMPTY_CONTENT("E403"),

    // ===== Media / 火山方舟 (E5xx) =====
    /** 媒体生成未启用或 api-key 为空 */
    MEDIA_DISABLED("E501"),
    /** 媒体 API HTTP 调用失败 */
    MEDIA_API_ERROR("E502"),
    /** 媒体文件下载失败 */
    MEDIA_DOWNLOAD_ERROR("E503"),
    /** 视频生成超时 */
    MEDIA_VIDEO_TIMEOUT("E504"),

    // ===== Voice / 语音 (E6xx) =====
    /** 语音配置错误（api-key 为空等） */
    VOICE_CONFIG_ERROR("E601"),
    /** 语音 API HTTP 调用失败 */
    VOICE_API_ERROR("E602"),
    /** 声音复刻失败 */
    VOICE_CLONE_ERROR("E603"),
    /** ASR 返回空文本 */
    VOICE_ASR_EMPTY("E604"),
    /** TTS 返回空响应 */
    VOICE_TTS_EMPTY("E605"),

    // ===== Persistence / 持久化 (E7xx) =====
    /** 文件 IO 写入失败 */
    PERSISTENCE_WRITE_ERROR("E701"),
    /** 文件 IO 读取失败 */
    PERSISTENCE_READ_ERROR("E702"),
    /** 目录创建失败 */
    PERSISTENCE_DIR_ERROR("E703"),

    // ===== Channel / 微信通道 (E8xx) =====
    /** 消息发送失败 */
    CHANNEL_SEND_ERROR("E801"),
    /** 微信登录失败 */
    CHANNEL_LOGIN_ERROR("E802"),
    /** 文件下载或解密失败 */
    CHANNEL_DOWNLOAD_ERROR("E803"),

    // ===== Agent / 编排 (E9xx) =====
    /** Agent Loop 整体失败 */
    AGENT_LOOP_ERROR("E901"),
    /** 单个 Tool 调用失败 */
    AGENT_TOOL_ERROR("E902"),
    /** 调用了未注册的 Tool */
    AGENT_UNKNOWN_TOOL("E903"),

    // ===== General (E999) =====
    /** 未分类的异常 */
    UNEXPECTED("E999");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * 返回日志前缀格式 {@code [E001]}，直接拼在 log.error 消息开头。
     */
    public String prefix() {
        return "[" + code + "] ";
    }

    /**
     * 返回带前缀的完整消息：{@code [E001] 原消息内容}
     */
    public String format(String message) {
        return prefix() + message;
    }

    /**
     * 返回带前缀的格式化消息：{@code [E001] 格式化的内容}
     */
    public String format(String format, Object... args) {
        return prefix() + String.format(format, args);
    }
}
