package com.github.wechat.cloudbot.modules.location.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 高德地点检索（复用 {@code AMAP_API_KEY}）。
 */
@ConfigurationProperties(prefix = "location")
public class LocationProperties {

    private boolean enabled = true;

    private String apiKey = "";

    private String baseUrl = "https://restapi.amap.com";

    private int poiCount = 8;

    /** 有街道/地标时周边检索半径（米） */
    private int aroundRadiusMeters = 3000;

    private long connectTimeoutMs = 10000L;

    private long readTimeoutMs = 15000L;

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

    public int getPoiCount() {
        return poiCount;
    }

    public void setPoiCount(int poiCount) {
        this.poiCount = poiCount;
    }

    public int getAroundRadiusMeters() {
        return aroundRadiusMeters;
    }

    public void setAroundRadiusMeters(int aroundRadiusMeters) {
        this.aroundRadiusMeters = aroundRadiusMeters;
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

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean canQuery() {
        return isEnabled() && hasApiKey();
    }
}
