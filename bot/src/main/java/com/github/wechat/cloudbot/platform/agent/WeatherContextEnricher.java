package com.github.wechat.cloudbot.platform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * 天气工具上下文注入器：向 {@code query_weather} 工具参数中注入日期、时间上下文。
 *
 * <p>让 LLM 在查询天气时自动感知「今天几号」「现在几点」，避免模型臆测日期。
 */
@Component
public class WeatherContextEnricher implements ToolContextEnricher {

    public WeatherContextEnricher() {
    }

    @Override
    public boolean supports(String toolName) {
        return "query_weather".equals(toolName);
    }

    @Override
    public String enrich(String argumentsJson, String userId, String userContent) {
        // 不再直接使用——由 enrichNode 替代
        return argumentsJson;
    }

    @Override
    public ObjectNode enrichNode(ObjectNode args, String userId, String userContent,
                                  ObjectMapper mapper) {
        args.put("_today", LocalDate.now().toString());
        args.put("_time", LocalTime.now().truncatedTo(ChronoUnit.MINUTES).toString());
        if (userContent != null && !userContent.isBlank()) {
            String summary = extractWeatherContext(userContent);
            if (!summary.isEmpty()) {
                args.put("_context_summary", summary);
            }
        }
        return args;
    }

    /**
     * 从用户输入中提取天气相关的关键词摘要。
     */
    private static String extractWeatherContext(String text) {
        StringBuilder sb = new StringBuilder();
        if (containsAny(text, "明天", "后天", "未来", "预报", "这周", "下周")) {
            sb.append("请求含预报/未来时段");
        }
        if (containsAny(text, "现在", "目前", "实时", "今天")) {
            if (!sb.isEmpty()) sb.append("；");
            sb.append("请求含当前/实时信息");
        }
        if (containsAny(text, "雨", "雪", "雾", "霾", "风", "温度", "气温", "冷", "热")) {
            if (!sb.isEmpty()) sb.append("；");
            sb.append("请求含具体天气要素");
        }
        return sb.toString();
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
