package com.github.wechat.cloudbot.modules.browse.api;

/**
 * 网页抓取网关。
 */
public interface BrowseGateway {

    boolean isAvailable();

    /**
     * @param url 完整 URL（http/https）
     * @return 适合回灌模型的纯文本摘要（已截断）
     */
    String fetchPage(String url);
}
