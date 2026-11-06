package com.github.wechat.cloudbot.modules.weather.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.wechat.cloudbot.modules.weather.api.WeatherGateway;
import com.github.wechat.cloudbot.platform.config.ErrorCode;
import com.github.wechat.cloudbot.platform.config.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高德天气：地理编码解析城市 → weatherInfo 查询，返回格式化摘要。
 * 仅支持中国大陆城市；海外地名或地理编码错配会直接失败。
 */
@Component
public class AmapWeatherClient implements WeatherGateway {

    private static final Logger log = LoggerFactory.getLogger(AmapWeatherClient.class);

    /** 明显海外/非大陆地名：不走高德，避免误匹配到国内楼盘。 */
    private static final Pattern OVERSEAS =
            Pattern.compile(
                    ".*(美国|洛杉矶|纽约|旧金山|西雅图|芝加哥|华盛顿|拉斯维加斯|"
                            + "日本|东京|大阪|京都|北海道|"
                            + "韩国|首尔|釜山|"
                            + "英国|伦敦|法国|巴黎|德国|柏林|意大利|罗马|"
                            + "澳大利亚|悉尼|墨尔本|加拿大|多伦多|温哥华|"
                            + "新加坡|泰国|曼谷|迪拜|俄罗斯|莫斯科|"
                            + "海外|国外|境外|"
                            + "los\\s*angeles|new\\s*york|san\\s*francisco|tokyo|osaka|"
                            + "london|paris|berlin|sydney|melbourne|toronto|singapore).*",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern CHINESE_TOKEN = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");

    private final RestClient restClient;
    private final AmapProperties properties;

    public AmapWeatherClient(
            @Qualifier("amapRestClient") RestClient restClient, AmapProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        return properties.canQuery();
    }

    @Override
    public String query(String city, String extensions) {
        if (!properties.canQuery()) {
            throw new IllegalStateException("高德天气未启用或 AMAP_API_KEY 为空");
        }
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("weather city empty");
        }

        String cityQuery = city.trim();
        if (looksOverseas(cityQuery)) {
            throw new IllegalStateException(
                    "高德天气仅支持中国大陆城市，无法查询「" + cityQuery + "」");
        }

        String ext = normalizeExtensions(extensions);
        ResolvedCity resolved = resolveCity(cityQuery);
        JsonNode root = fetchWeather(resolved.adcode(), ext);
        String formatted = formatWeather(root, resolved, ext);
        if (formatted.isBlank()) {
            throw new IllegalStateException("高德返回空天气结果");
        }
        log.info(
                "高德天气完成 city={}, adcode={}, extensions={}, chars={}",
                resolved.displayName(),
                resolved.adcode(),
                ext,
                formatted.length());
        return formatted;
    }

    @Override
    public boolean looksOverseas(String text) {
        return text != null && OVERSEAS.matcher(text).matches();
    }

    private ResolvedCity resolveCity(String city) {
        try {
            JsonNode root =
                    restClient
                            .get()
                            .uri(
                                    uriBuilder ->
                                            uriBuilder
                                                    .path("/v3/geocode/geo")
                                                    .queryParam("key", properties.getApiKey())
                                                    .queryParam("address", city)
                                                    .queryParam("output", "JSON")
                                                    .build())
                            .retrieve()
                            .body(JsonNode.class);
            assertAmapOk(root, "地理编码");
            JsonNode geocodes = root.path("geocodes");
            if (!geocodes.isArray() || geocodes.isEmpty()) {
                throw new IllegalStateException("无法识别城市：「" + city + "」");
            }
            JsonNode first = geocodes.get(0);
            assertGeocodeMatchesQuery(city, first);
            String adcode = first.path("adcode").asText("").trim();
            if (adcode.isBlank()) {
                throw new IllegalStateException("城市无 adcode：「" + city + "」");
            }
            // 天气接口对地级市更稳：取前 4 位 + 00（如 330106 → 330100）
            String weatherAdcode = toCityAdcode(adcode);
            String formatted = first.path("formatted_address").asText("").trim();
            String display =
                    !formatted.isBlank()
                            ? formatted
                            : firstText(first, "city", "district", "province");
            if (display.isBlank()) {
                display = city;
            }
            return new ResolvedCity(weatherAdcode, display);
        } catch (RestClientResponseException e) {
            log.error("{}高德地理编码 HTTP {} : {}",
                    ErrorCode.WEATHER_API_ERROR.prefix(),
                    e.getStatusCode().value(),
                   Strings.truncate(e.getResponseBodyAsString(), 400));
            throw new IllegalStateException("高德地理编码 API error: " + e.getStatusCode().value(), e);
        }
    }

    /**
     * 防止「洛杉矶」被高德误匹配到国内同名楼盘/小区。
     *
     * <p>允许「杭州余杭区」对齐「浙江省杭州市余杭区」：去掉省/市/区等行政区划字后再比包含关系。
     */
    private static void assertGeocodeMatchesQuery(String query, JsonNode geo) {
        String haystack =
                (text(geo, "country")
                                + text(geo, "province")
                                + text(geo, "city")
                                + text(geo, "district")
                                + text(geo, "formatted_address"))
                        .replace("[]", "");
        String longest = longestChineseToken(query);
        if (longest.isBlank()) {
            // 纯英文地名：高德国内结果几乎不可能正确
            if (query.chars().anyMatch(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
                throw new IllegalStateException(
                        "高德天气仅支持中国大陆城市，无法查询「" + query + "」");
            }
            return;
        }
        String needle = longest;
        String strippedTail = needle.replaceAll("(特别行政区|省|市|区|县|州|盟|旗)$", "");
        if (haystack.contains(needle)
                || (strippedTail.length() >= 2 && haystack.contains(strippedTail))) {
            return;
        }
        // 「杭州余杭区」→「杭州余杭」；「杭州市余杭区」→「杭州余杭」
        String qNorm = stripAdminMarkers(needle);
        String hNorm = stripAdminMarkers(haystack);
        if (qNorm.length() >= 2 && hNorm.contains(qNorm)) {
            return;
        }
        String resolved =
                text(geo, "formatted_address").isBlank()
                        ? firstText(geo, "city", "district", "province")
                        : text(geo, "formatted_address");
        throw new IllegalStateException(
                "高德未能正确定位「"
                        + query
                        + "」（解析到："
                        + (resolved.isBlank() ? "未知地点" : resolved)
                        + "）。仅支持中国大陆城市。");
    }

    /** 去掉省/市/区等行政区划标记，便于复合地名对齐。 */
    private static String stripAdminMarkers(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("特别行政区|自治区|省|市|区|县|州|盟|旗", "");
    }

    private static String longestChineseToken(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher m = CHINESE_TOKEN.matcher(text);
        String best = "";
        while (m.find()) {
            String t = m.group();
            if (t.length() > best.length()) {
                best = t;
            }
        }
        return best;
    }

    private JsonNode fetchWeather(String adcode, String extensions) {
        try {
            JsonNode root =
                    restClient
                            .get()
                            .uri(
                                    uriBuilder ->
                                            uriBuilder
                                                    .path("/v3/weather/weatherInfo")
                                                    .queryParam("key", properties.getApiKey())
                                                    .queryParam("city", adcode)
                                                    .queryParam("extensions", extensions)
                                                    .queryParam("output", "JSON")
                                                    .build())
                            .retrieve()
                            .body(JsonNode.class);
            assertAmapOk(root, "天气查询");
            return root;
        } catch (RestClientResponseException e) {
            log.error("{}高德天气 HTTP {} : {}",
                    ErrorCode.WEATHER_API_ERROR.prefix(),
                    e.getStatusCode().value(),
                   Strings.truncate(e.getResponseBodyAsString(), 400));
            throw new IllegalStateException("高德天气 API error: " + e.getStatusCode().value(), e);
        }
    }

    private String formatWeather(JsonNode root, ResolvedCity city, String extensions) {
        StringBuilder sb = new StringBuilder();
        sb.append("查询地点：").append(city.displayName()).append('\n');
        sb.append("城市编码：").append(city.adcode()).append('\n');

        if ("base".equals(extensions)) {
            appendLives(sb, root.path("lives"));
        } else {
            appendForecasts(sb, root.path("forecasts"));
        }
        return sb.toString().trim();
    }

    private static void appendLives(StringBuilder sb, JsonNode lives) {
        if (!lives.isArray() || lives.isEmpty()) {
            return;
        }
        sb.append("\n【实况】\n");
        for (JsonNode live : lives) {
            String place = joinNonBlank(" ", text(live, "province"), text(live, "city"));
            if (!place.isBlank()) {
                sb.append(place).append('\n');
            }
            sb.append("天气：").append(text(live, "weather")).append('\n');
            sb.append("气温：").append(text(live, "temperature")).append("℃\n");
            String direction = text(live, "winddirection");
            String power = text(live, "windpower");
            if (!direction.isBlank() || !power.isBlank()) {
                sb.append("风力：");
                if (!direction.isBlank()) {
                    sb.append(direction).append("风");
                }
                if (!power.isBlank()) {
                    sb.append(power).append("级");
                }
                sb.append('\n');
            }
            String humidity = text(live, "humidity");
            if (!humidity.isBlank()) {
                sb.append("湿度：").append(humidity).append("%\n");
            }
            String reporttime = text(live, "reporttime");
            if (!reporttime.isBlank()) {
                sb.append("发布时间：").append(reporttime).append('\n');
            }
            sb.append('\n');
        }
    }

    private static void appendForecasts(StringBuilder sb, JsonNode forecasts) {
        if (!forecasts.isArray() || forecasts.isEmpty()) {
            return;
        }
        for (JsonNode forecast : forecasts) {
            String place = joinNonBlank(" ", text(forecast, "province"), text(forecast, "city"));
            sb.append("\n【预报】");
            if (!place.isBlank()) {
                sb.append(' ').append(place);
            }
            sb.append('\n');
            String reporttime = text(forecast, "reporttime");
            if (!reporttime.isBlank()) {
                sb.append("发布时间：").append(reporttime).append('\n');
            }
            JsonNode casts = forecast.path("casts");
            if (!casts.isArray()) {
                continue;
            }
            int n = 0;
            for (JsonNode cast : casts) {
                if (n++ >= 4) {
                    break;
                }
                sb.append(text(cast, "date"));
                String week = text(cast, "week");
                if (!week.isBlank()) {
                    sb.append("（周").append(week).append('）');
                }
                sb.append(" 白天")
                        .append(text(cast, "dayweather"))
                        .append(' ')
                        .append(text(cast, "daytemp"))
                        .append("℃ / 夜间")
                        .append(text(cast, "nightweather"))
                        .append(' ')
                        .append(text(cast, "nighttemp"))
                        .append("℃");
                String daywind = text(cast, "daywind");
                String daypower = text(cast, "daypower");
                if (!daywind.isBlank() || !daypower.isBlank()) {
                    sb.append("，白天");
                    if (!daywind.isBlank()) {
                        sb.append(daywind).append("风");
                    }
                    if (!daypower.isBlank()) {
                        sb.append(daypower).append("级");
                    }
                }
                sb.append('\n');
            }
        }
    }

    private String normalizeExtensions(String extensions) {
        if (extensions == null || extensions.isBlank()) {
            String def = properties.getDefaultExtensions();
            return "base".equalsIgnoreCase(def) ? "base" : "all";
        }
        return "base".equalsIgnoreCase(extensions.trim()) ? "base" : "all";
    }

    /** 区县级 adcode 转为地级市（末两位置 0），提高天气命中率。 */
    private static String toCityAdcode(String adcode) {
        if (adcode != null && adcode.length() == 6 && adcode.chars().allMatch(Character::isDigit)) {
            return adcode.substring(0, 4) + "00";
        }
        return adcode;
    }

    private static void assertAmapOk(JsonNode root, String action) {
        if (root == null) {
            throw new IllegalStateException("高德" + action + "返回空响应");
        }
        String status = root.path("status").asText("");
        if (!"1".equals(status)) {
            String info = root.path("info").asText(root.path("infocode").asText("unknown"));
            throw new IllegalStateException("高德" + action + "失败：" + info);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return "";
        }
        if (n.isArray()) {
            return n.isEmpty() ? "" : n.get(0).asText("").trim();
        }
        return n.asText("").trim();
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String t = text(node, f);
            if (!t.isBlank() && !"[]".equals(t)) {
                return t;
            }
        }
        return "";
    }

    private static String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank() || "[]".equals(p)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(p);
        }
        return sb.toString();
    }


    private record ResolvedCity(String adcode, String displayName) {}
}
