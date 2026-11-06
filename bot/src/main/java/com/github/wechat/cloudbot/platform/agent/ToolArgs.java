package com.github.wechat.cloudbot.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.config.Strings;

/**
 * Agent Tool 参数解析公共方法，消除各 Tool 内重复的 readTree / text / intValue。
 */
public final class ToolArgs {

    private ToolArgs() {}

    public static JsonNode parse(ObjectMapper objectMapper, ToolCall call) throws Exception {
        String raw = call == null || call.argumentsJson() == null ? "{}" : call.argumentsJson();
        return objectMapper.readTree(raw);
    }

    public static String text(JsonNode args, String field) {
        return Strings.text(args, field);
    }

    /** 按顺序取第一个非空字段。 */
    public static String textAny(JsonNode args, String... fields) {
        if (fields == null) {
            return "";
        }
        for (String field : fields) {
            String v = text(args, field);
            if (!v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    public static int intValue(JsonNode args, String field, int defaultValue) {
        if (args == null || field == null) {
            return defaultValue;
        }
        JsonNode n = args.path(field);
        if (n.isMissingNode() || n.isNull()) {
            return defaultValue;
        }
        if (n.isIntegralNumber()) {
            return n.asInt(defaultValue);
        }
        try {
            return Integer.parseInt(n.asText("").trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 解析用户 ID：优先 arguments 中的 {@code user_id}/{@code userId}，再回退 {@link ToolCall#userId()}。
     */
    public static String resolveUserId(ToolCall call, JsonNode args) {
        return Strings.firstNonBlank(
                text(args, "user_id"),
                text(args, "userId"),
                call == null ? null : call.userId());
    }
}
