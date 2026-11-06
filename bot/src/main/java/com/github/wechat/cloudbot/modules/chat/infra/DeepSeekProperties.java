package com.github.wechat.cloudbot.modules.chat.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

/**
 * DeepSeek Chat API 配置（OpenAI 兼容协议）。主对话模型。
 *
 * <p>API Key 请通过环境变量 {@code DEEPSEEK_API_KEY} 注入，勿提交到仓库。
 */
@ConfigurationProperties(prefix = "deepseek")
public class DeepSeekProperties {

    private boolean enabled = true;

    private String apiKey = "";

    private String baseUrl = "https://api.deepseek.com";

    private String model = "deepseek-v4-flash";

    private String systemPrompt =
            "你是微信 Cloudbot 助手，回复简洁友好，适合即时消息。";

    /**
     * 将千问识图结果交给 DeepSeek 优化时追加的用户提示模板。
     * 使用 {@code {vision}} 占位符表示识图原文。
     */
    private String visionRefinePrompt =
            "【图片识图结果】\n{vision}\n\n请结合对话上下文，将上述内容优化为简洁友好的微信回复。"
                    + "直接输出最终回复正文，不要解释优化过程。";

    /**
     * 将千问识视频结果交给 DeepSeek 优化时的模板。
     * 使用 {@code {vision}} 占位符表示识视频原文。
     */
    private String videoRefinePrompt =
            "【视频理解结果】\n{vision}\n\n请结合对话上下文，将上述内容优化为简洁友好的微信回复。"
                    + "直接输出最终回复正文，不要解释优化过程。";

    private int maxHistoryTurns = 10;

    private long connectTimeoutMs = 10000L;

    private long readTimeoutMs = 60000L;

    private double temperature = 0.7;

    /**
     * 模型训练知识大约截止的年月（如 {@code 2025-06}）。
     * 晚于此时间且像在问可验证事实的消息，会触发第 3 层搜索兜底。
     */
    private String knowledgeCutoff = "2025-06";

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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getVisionRefinePrompt() {
        return visionRefinePrompt;
    }

    public void setVisionRefinePrompt(String visionRefinePrompt) {
        this.visionRefinePrompt = visionRefinePrompt;
    }

    public String getVideoRefinePrompt() {
        return videoRefinePrompt;
    }

    public void setVideoRefinePrompt(String videoRefinePrompt) {
        this.videoRefinePrompt = videoRefinePrompt;
    }

    public int getMaxHistoryTurns() {
        return maxHistoryTurns;
    }

    public void setMaxHistoryTurns(int maxHistoryTurns) {
        this.maxHistoryTurns = maxHistoryTurns;
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

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public String getKnowledgeCutoff() {
        return knowledgeCutoff;
    }

    public void setKnowledgeCutoff(String knowledgeCutoff) {
        this.knowledgeCutoff = knowledgeCutoff;
    }

    /** 解析 {@link #knowledgeCutoff}，非法或空则回退 2025-06。 */
    public YearMonth getKnowledgeCutoffYearMonth() {
        if (knowledgeCutoff == null || knowledgeCutoff.isBlank()) {
            return YearMonth.of(2025, 6);
        }
        try {
            return YearMonth.parse(knowledgeCutoff.trim());
        } catch (DateTimeParseException e) {
            return YearMonth.of(2025, 6);
        }
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
