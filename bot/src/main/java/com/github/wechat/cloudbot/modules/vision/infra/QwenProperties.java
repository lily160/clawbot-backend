package com.github.wechat.cloudbot.modules.vision.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 通义千问（DashScope）Chat API 配置，使用 OpenAI 兼容模式。
 *
 * <p>API Key 请通过环境变量 {@code DASHSCOPE_API_KEY} 注入，勿提交到仓库。
 */
@ConfigurationProperties(prefix = "qwen")
public class QwenProperties {

    /** 是否启用 LLM；关闭或 Key 为空时回退固定文案。 */
    private boolean enabled = true;

    private String apiKey = "";

    /** DashScope OpenAI 兼容模式地址。 */
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    /** 文本模型：qwen-turbo / qwen-plus / qwen-max */
    private String model = "qwen-plus";

    /** 视觉模型：qwen-vl-plus / qwen-vl-max */
    private String visionModel = "qwen-vl-plus";

    private String systemPrompt =
            "你是微信 Cloudbot 助手，回复简洁友好，适合即时消息。可以理解用户发送的图片并回答相关问题。";

    /** 用户只发图、无额外文字时的默认提问。 */
    private String imagePrompt = "请描述这张图片，并给出简洁、有帮助的回复。";

    /** 用户只发视频时的默认提问。 */
    private String videoPrompt = "请详细描述这段视频的关键内容与情节，便于后续助手回复用户。";

    /** 视频抽帧频率（帧/秒），越大越细、越贵越慢。 */
    private double videoFps = 2.0;

    /**
     * 提交给千问的视频体积上限（字节）。超出则友好提示，避免 Base64 请求过大失败。
     * 默认约 12MB。
     */
    private long maxVideoBytes = 12L * 1024 * 1024;

    /** 每个用户保留的最近对话轮数（一轮 = user + assistant）。 */
    private int maxHistoryTurns = 10;

    private long connectTimeoutMs = 10000L;

    private long readTimeoutMs = 180000L;

    private double temperature = 0.7;

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

    public String getVisionModel() {
        return visionModel;
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getImagePrompt() {
        return imagePrompt;
    }

    public void setImagePrompt(String imagePrompt) {
        this.imagePrompt = imagePrompt;
    }

    public String getVideoPrompt() {
        return videoPrompt;
    }

    public void setVideoPrompt(String videoPrompt) {
        this.videoPrompt = videoPrompt;
    }

    public double getVideoFps() {
        return videoFps;
    }

    public void setVideoFps(double videoFps) {
        this.videoFps = videoFps;
    }

    public long getMaxVideoBytes() {
        return maxVideoBytes;
    }

    public void setMaxVideoBytes(long maxVideoBytes) {
        this.maxVideoBytes = maxVideoBytes;
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

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
