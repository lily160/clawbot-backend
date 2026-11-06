package com.github.wechat.cloudbot.modules.browse.infra;

import com.github.wechat.cloudbot.modules.browse.api.BrowseGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 简易网页抓取：拉取 HTML/文本并去标签截断，供 Agent Loop 回灌。
 */
@Component
public class SimpleBrowseClient implements BrowseGateway {

    private static final Logger log = LoggerFactory.getLogger(SimpleBrowseClient.class);

    private static final Pattern SCRIPT_STYLE =
            Pattern.compile("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>");
    private static final Pattern TAGS = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern MULTI_NL = Pattern.compile("\\n{3,}");

    private final RestClient restClient;
    private final BrowseProperties properties;

    public SimpleBrowseClient(
            @Qualifier("browseRestClient") RestClient restClient, BrowseProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        return properties.isEnabled();
    }

    @Override
    public String fetchPage(String url) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("browse 已关闭");
        }
        String normalized = normalizeUrl(url);
        try {
            @SuppressWarnings("null")
            java.net.URI browseUri = URI.create(normalized);
            String body =
                    restClient
                            .get()
                            .uri(browseUri)
                            .accept(MediaType.TEXT_HTML, MediaType.TEXT_PLAIN, MediaType.ALL)
                            .retrieve()
                            .body(String.class);
            if (body == null || body.isBlank()) {
                throw new IllegalStateException("页面内容为空：" + normalized);
            }
            String text = htmlToText(body);
            int max = Math.max(500, properties.getMaxChars());
            if (text.length() > max) {
                text = text.substring(0, max) + "\n…(已截断)";
            }
            String result = "【URL】\n" + normalized + "\n\n【正文摘要】\n" + text;
            log.info("browse_url 完成 url={}, chars={}", normalized, text.length());
            return result;
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(
                    "抓取失败 HTTP " + e.getStatusCode().value() + "：" + normalized, e);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("抓取失败：" + e.getMessage(), e);
        }
    }

    private static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url empty");
        }
        String u = url.trim();
        if (u.startsWith("<") && u.endsWith(">")) {
            u = u.substring(1, u.length() - 1).trim();
        }
        if (!u.contains("://")) {
            u = "https://" + u;
        }
        URI uri = URI.create(u);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("仅支持 http/https：" + u);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("无效 URL：" + u);
        }
        return uri.toString();
    }

    static String htmlToText(String html) {
        String s = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        s = s.replaceAll("(?is)<br\\s*/?>", "\n");
        s = s.replaceAll("(?is)</p>", "\n");
        s = s.replaceAll("(?is)</div>", "\n");
        s = s.replaceAll("(?is)</li>", "\n");
        s = s.replaceAll("(?is)</h[1-6]>", "\n");
        s = TAGS.matcher(s).replaceAll(" ");
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        s = WHITESPACE.matcher(s).replaceAll(" ");
        s = MULTI_NL.matcher(s.replace("\r\n", "\n")).replaceAll("\n\n");
        return s.trim();
    }
}
