package com.github.wechat.cloudbot.platform.config;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 通用字符串/文本工具方法。
 */
public final class Strings {

    private Strings() {}

    /**
     * 截断字符串并追加 {@code ...}。
     *
     * @param s   原始字符串
     * @param max 最大长度（含 {@code ...} 三个字符）
     * @return 截断后的字符串；{@code null} 返回 {@code ""}
     */
    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max)) + "...";
    }

    /**
     * 单行摘要：将换行替换为空格，再截断（默认 80 字符，用于日志）。
     */
    public static String summarize(String text) {
        return summarize(text, 80);
    }

    /**
     * 单行摘要：将换行替换为空格，再截断。
     *
     * @param text 原始文本
     * @param max  最大字符数
     * @return 单行摘要
     */
    public static String summarize(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }

    /**
     * 去除 URL 末尾的斜杠。
     *
     * @param baseUrl URL 字符串
     * @return 去掉末尾斜杠后的 URL；{@code null} 返回 {@code null}
     */
    public static String trimTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return baseUrl;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 压平换行后截断，适合日志单行预览。
     */
    public static String summarizeOneLine(String text) {
        return summarizeOneLine(text, 80);
    }

    /**
     * 压平换行后截断到指定长度。
     */
    public static String summarizeOneLine(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').trim();
        return truncate(oneLine, max);
    }

    /**
     * 去掉 URL 末尾 {@code /}；空则返回默认值。
     */
    public static String trimTrailingSlash(String baseUrl, String defaultIfBlank) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return defaultIfBlank;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 读取 JSON 字段文本并 trim；缺失/空返回 {@code ""}。
     */
    public static String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return "";
        }
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? "" : n.asText("").trim();
    }

    /**
     * 返回第一个非空白字符串。
     */
    public static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    /**
     * 提取 JSON 节点中第一个非空文本值。
     *
     * @param root JSON 节点
     * @param keys 按顺序尝试的键名
     * @return 第一个有值的文本，无匹配时返回 {@code ""}
     */
    public static String firstText(JsonNode root, String... keys) {
        if (root == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            JsonNode n = root.get(key);
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                return n.asText().trim();
            }
        }
        return "";
    }
}
