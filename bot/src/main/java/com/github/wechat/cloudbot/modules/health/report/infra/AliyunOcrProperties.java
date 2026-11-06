package com.github.wechat.cloudbot.modules.health.report.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云 OCR 配置，绑定到 {@code health.report.aliyun-ocr}。
 * 支持两种鉴权方式：API市场 AppCode / 官方 API AccessKey。
 */
@ConfigurationProperties(prefix = "health.report.aliyun-ocr")
public class AliyunOcrProperties {

    /** API市场 AppCode（简单鉴权） */
    private String appCode = "";
    /** 官方API AccessKeyId */
    private String accessKeyId = "";
    /** 官方API AccessKeySecret */
    private String accessKeySecret = "";
    /** OCR接口地址 */
    private String endpoint = "https://gjbsb.market.alicloudapi.com/ocrservice/advanced";

    public String getAppCode() { return appCode; }
    public void setAppCode(String appCode) { this.appCode = appCode; }

    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }

    public String getAccessKeySecret() { return accessKeySecret; }
    public void setAccessKeySecret(String accessKeySecret) { this.accessKeySecret = accessKeySecret; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public boolean isConfigured() {
        return (appCode != null && !appCode.isBlank())
                || (accessKeyId != null && !accessKeyId.isBlank()
                    && accessKeySecret != null && !accessKeySecret.isBlank());
    }

    /** 是否使用官方API（AccessKey鉴权） */
    public boolean useAccessKey() {
        return accessKeyId != null && !accessKeyId.isBlank()
                && accessKeySecret != null && !accessKeySecret.isBlank();
    }
}
