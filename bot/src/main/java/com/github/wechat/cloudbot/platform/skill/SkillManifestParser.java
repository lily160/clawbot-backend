package com.github.wechat.cloudbot.platform.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 解析并校验 {@code SKILL.md} 协议文件。
 */
final class SkillManifestParser {

    private static final Pattern VALID_NAME = Pattern.compile("[a-z][a-z0-9_]{1,63}");
    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private final ObjectMapper objectMapper;

    SkillManifestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    MarkdownSkill parse(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (!content.startsWith("---")) {
            throw new IllegalArgumentException("缺少以 --- 开始的元数据");
        }

        int headerEnd = content.indexOf("\n---", 3);
        if (headerEnd < 0) {
            throw new IllegalArgumentException("缺少以 --- 结束的元数据");
        }

        String header = content.substring(3, headerEnd);
        String instructions = content.substring(headerEnd + 4).strip();
        Map<String, String> metadata = parseMetadata(header);

        String name = required(metadata, "name");
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "name 必须匹配 [a-z][a-z0-9_]{1,63}：" + name);
        }
        String description = required(metadata, "description");
        String schema = metadata.getOrDefault("parameters", EMPTY_SCHEMA);
        validateSchema(schema);
        if (instructions.isBlank()) {
            throw new IllegalArgumentException("Skill 正文不能为空");
        }

        return new MarkdownSkill(
                new ToolDefinition(name, description, schema),
                instructions,
                file.toAbsolutePath().normalize());
    }

    private static Map<String, String> parseMetadata(String header) {
        Map<String, String> metadata = new HashMap<>();
        for (String rawLine : header.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IllegalArgumentException("无效元数据行：" + rawLine);
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (metadata.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("元数据字段重复：" + key);
            }
        }
        return metadata;
    }

    private static String required(Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少元数据字段：" + key);
        }
        return value;
    }

    private void validateSchema(String schema) throws IOException {
        JsonNode root = objectMapper.readTree(schema);
        if (!root.isObject() || !"object".equals(root.path("type").asText())) {
            throw new IllegalArgumentException("parameters 必须是 type=object 的 JSON Schema");
        }
    }
}
