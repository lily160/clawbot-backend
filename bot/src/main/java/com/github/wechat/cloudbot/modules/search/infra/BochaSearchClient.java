package com.github.wechat.cloudbot.modules.search.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.wechat.cloudbot.modules.search.api.SearchGateway;
import com.github.wechat.cloudbot.platform.config.ErrorCode;
import com.github.wechat.cloudbot.platform.config.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.Map;

/**
 * 博查 Web Search：返回格式化摘要文本，交给 DeepSeek 润色。
 */
@Component
public class BochaSearchClient implements SearchGateway {

    private static final Logger log = LoggerFactory.getLogger(BochaSearchClient.class);

    private final RestClient restClient;
    private final BochaProperties properties;

    public BochaSearchClient(
            @Qualifier("bochaRestClient") RestClient restClient, BochaProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public boolean isAvailable() {
        return properties.canSearch();
    }

    /**
     * @param query     搜索词
     * @param freshness 可选覆盖默认时效；空则用配置
     * @return 给大模型看的纯文本摘要（已截断）
     */
    @Override
    public String search(String query, String freshness) {
        if (!properties.canSearch()) {
            throw new IllegalStateException("博查未启用或 BOCHA_API_KEY 为空");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("search query empty");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("query", query.trim());
        body.put(
                "freshness",
                freshness == null || freshness.isBlank()
                        ? properties.getFreshness()
                        : freshness.trim());
        body.put("summary", properties.isSummary());
        body.put("count", Math.max(1, Math.min(properties.getCount(), 20)));

        try {
            JsonNode root =
                    restClient
                            .post()
                            .uri("/web-search")
                            .body(body)
                            .retrieve()
                            .body(JsonNode.class);

            String formatted = formatResults(root);
            if (formatted.isBlank()) {
                throw new IllegalStateException("博查返回空搜索结果");
            }
            log.info("博查搜索完成 query={}, chars={}", query, formatted.length());
            return formatted;
        } catch (RestClientResponseException e) {
            log.error("{}博查 HTTP {} : {}",
                    ErrorCode.SEARCH_API_ERROR.prefix(),
                    e.getStatusCode().value(),
                   Strings.truncate(e.getResponseBodyAsString(), 400));
            throw new IllegalStateException("博查 API error: " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            throw new IllegalStateException("博查 search failed: " + e.getMessage(), e);
        }
    }

    private static String formatResults(JsonNode root) {
        if (root == null) {
            return "";
        }
        JsonNode pages = root.path("data").path("webPages").path("value");
        if (!pages.isArray() || pages.isEmpty()) {
            // 兼容其它结构
            pages = root.path("data").path("webpage").path("value");
        }
        if (!pages.isArray() || pages.isEmpty()) {
            return Strings.truncate(root.toString(), 2000);
        }

        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (JsonNode page : pages) {
            String title = firstText(page, "name", "title");
            String url = firstText(page, "url", "displayUrl");
            String summary = firstText(page, "summary", "snippet", "description");
            if (title.isBlank() && summary.isBlank()) {
                continue;
            }
            sb.append(i++)
                    .append(". ")
                    .append(title.isBlank() ? "(无标题)" : title)
                    .append('\n');
            if (!summary.isBlank()) {
                sb.append("   ").append(Strings.truncate(summary, 400)).append('\n');
            }
            if (!url.isBlank()) {
                sb.append("   来源: ").append(url).append('\n');
            }
            sb.append('\n');
            if (i > 10) {
                break;
            }
        }
        return sb.toString().trim();
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode n = node.get(f);
            if (n != null && !n.isNull()) {
                String t = n.asText("").trim();
                if (!t.isBlank()) {
                    return t;
                }
            }
        }
        return "";
    }

}
