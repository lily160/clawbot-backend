package com.github.wechat.cloudbot.modules.search.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 博查 Web Search（联网搜索）。API Key 用环境变量 {@code BOCHA_API_KEY}。
 */
@ConfigurationProperties(prefix = "bocha")
public class BochaProperties {

    private boolean enabled = true;

    private String apiKey = "";

    private String baseUrl = "https://api.bochaai.com/v1";

    /** 时效：noLimit / oneDay / oneWeek / oneMonth / oneYear */
    private String freshness = "noLimit";

    private boolean summary = true;

    private int count = 8;

    private long connectTimeoutMs = 10000L;

    private long readTimeoutMs = 30000L;

    /**
     * 将搜索结果交给 DeepSeek 润色的模板。
     * {@code {search}} = 搜索摘要，{@code {question}} = 用户原问题。
     */
    private String refinePrompt =
            """
            【当前日期】
            {today}

            【联网搜索结果】
            {search}

            【用户问题】
            {question}

            今天是 {today}。请仅依据上述联网搜索结果回答，禁止使用模型训练记忆。
            若搜索结果中已有明确冠军、比分、赛果，必须采用；冲突时优先较新的赛果报道。
            若确实没有答案，如实说明未查到；不要自行推断「还没举办」，除非搜索结果明确如此。
            用简洁友好的微信口吻直接回答；不要写「根据搜索结果」等套话。
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

    public String getFreshness() {
        return freshness;
    }

    public void setFreshness(String freshness) {
        this.freshness = freshness;
    }

    public boolean isSummary() {
        return summary;
    }

    public void setSummary(boolean summary) {
        this.summary = summary;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
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

    public boolean canSearch() {
        return isEnabled() && hasApiKey();
    }
}
