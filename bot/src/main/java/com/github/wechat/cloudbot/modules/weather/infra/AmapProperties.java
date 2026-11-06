package com.github.wechat.cloudbot.modules.weather.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 高德 Web 服务（天气查询）。API Key 用环境变量 {@code AMAP_API_KEY}。
 */
@ConfigurationProperties(prefix = "amap")
public class AmapProperties {

    private boolean enabled = true;

    private String apiKey = "";

    private String baseUrl = "https://restapi.amap.com";

    /** 默认气象类型：base=实况，all=预报 */
    private String defaultExtensions = "all";

    private long connectTimeoutMs = 10000L;

    private long readTimeoutMs = 15000L;

    /**
     * 将天气结果交给 DeepSeek 润色的模板。
     * {@code {weather}} = 高德摘要，{@code {question}} = 用户原问题。
     */
    private String refinePrompt =
            """
            【当前日期】
            {today}

            【高德天气查询结果】
            {weather}

            【用户问题】
            {question}

            今天是 {today}。请仅依据上述天气数据回答，禁止使用模型训练记忆编造气温或天气。
            若结果地点与用户询问的城市明显不符，只说明查不到该城市，禁止改口播报结果里其它城市的天气。
            用简洁友好的微信口吻直接回答；不要写「根据高德」等套话；可适当给出穿衣/出行提醒。
            """;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getDefaultExtensions() {
        return defaultExtensions;
    }

    public void setDefaultExtensions(String defaultExtensions) {
        this.defaultExtensions = defaultExtensions;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public String getRefinePrompt() {
        return refinePrompt;
    }

    public void setRefinePrompt(String refinePrompt) {
        this.refinePrompt = refinePrompt;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean canQuery() {
        return isEnabled() && hasApiKey();
    }
}
