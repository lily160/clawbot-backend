package com.github.wechat.cloudbot.modules.health.booking.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;


/**
 * 健康挂号链路配置。
 */
@ConfigurationProperties(prefix = "health.booking")
public class HealthBookingProperties {

    private boolean enabled = false;
    private String baseUrl = "";
    private String token = "";
    private String appVersion = "";
    private String userAgent = "";
    private long connectTimeoutMs = 10000L;
    private long readTimeoutMs = 15000L;
    private int defaultRecommendLimit = 3;
    private MiniProgramTarget firstHospital = new MiniProgramTarget();
    private MiniProgramTarget secondHospital = new MiniProgramTarget();
    private MiniProgramTarget thirdHospital = new MiniProgramTarget();
    private MiniProgramTarget fallbackMiniProgram = new MiniProgramTarget();
    private String miniProgramHint = "请在对应医院小程序内完成最终确认和预约提交。";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
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

    public int getDefaultRecommendLimit() {
        return defaultRecommendLimit;
    }

    public void setDefaultRecommendLimit(int defaultRecommendLimit) {
        this.defaultRecommendLimit = defaultRecommendLimit;
    }

    public MiniProgramTarget getFirstHospital() {
        return firstHospital;
    }

    public void setFirstHospital(MiniProgramTarget firstHospital) {
        this.firstHospital = firstHospital;
    }

    public MiniProgramTarget getSecondHospital() {
        return secondHospital;
    }

    public void setSecondHospital(MiniProgramTarget secondHospital) {
        this.secondHospital = secondHospital;
    }

    public MiniProgramTarget getThirdHospital() {
        return thirdHospital;
    }

    public void setThirdHospital(MiniProgramTarget thirdHospital) {
        this.thirdHospital = thirdHospital;
    }

    public MiniProgramTarget getFallbackMiniProgram() {
        return fallbackMiniProgram;
    }

    public void setFallbackMiniProgram(MiniProgramTarget fallbackMiniProgram) {
        this.fallbackMiniProgram = fallbackMiniProgram;
    }

    public String getMiniProgramHint() {
        return miniProgramHint;
    }

    public void setMiniProgramHint(String miniProgramHint) {
        this.miniProgramHint = miniProgramHint;
    }

    public MiniProgramTarget resolveMiniProgram(String hospitalName) {
        if (hospitalName == null || hospitalName.isBlank()) {
            return fallbackMiniProgram;
        }
        if (hospitalName.contains("第一人民医院") || hospitalName.contains("一院")) {
            return firstHospital;
        }
        if (hospitalName.contains("二院")) {
            return secondHospital;
        }
        if (hospitalName.contains("第三人民医院") || hospitalName.contains("三院")) {
            return thirdHospital;
        }
        return fallbackMiniProgram;
    }

    public boolean hasRemoteConfig() {
        return baseUrl != null && !baseUrl.isBlank() && token != null && !token.isBlank();
    }

    public boolean canUseRemote() {
        return enabled && hasRemoteConfig();
    }

    public static class MiniProgramTarget {
        private String name = "";
        private String path = "";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }
}
