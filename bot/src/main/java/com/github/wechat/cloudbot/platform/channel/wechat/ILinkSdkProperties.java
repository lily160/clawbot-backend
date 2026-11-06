package com.github.wechat.cloudbot.platform.channel.wechat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 从 application.yml 的 {@code ilink.*} 绑定 SDK 配置。
 *
 * <p>当前官方 iLink Bot 通道一般不需要 AppKey；此处预留扩展字段，便于后续切换网关。
 */
@ConfigurationProperties(prefix = "ilink")
public class ILinkSdkProperties {

    /** 预留：部分第三方网关可能需要的 AppKey（当前 SDK 未使用）。 */
    private String appKey = "";

    /** 预留：AppSecret（当前 SDK 未使用）。 */
    private String appSecret = "";

    private long connectTimeoutMs = 35000L;
    private long readTimeoutMs = 35000L;
    private long writeTimeoutMs = 35000L;
    private int httpMaxRetries = 3;
    private long retryBaseDelayMs = 1000L;
    private long retryMaxDelayMs = 10000L;
    private boolean retryJitterEnabled = true;
    private long loginTimeoutMs = 120000L;
    private boolean heartbeatEnabled = true;
    private long heartbeatIntervalMs = 30000L;
    private String channelVersion = "1.0.0";
    private String routeTag = "";

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
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

    public long getWriteTimeoutMs() {
        return writeTimeoutMs;
    }

    public void setWriteTimeoutMs(long writeTimeoutMs) {
        this.writeTimeoutMs = writeTimeoutMs;
    }

    public int getHttpMaxRetries() {
        return httpMaxRetries;
    }

    public void setHttpMaxRetries(int httpMaxRetries) {
        this.httpMaxRetries = httpMaxRetries;
    }

    public long getRetryBaseDelayMs() {
        return retryBaseDelayMs;
    }

    public void setRetryBaseDelayMs(long retryBaseDelayMs) {
        this.retryBaseDelayMs = retryBaseDelayMs;
    }

    public long getRetryMaxDelayMs() {
        return retryMaxDelayMs;
    }

    public void setRetryMaxDelayMs(long retryMaxDelayMs) {
        this.retryMaxDelayMs = retryMaxDelayMs;
    }

    public boolean isRetryJitterEnabled() {
        return retryJitterEnabled;
    }

    public void setRetryJitterEnabled(boolean retryJitterEnabled) {
        this.retryJitterEnabled = retryJitterEnabled;
    }

    public long getLoginTimeoutMs() {
        return loginTimeoutMs;
    }

    public void setLoginTimeoutMs(long loginTimeoutMs) {
        this.loginTimeoutMs = loginTimeoutMs;
    }

    public boolean isHeartbeatEnabled() {
        return heartbeatEnabled;
    }

    public void setHeartbeatEnabled(boolean heartbeatEnabled) {
        this.heartbeatEnabled = heartbeatEnabled;
    }

    public long getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
        this.heartbeatIntervalMs = heartbeatIntervalMs;
    }

    public String getChannelVersion() {
        return channelVersion;
    }

    public void setChannelVersion(String channelVersion) {
        this.channelVersion = channelVersion;
    }

    public String getRouteTag() {
        return routeTag;
    }

    public void setRouteTag(String routeTag) {
        this.routeTag = routeTag;
    }
}
