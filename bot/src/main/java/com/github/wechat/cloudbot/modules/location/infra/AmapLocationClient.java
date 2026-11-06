package com.github.wechat.cloudbot.modules.location.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.wechat.cloudbot.modules.location.api.LocationGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高德地点：关键字 POI / 街道周边检索 + 地理编码；结果附带可点击高德 URI。
 */
@Component
public class AmapLocationClient implements LocationGateway {

    private static final Logger log = LoggerFactory.getLogger(AmapLocationClient.class);

    private static final Pattern CITY_TOKEN =
            Pattern.compile("([\\u4e00-\\u9fa5]{2,10}?)(?:市|地区|州|盟)?");
    private static final Pattern DISTRICT_TOKEN =
            Pattern.compile("([\\u4e00-\\u9fa5]{2,10}?)(?:区|县|市|旗)$");

    private final RestClient restClient;
    private final LocationProperties properties;

    public AmapLocationClient(
            @Qualifier("locationRestClient") RestClient restClient, LocationProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        return properties.canQuery();
    }

    @Override
    public String searchPoi(String keywords, String city, String street) {
        if (!properties.canQuery()) {
            throw new IllegalStateException("地点查询未配置（请设置 AMAP_API_KEY）");
        }
        if (keywords == null || keywords.isBlank()) {
            throw new IllegalArgumentException("keywords empty");
        }

        String kw = keywords.trim();
        String streetTrim = street == null ? "" : street.trim();
        SearchScope scope = resolveSearchScope(kw, city);

        JsonNode root;
        String mode;
        try {
            if (!streetTrim.isBlank()) {
                String anchor = buildStreetAnchor(scope, streetTrim, city);
                String location = resolveLocation(anchor, scope.cityParam());
                root = fetchAround(kw, location);
                mode = "around";
                log.info(
                        "高德周边检索 anchor={}, location={}, keywords={}",
                        anchor,
                        location,
                        kw);
            } else {
                root = fetchText(scope.keywords(), scope.cityParam());
                mode = "text";
            }
            assertAmapOk(root, "地点检索");
            String formatted =
                    formatPois(root, scope.expectCity(), scope.expectDistrict(), mode, streetTrim);
            if (formatted.isBlank()) {
                throw new IllegalStateException(
                        "未找到与「"
                                + describeQuery(kw, city, streetTrim)
                                + "」匹配的地点（可补充更具体的街道/地标后重试）");
            }
            log.info(
                    "高德 POI 完成 mode={}, keywords={}, cityParam={}, street={}, chars={}",
                    mode,
                    scope.keywords(),
                    scope.cityParam(),
                    streetTrim,
                    formatted.length());
            return formatted;
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("高德地点 API error: " + e.getStatusCode().value(), e);
        }
    }

    @Override
    public String geocode(String address, String city) {
        if (!properties.canQuery()) {
            throw new IllegalStateException("地理编码未配置（请设置 AMAP_API_KEY）");
        }
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("address empty");
        }
        try {
            JsonNode root = fetchGeocode(address.trim(), city);
            String formatted = formatGeocodes(root);
            if (formatted.isBlank()) {
                throw new IllegalStateException("未能解析地址：" + address.trim());
            }
            log.info("高德 geocode 完成 address={}, chars={}", address, formatted.length());
            return formatted;
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("高德地理编码 API error: " + e.getStatusCode().value(), e);
        }
    }

    private String buildStreetAnchor(SearchScope scope, String street, String cityRaw) {
        StringBuilder sb = new StringBuilder();
        if (cityRaw != null && !cityRaw.isBlank()) {
            sb.append(cityRaw.trim());
        } else if (scope.expectCity() != null && !scope.expectCity().isBlank()) {
            sb.append(scope.expectCity());
            if (scope.expectDistrict() != null && !scope.expectDistrict().isBlank()) {
                sb.append(scope.expectDistrict());
            }
        }
        if (!sb.isEmpty() && !sb.toString().contains(street)) {
            sb.append(street);
        } else if (sb.isEmpty()) {
            sb.append(street);
        }
        return sb.toString();
    }

    private String resolveLocation(String address, String cityBias) {
        JsonNode root = fetchGeocode(address, cityBias);
        JsonNode first = root.path("geocodes").path(0);
        String location = text(first, "location");
        if (location.isBlank() || !location.contains(",")) {
            throw new IllegalStateException("未能定位街道/地标：「" + address + "」");
        }
        return location;
    }

    private JsonNode fetchText(String keywords, String cityParam) {
        return restClient
                .get()
                .uri(
                        uriBuilder -> {
                            var b =
                                    uriBuilder
                                            .path("/v3/place/text")
                                            .queryParam("key", properties.getApiKey())
                                            .queryParam("keywords", keywords)
                                            .queryParam("offset", pageSize())
                                            .queryParam("page", 1)
                                            .queryParam("extensions", "all");
                            if (cityParam != null && !cityParam.isBlank()) {
                                b.queryParam("city", cityParam);
                                b.queryParam("citylimit", "true");
                            }
                            return b.build();
                        })
                .retrieve()
                .body(JsonNode.class);
    }

    private JsonNode fetchAround(String keywords, String location) {
        int radius = Math.max(500, Math.min(properties.getAroundRadiusMeters(), 50000));
        return restClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/v3/place/around")
                                        .queryParam("key", properties.getApiKey())
                                        .queryParam("location", location)
                                        .queryParam("keywords", keywords)
                                        .queryParam("radius", radius)
                                        .queryParam("offset", pageSize())
                                        .queryParam("page", 1)
                                        .queryParam("extensions", "all")
                                        .queryParam("sortrule", "distance")
                                        .build())
                .retrieve()
                .body(JsonNode.class);
    }

    private int pageSize() {
        return Math.max(1, Math.min(properties.getPoiCount(), 20));
    }

    private SearchScope resolveSearchScope(String keywords, String city) {
        if (city == null || city.isBlank()) {
            return new SearchScope(null, keywords, null, null);
        }
        String cityRaw = city.trim();
        try {
            JsonNode root = fetchGeocode(cityRaw, null);
            JsonNode first = root.path("geocodes").path(0);
            if (first != null && !first.isMissingNode()) {
                String adcode = text(first, "adcode");
                String cityName = stripAdminSuffix(text(first, "city"));
                String district = stripAdminSuffix(text(first, "district"));
                if (!adcode.isBlank()) {
                    log.info(
                            "POI city 已解析为 adcode={}, city={}, district={}, raw={}",
                            adcode,
                            cityName,
                            district,
                            cityRaw);
                    return new SearchScope(adcode, keywords, cityName, district);
                }
            }
        } catch (Exception e) {
            log.warn("POI city 地理编码失败，改用市名降级 raw={}: {}", cityRaw, e.getMessage());
        }

        String districtHint = extractDistrictHint(cityRaw);
        String cityHint = extractCityHint(cityRaw);
        String kw = keywords;
        if (districtHint != null
                && !districtHint.isBlank()
                && !kw.contains(districtHint)
                && (cityHint == null || !districtHint.equals(cityHint))) {
            kw = districtHint + " " + kw;
        }
        String cityParam = cityHint != null && !cityHint.isBlank() ? cityHint : cityRaw;
        return new SearchScope(cityParam, kw, cityHint, districtHint);
    }

    private JsonNode fetchGeocode(String address, String city) {
        JsonNode root =
                restClient
                        .get()
                        .uri(
                                uriBuilder -> {
                                    var b =
                                            uriBuilder
                                                    .path("/v3/geocode/geo")
                                                    .queryParam("key", properties.getApiKey())
                                                    .queryParam("address", address);
                                    if (city != null && !city.isBlank()) {
                                        b.queryParam("city", city.trim());
                                    }
                                    return b.build();
                                })
                        .retrieve()
                        .body(JsonNode.class);
        assertAmapOk(root, "地理编码");
        return root;
    }

    private static String formatPois(
            JsonNode root,
            String expectCity,
            String expectDistrict,
            String mode,
            String street) {
        JsonNode pois = root.path("pois");
        if (!pois.isArray() || pois.isEmpty()) {
            return "";
        }
        List<JsonNode> matched = new ArrayList<>();
        List<JsonNode> others = new ArrayList<>();
        for (JsonNode poi : pois) {
            if (text(poi, "name").isBlank()) {
                continue;
            }
            if (matchesExpectedRegion(poi, expectCity, expectDistrict)) {
                matched.add(poi);
            } else {
                others.add(poi);
            }
        }
        List<JsonNode> use;
        if (!matched.isEmpty()) {
            use = matched;
        } else if ("around".equals(mode)) {
            // 周边检索已按街道坐标锚定，不过度丢弃
            use = others;
        } else if ((expectCity != null && !expectCity.isBlank())
                || (expectDistrict != null && !expectDistrict.isBlank())) {
            log.warn(
                    "POI 结果与期望区域不符 expectCity={}, expectDistrict={}, rawCount={}",
                    expectCity,
                    expectDistrict,
                    pois.size());
            return "";
        } else {
            use = others;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【检索说明】");
        if ("around".equals(mode) && street != null && !street.isBlank()) {
            sb.append("已按街道/地标「").append(street).append("」周边检索；");
        } else {
            sb.append("关键字区域检索；");
        }
        sb.append("回复用户时按此排版：店名标题 + 子项「• 📍地址/距离」「• 📞电话」「• 🔗 [查看地图](高德URL)」；禁止裸 URL。\n\n");

        int i = 1;
        for (JsonNode poi : use) {
            String name = text(poi, "name");
            String type = text(poi, "type");
            String addr = text(poi, "address");
            String loc = text(poi, "location");
            String pname = text(poi, "pname");
            String cityname = text(poi, "cityname");
            String adname = text(poi, "adname");
            String poiId = text(poi, "id");
            String distance = text(poi, "distance");
            String tel = text(poi, "tel");

            sb.append(i++).append(". ").append(name);
            if (!type.isBlank()) {
                sb.append("（").append(shortType(type)).append("）");
            }
            sb.append('\n');
            String region = joinNonBlank("", pname, cityname, adname);
            if (!region.isBlank() || !addr.isBlank()) {
                sb.append("   地址：").append(region);
                if (!addr.isBlank() && !region.contains(addr)) {
                    sb.append(addr);
                }
                sb.append('\n');
            }
            if (!distance.isBlank()) {
                sb.append("   距离：约 ").append(distance).append(" 米\n");
            }
            if (!tel.isBlank()) {
                sb.append("   电话：").append(tel).append('\n');
            }
            String link = buildAmapLink(poiId, loc, name);
            if (!link.isBlank()) {
                sb.append("   地图：").append(markdownAmapLink(link)).append('\n');
            }
            sb.append('\n');
            if (i > 10) {
                break;
            }
        }
        return sb.toString().trim();
    }

    /** 微信侧渲染为短链「查看地图」，避免裸 URL 刷屏。 */
    static String markdownAmapLink(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return "[查看地图](" + url.trim() + ")";
    }

    /**
     * 优先 POI ID；否则用经纬度。callnative=1 便于手机微信点击调起高德 App。
     */
    static String buildAmapLink(String poiId, String location, String name) {
        String encodedName = urlEncode(name == null || name.isBlank() ? "地点" : name);
        if (poiId != null && !poiId.isBlank()) {
            return "https://uri.amap.com/marker?poiid="
                    + urlEncode(poiId)
                    + "&name="
                    + encodedName
                    + "&src=cloudbot&callnative=1";
        }
        if (location != null && location.contains(",")) {
            return "https://uri.amap.com/marker?position="
                    + location.trim()
                    + "&name="
                    + encodedName
                    + "&src=cloudbot&coordinate=gaode&callnative=1";
        }
        return "";
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String shortType(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String[] parts = type.split(";");
        return parts[parts.length - 1].trim();
    }

    private static boolean matchesExpectedRegion(
            JsonNode poi, String expectCity, String expectDistrict) {
        if ((expectCity == null || expectCity.isBlank())
                && (expectDistrict == null || expectDistrict.isBlank())) {
            return true;
        }
        String hay =
                (text(poi, "pname")
                                + text(poi, "cityname")
                                + text(poi, "adname")
                                + text(poi, "address"))
                        .replace("[]", "");
        if (expectDistrict != null && !expectDistrict.isBlank()) {
            if (hay.contains(expectDistrict)
                    || hay.contains(expectDistrict + "区")
                    || hay.contains(expectDistrict + "县")) {
                return true;
            }
            return expectCity != null
                    && !expectCity.isBlank()
                    && (hay.contains(expectCity) || hay.contains(expectCity + "市"));
        }
        return expectCity != null
                && !expectCity.isBlank()
                && (hay.contains(expectCity) || hay.contains(expectCity + "市"));
    }

    private static String formatGeocodes(JsonNode root) {
        JsonNode geos = root.path("geocodes");
        if (!geos.isArray() || geos.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【回复要求】若用户要看地图，请用 Markdown 短链「[查看地图](高德URL)」。\n\n");
        int i = 1;
        for (JsonNode geo : geos) {
            String formatted = text(geo, "formatted_address");
            String location = text(geo, "location");
            String level = text(geo, "level");
            String province = text(geo, "province");
            String city = text(geo, "city");
            String district = text(geo, "district");
            if (formatted.isBlank() && location.isBlank()) {
                continue;
            }
            sb.append(i++)
                    .append(". ")
                    .append(formatted.isBlank() ? "(未知地址)" : formatted)
                    .append('\n');
            if (!location.isBlank()) {
                sb.append("   坐标：").append(location).append("（经度,纬度）\n");
            }
            String region = joinNonBlank(" ", province, city, district);
            if (!region.isBlank()) {
                sb.append("   行政区：").append(region).append('\n');
            }
            if (!level.isBlank()) {
                sb.append("   级别：").append(level).append('\n');
            }
            String link = buildAmapLink(null, location, formatted);
            if (!link.isBlank()) {
                sb.append("   地图：").append(markdownAmapLink(link)).append('\n');
            }
            sb.append('\n');
            if (i > 5) {
                break;
            }
        }
        return sb.toString().trim();
    }

    private static String describeQuery(String keywords, String city, String street) {
        StringBuilder sb = new StringBuilder();
        if (city != null && !city.isBlank()) {
            sb.append(city.trim()).append(' ');
        }
        if (street != null && !street.isBlank()) {
            sb.append(street.trim()).append(' ');
        }
        sb.append(keywords);
        return sb.toString().trim();
    }

    private static String extractCityHint(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        int idx = text.indexOf('市');
        if (idx >= 2) {
            return stripAdminSuffix(text.substring(0, idx));
        }
        Matcher m = CITY_TOKEN.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return stripAdminSuffix(text);
    }

    private static String extractDistrictHint(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher m = DISTRICT_TOKEN.matcher(text);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    private static String stripAdminSuffix(String s) {
        if (s == null || s.isBlank() || "[]".equals(s)) {
            return "";
        }
        return s.replaceAll("(特别行政区|自治区|省|市|区|县|州|盟|旗)$", "").trim();
    }

    private static void assertAmapOk(JsonNode root, String action) {
        if (root == null) {
            throw new IllegalStateException(action + "：高德返回空");
        }
        String status = text(root, "status");
        if (!"1".equals(status)) {
            String info = text(root, "info");
            throw new IllegalStateException(action + "失败：" + (info.isBlank() ? status : info));
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) {
            return "";
        }
        String t = n.asText("").trim();
        return "[]".equals(t) ? "" : t;
    }

    private static String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank() || "[]".equals(p)) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(sep);
            }
            sb.append(p.trim());
        }
        return sb.toString();
    }

    private record SearchScope(
            String cityParam, String keywords, String expectCity, String expectDistrict) {}
}
