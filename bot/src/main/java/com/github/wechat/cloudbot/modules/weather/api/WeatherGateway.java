package com.github.wechat.cloudbot.modules.weather.api;

/**
 * 天气查询网关（高德）。
 */
public interface WeatherGateway {

    boolean isAvailable();

    /**
     * @param city 城市名（如「北京」「杭州市」），内部会解析为 adcode
     * @param extensions {@code base}=实况，{@code all}=预报；空则用配置默认
     * @return 给大模型润色用的纯文本摘要
     */
    String query(String city, String extensions);

    /** 明显海外/非大陆地名时，编排层应改走联网搜索。 */
    boolean looksOverseas(String text);
}
