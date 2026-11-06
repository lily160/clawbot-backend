package com.github.wechat.cloudbot.modules.file.service;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 文本类文件：扩展名白名单、安全文件名、UTF-8/GBK 解码、内容截断。
 */
public final class TextFileSupport {

    private static final Set<String> TEXT_EXTENSIONS =
            Set.of(
                    "txt",
                    "md",
                    "json",
                    "csv",
                    "log",
                    "xml",
                    "yml",
                    "yaml",
                    "html",
                    "htm",
                    "css",
                    "js",
                    "ts",
                    "java",
                    "py",
                    "properties",
                    "sql",
                    "conf",
                    "ini");

    private static final Charset GBK = Charset.forName("GBK");

    private TextFileSupport() {}

    public static boolean isTextExtension(String fileName) {
        String ext = extensionOf(fileName);
        return ext != null && TEXT_EXTENSIONS.contains(ext);
    }

    public static String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String name = fileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String sanitizeFileName(String fileName) {
        String raw = fileName == null || fileName.isBlank() ? "file.txt" : fileName.trim();
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        if (slash >= 0) {
            raw = raw.substring(slash + 1);
        }
        raw = raw.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]", "_");
        if (raw.isBlank() || ".".equals(raw) || "..".equals(raw)) {
            raw = "file.txt";
        }
        if (!isTextExtension(raw)) {
            int dot = raw.lastIndexOf('.');
            String base = dot > 0 ? raw.substring(0, dot) : raw;
            if (base.isBlank()) {
                base = "file";
            }
            raw = base + ".txt";
        }
        if (raw.length() > 80) {
            String ext = extensionOf(raw);
            String base = raw.substring(0, Math.min(60, raw.length()));
            raw = ext == null ? base : base + "." + ext;
        }
        return raw;
    }

    public static String decodeText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!hasReplacementChar(utf8) || looksMostlyAscii(bytes)) {
            return utf8;
        }
        String gbk = new String(bytes, GBK);
        if (!hasReplacementChar(gbk)) {
            return gbk;
        }
        if (countReplacement(utf8) <= countReplacement(gbk)) {
            return utf8;
        }
        return gbk;
    }

    public static String truncateForPrompt(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        int max = Math.max(1024, maxChars);
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n…(内容已截断，原文更长)";
    }

    public static String formatSizeKb(int bytes) {
        if (bytes <= 0) {
            return "0 KB";
        }
        double kb = bytes / 1024.0;
        if (kb < 10) {
            return String.format(Locale.ROOT, "%.1f KB", kb);
        }
        return String.format(Locale.ROOT, "%.0f KB", kb);
    }

    private static boolean hasReplacementChar(String s) {
        return s.indexOf('\uFFFD') >= 0;
    }

    private static int countReplacement(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\uFFFD') {
                n++;
            }
        }
        return n;
    }

    private static boolean looksMostlyAscii(byte[] bytes) {
        int sample = Math.min(bytes.length, 256);
        int nonAscii = 0;
        for (int i = 0; i < sample; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0) {
                return false;
            }
            if (b >= 0x80) {
                nonAscii++;
            }
        }
        return nonAscii * 4 < sample;
    }
}
