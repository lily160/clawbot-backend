package com.github.wechat.cloudbot.modules.search.api;

/**
 * 联网搜索网关。
 */
public interface SearchGateway {

    boolean isAvailable();

    /**
     * @param freshness 可选时效（noLimit / oneDay / oneWeek / …）；空则用实现默认
     */
    String search(String query, String freshness);
}
