package com.github.wechat.cloudbot.bootstrap;

import com.github.wechat.cloudbot.modules.browse.infra.BrowseProperties;
import com.github.wechat.cloudbot.modules.browse.infra.SimpleBrowseClient;
import com.github.wechat.cloudbot.modules.location.infra.AmapLocationClient;
import com.github.wechat.cloudbot.modules.location.infra.LocationProperties;
import com.github.wechat.cloudbot.modules.search.infra.BochaProperties;
import com.github.wechat.cloudbot.modules.search.infra.BochaSearchClient;
import com.github.wechat.cloudbot.modules.weather.infra.AmapProperties;
import com.github.wechat.cloudbot.modules.weather.infra.AmapWeatherClient;
import org.springframework.web.client.RestClient;

/**
 * 可选在线 API 冒烟（有对应环境变量才测）。
 *
 * <pre>
 * $env:AMAP_API_KEY="..."
 * $env:BOCHA_API_KEY="..."
 * mvn -q test-compile exec:java "-Dexec.classpathScope=test" \
 *   "-Dexec.mainClass=com.github.wechat.cloudbot.bootstrap.OnlineApiSmokeMain"
 * </pre>
 */
public final class OnlineApiSmokeMain {

    public static void main(String[] args) {
        int ran = 0;
        int failed = 0;

        String amap = env("AMAP_API_KEY");
        if (!amap.isBlank()) {
            ran++;
            try {
                checkWeather(amap);
                checkPoi(amap);
                System.out.println("AMAP_OK");
            } catch (Exception e) {
                failed++;
                System.err.println("AMAP_FAIL: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        } else {
            System.out.println("AMAP_SKIP (no AMAP_API_KEY)");
        }

        String bocha = env("BOCHA_API_KEY");
        if (!bocha.isBlank()) {
            ran++;
            try {
                checkBocha(bocha);
                System.out.println("BOCHA_OK");
            } catch (Exception e) {
                failed++;
                System.err.println("BOCHA_FAIL: " + e.getMessage());
                e.printStackTrace(System.err);
            }
        } else {
            System.out.println("BOCHA_SKIP (no BOCHA_API_KEY)");
        }

        try {
            checkBrowse();
            System.out.println("BROWSE_OK");
            ran++;
        } catch (Exception e) {
            failed++;
            System.err.println("BROWSE_FAIL: " + e.getMessage());
            e.printStackTrace(System.err);
        }

        if (failed > 0) {
            System.err.println(
                    "ONLINE_API_SMOKE_PARTIAL failed=" + failed + " ran=" + ran
                            + "（密钥无效/网络问题不阻断本地回归）");
            return;
        }
        System.out.println("ONLINE_API_SMOKE_OK ran=" + ran);
    }

    private static void checkWeather(String apiKey) {
        AmapProperties props = new AmapProperties();
        props.setEnabled(true);
        props.setApiKey(apiKey);
        props.setBaseUrl("https://restapi.amap.com");
        RestClient rc = RestClient.builder().baseUrl(props.getBaseUrl()).build();
        AmapWeatherClient client = new AmapWeatherClient(rc, props);
        String body = client.query("杭州", "base");
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("weather empty");
        }
        System.out.println("weather preview=" + preview(body));
    }

    private static void checkPoi(String apiKey) {
        LocationProperties props = new LocationProperties();
        props.setEnabled(true);
        props.setApiKey(apiKey);
        props.setBaseUrl("https://restapi.amap.com");
        RestClient rc = RestClient.builder().baseUrl(props.getBaseUrl()).build();
        AmapLocationClient client = new AmapLocationClient(rc, props);
        String body = client.searchPoi("停车场", "杭州", "西湖");
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("poi empty");
        }
        System.out.println("poi preview=" + preview(body));
    }

    private static void checkBocha(String apiKey) {
        BochaProperties props = new BochaProperties();
        props.setEnabled(true);
        props.setApiKey(apiKey);
        props.setBaseUrl("https://api.bochaai.com/v1");
        RestClient rc =
                RestClient.builder()
                        .baseUrl(props.getBaseUrl())
                        .defaultHeader("Authorization", "Bearer " + apiKey)
                        .build();
        BochaSearchClient client = new BochaSearchClient(rc, props);
        String body = client.search("杭州天气", "oneWeek");
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("bocha empty");
        }
        System.out.println("bocha preview=" + preview(body));
    }

    private static void checkBrowse() {
        BrowseProperties props = new BrowseProperties();
        props.setEnabled(true);
        RestClient rc = RestClient.builder().build();
        SimpleBrowseClient client = new SimpleBrowseClient(rc, props);
        String body = client.fetchPage("https://example.com");
        if (body == null || !body.toLowerCase().contains("example")) {
            throw new IllegalStateException("browse unexpected: " + preview(body));
        }
    }

    private static String env(String key) {
        String v = System.getenv(key);
        return v == null ? "" : v.trim();
    }

    private static String preview(String s) {
        if (s == null) {
            return "null";
        }
        String one = s.replace('\n', ' ');
        return one.length() <= 160 ? one : one.substring(0, 160) + "...";
    }
}
