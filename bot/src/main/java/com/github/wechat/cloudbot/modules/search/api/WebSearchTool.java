package com.github.wechat.cloudbot.modules.search.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.search.infra.BochaProperties;
import com.github.wechat.cloudbot.modules.search.service.RealtimeQueryDetector;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 工具层：联网搜索（web_search）。结果套 refine-prompt 供 Agent Loop 回灌。
 */
@Component
public class WebSearchTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);

    private final SearchGateway searchGateway;
    private final BochaProperties bochaProperties;
    private final ObjectMapper objectMapper;

    public WebSearchTool(
            SearchGateway searchGateway,
            BochaProperties bochaProperties,
            ObjectMapper objectMapper) {
        this.searchGateway = searchGateway;
        this.bochaProperties = bochaProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "web_search",
                "当用户询问需要联网核实的事实时必须调用：新闻、赛事冠军/比分/赛果、"
                        + "股价汇率、最新政策、模型知识截止之后发生的事件（如2026世界杯谁夺冠）、"
                        + "「今天/现在/最新/目前」类非天气问题。天气请用 query_weather。"
                        + "禁止对这类问题直接凭记忆回答。",
                """
                {"type":"object","properties":{"query":{"type":"string","description":"发给搜索引擎的查询词，简洁明确，可含城市/时间等关键约束"},"caption":{"type":"string","description":"发给用户的简短确认语，例如「好的，我先帮你查一下…」"},"freshness":{"type":"string","description":"可选时效：noLimit / oneDay / oneWeek / oneMonth / oneYear；新闻等实时问题用 oneDay"},"question":{"type":"string","description":"用户原问题（可选，编排层可注入）"}},"required":["query"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String query = ToolArgs.textAny(args, "query", "prompt");
            if (query.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "query empty");
            }
            if (!searchGateway.isAvailable()) {
                return ToolResult.fail(
                        call.id(), call.name(), "联网搜索未配置（请设置 BOCHA_API_KEY）。");
            }

            String question = ToolArgs.text(args, "question");
            if (question.isBlank()) {
                question = query;
            }
            query = RealtimeQueryDetector.enhanceSearchQuery(query, question);
            String freshness =
                    RealtimeQueryDetector.resolveFreshness(ToolArgs.text(args, "freshness"), question);

            String searchText = searchGateway.search(query, freshness);
            if ((looksLikeSportsResultQuestion(question) || looksLikeSportsResultQuestion(query))
                    && !isHistoricalSportsQuery(question, query)) {
                try {
                    String enQuery = "2026 FIFA World Cup final winner champion result";
                    String enText = searchGateway.search(enQuery, "oneDay");
                    searchText =
                            "【中文检索】\n" + searchText + "\n\n【英文检索】\n" + enText;
                    log.info("赛事问题已补充英文检索 query={}", enQuery);
                } catch (Exception enEx) {
                    log.warn("英文补充搜索失败，继续用中文结果: {}", enEx.getMessage());
                }
            }

            String today = LocalDate.now().toString();
            String wrapped =
                    bochaProperties
                            .getRefinePrompt()
                            .replace("{today}", today)
                            .replace("{search}", searchText)
                            .replace("{question}", question);
            return ToolResult.ok(call.id(), call.name(), wrapped);
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), e.getMessage());
        }
    }

    private static boolean looksLikeSportsResultQuestion(String text) {
        if (text == null) {
            return false;
        }
        return text.contains("世界杯")
                || text.contains("奥运会")
                || text.contains("欧洲杯")
                || text.contains("夺冠")
                || text.contains("冠军")
                || text.contains("赛果")
                || text.contains("比分");
    }

    /** 判断是否为历史赛事问题（提到非当前年份），避免把 2026 年检索结果混入对历史赛果的回答。 */
    private static boolean isHistoricalSportsQuery(String question, String query) {
        String text = (question + " " + query).toLowerCase();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(20\\d{2})").matcher(text);
        while (m.find()) {
            if (!"2026".equals(m.group(1))) {
                return true;
            }
        }
        return false;
    }
}
