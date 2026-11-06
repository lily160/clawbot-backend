package com.github.wechat.cloudbot.platform.skill;

import com.github.wechat.cloudbot.platform.agent.ToolDefinition;

import java.nio.file.Path;

/**
 * 从 {@code SKILL.md} 加载的声明式 Skill。
 */
final class MarkdownSkill implements Skill {

    private final ToolDefinition definition;
    private final String instructions;
    private final Path source;

    MarkdownSkill(ToolDefinition definition, String instructions, Path source) {
        this.definition = definition;
        this.instructions = instructions;
        this.source = source;
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public String execute(String argumentsJson, String userId) {
        String arguments = argumentsJson == null || argumentsJson.isBlank()
                ? "{}"
                : argumentsJson;
        return """
                请严格按照下面的 Skill 说明继续完成用户任务。

                【Skill 参数】
                %s

                【Skill 说明】
                %s
                """.formatted(arguments, instructions).strip();
    }

    Path source() {
        return source;
    }
}
