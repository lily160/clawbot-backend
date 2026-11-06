package com.github.wechat.cloudbot.modules.location.api;

/**
 * 地点检索 / 地理编码网关（高德）。
 */
public interface LocationGateway {

    boolean isAvailable();

    /**
     * 关键词搜周边/地点。
     *
     * @param keywords 关键词，如「咖啡」「地铁站」
     * @param city 城市/区县限定（可空），如「杭州」「余杭区」
     * @param street 街道/地标/详细地址（可空）；有则优先周边检索，精确到街道附近
     */
    String searchPoi(String keywords, String city, String street);

    /**
     * 地址 / 地名 → 结构化坐标与行政区划。
     *
     * @param address 地址或地名
     * @param city 可选城市偏置
     */
    String geocode(String address, String city);
}
