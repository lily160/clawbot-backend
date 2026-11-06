package com.github.wechat.cloudbot.modules.media.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 火山方舟（Volcengine Ark）文生图 / 文生视频配置。
 *
 * <p>API Key 用环境变量 {@code ARK_API_KEY}；模型填控制台推理接入点 ID 或公开模型名。
 */
@ConfigurationProperties(prefix = "volcengine")
public class VolcengineProperties {

    private boolean enabled = true;

    private String apiKey = "";

    private String baseUrl = "https://ark.cn-beijing.volces.com/api/v3";

    /** 文生图推理接入点 ID */
    private String imageModel = "ep-m-20260718152120-z6dmr";

    /** 文生视频推理接入点 ID */
    private String videoModel = "ep-20260718153716-77shj";

    /** 生图尺寸：2K / 4K / 1024x1024 等 */
    private String imageSize = "2K";

    private boolean imageWatermark = false;

    private int videoDurationSeconds = 5;

    private String videoRatio = "16:9";

    private long videoPollIntervalMs = 10000L;

    private long videoPollTimeoutMs = 600000L;

    /** 异步媒体生成线程池大小。 */
    private int threadPoolSize = 2;

    private long connectTimeoutMs = 15000L;

    private long readTimeoutMs = 120000L;

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

    public String getImageModel() {
        return imageModel;
    }

    public void setImageModel(String imageModel) {
        this.imageModel = imageModel;
    }

    public String getVideoModel() {
        return videoModel;
    }

    public void setVideoModel(String videoModel) {
        this.videoModel = videoModel;
    }

    public String getImageSize() {
        return imageSize;
    }

    public void setImageSize(String imageSize) {
        this.imageSize = imageSize;
    }

    public boolean isImageWatermark() {
        return imageWatermark;
    }

    public void setImageWatermark(boolean imageWatermark) {
        this.imageWatermark = imageWatermark;
    }

    public int getVideoDurationSeconds() {
        return videoDurationSeconds;
    }

    public void setVideoDurationSeconds(int videoDurationSeconds) {
        this.videoDurationSeconds = videoDurationSeconds;
    }

    public String getVideoRatio() {
        return videoRatio;
    }

    public void setVideoRatio(String videoRatio) {
        this.videoRatio = videoRatio;
    }

    public long getVideoPollIntervalMs() {
        return videoPollIntervalMs;
    }

    public void setVideoPollIntervalMs(long videoPollIntervalMs) {
        this.videoPollIntervalMs = videoPollIntervalMs;
    }

    public long getVideoPollTimeoutMs() {
        return videoPollTimeoutMs;
    }

    public void setVideoPollTimeoutMs(long videoPollTimeoutMs) {
        this.videoPollTimeoutMs = videoPollTimeoutMs;
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

    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    public void setThreadPoolSize(int threadPoolSize) {
        this.threadPoolSize = threadPoolSize;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean canGenerateImage() {
        return isEnabled() && hasApiKey() && imageModel != null && !imageModel.isBlank();
    }

    public boolean canGenerateVideo() {
        return isEnabled() && hasApiKey() && videoModel != null && !videoModel.isBlank();
    }
}
