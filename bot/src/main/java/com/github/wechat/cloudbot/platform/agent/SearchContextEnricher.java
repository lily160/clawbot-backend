package com.github.wechat.cloudbot.platform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 搜索工具上下文注入器：向 {@code web_search} 工具参数中注入今日日期、语言偏好等上下文。
 *
 * <p>帮助 LLM 生成更精准的搜索 query，避免搜索到过时的信息。
 */
@Component
public class SearchContextEnricher implements ToolContextEnricher {

    public SearchContextEnricher() {
    }

    @Override
    public boolean supports(String toolName) {
        return "web_search".equals(toolName);
    }

    @Override
    public String enrich(String argumentsJson, String userId, String userContent) {
        return argumentsJson;
    }

    @Override
    public ObjectNode enrichNode(ObjectNode args, String userId, String userContent,
                                  ObjectMapper mapper) {
        args.put("_today", LocalDate.now().toString());
        args.put("_lang", "zh-CN");
        if (userContent != null && !userContent.isBlank()
                && (!args.hasNonNull("query") || args.get("query").asText("").isBlank())) {
            args.put("_user_intent", userContent);
        }
        return args;
    }
}
