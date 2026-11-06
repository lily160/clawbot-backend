package com.github.wechat.cloudbot.platform.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolRegistry;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void scansAllSkillFoldersAndRegistersThemAsAgentTools() throws IOException {
        writeSkill("daily", "write_daily_report", "生成日报", "# 步骤\n整理当天工作。");
        writeSkill("weekly", "write_weekly_report", "生成周报", "# 步骤\n整理本周工作。");

        ToolRegistry registry = new ToolRegistry(List.of(), List.of());
        SkillManager manager = manager(registry);

        SkillManager.ReloadResult result = manager.reload();

        assertEquals(2, result.loaded());
        assertEquals(0, result.failed());
        assertEquals(2, registry.size());
        assertTrue(manager.loadedNames().contains("write_daily_report"));
        assertTrue(manager.loadedNames().contains("write_weekly_report"));

        ToolResult execution = registry.execute(new ToolCall(
                "call-1",
                "write_daily_report",
                "{\"work\":\"完成 SkillManager\"}",
                "user-1"));
        assertTrue(execution.success());
        assertTrue(execution.content().contains("完成 SkillManager"));
        assertTrue(execution.content().contains("整理当天工作"));
    }

    @Test
    void reloadAddsChangedSkillsAndUnregistersDeletedSkills() throws IOException {
        Path oldSkill = writeSkill("old", "old_skill", "旧 Skill", "旧说明");
        ToolRegistry registry = new ToolRegistry(List.of(), List.of());
        SkillManager manager = manager(registry);
        manager.reload();

        deleteDirectory(oldSkill.getParent());
        writeSkill("new", "new_skill", "新 Skill", "新说明");
        SkillManager.ReloadResult result = manager.reload();

        assertEquals(1, result.loaded());
        assertFalse(registry.isRegistered("old_skill"));
        assertTrue(registry.isRegistered("new_skill"));
    }

    @Test
    void invalidManifestDoesNotPreventOtherSkillsFromLoading() throws IOException {
        writeSkill("valid", "valid_skill", "有效 Skill", "有效说明");
        Path invalidDir = Files.createDirectories(tempDir.resolve("invalid"));
        Files.writeString(invalidDir.resolve("SKILL.md"), """
                ---
                name: Invalid-Name
                description: 无效名称
                ---
                无效说明
                """);

        ToolRegistry registry = new ToolRegistry(List.of(), List.of());
        SkillManager.ReloadResult result = manager(registry).reload();

        assertEquals(1, result.loaded());
        assertEquals(1, result.failed());
        assertTrue(registry.isRegistered("valid_skill"));
    }

    @Test
    void skillCannotOverwriteBuiltInToolWithSameName() throws IOException {
        writeSkill("collision", "built_in", "冲突 Skill", "不应执行");
        ToolRegistry registry = new ToolRegistry(List.of(new StubTool()), List.of());

        SkillManager.ReloadResult result = manager(registry).reload();

        assertEquals(0, result.loaded());
        ToolResult execution = registry.execute(new ToolCall("1", "built_in", "{}"));
        assertEquals("built-in result", execution.content());
    }

    private SkillManager manager(ToolRegistry registry) {
        SkillProperties properties = new SkillProperties();
        properties.setDirectory(tempDir.toString());
        return new SkillManager(registry, new ObjectMapper(), properties);
    }

    private Path writeSkill(
            String directory,
            String name,
            String description,
            String instructions) throws IOException {
        Path skillDir = Files.createDirectories(tempDir.resolve(directory));
        Path manifest = skillDir.resolve("SKILL.md");
        Files.writeString(manifest, """
                ---
                name: %s
                description: %s
                parameters: {"type":"object","properties":{"work":{"type":"string"}}}
                ---
                %s
                """.formatted(name, description, instructions));
        return manifest;
    }

    private static void deleteDirectory(Path directory) throws IOException {
        Files.delete(directory.resolve("SKILL.md"));
        Files.delete(directory);
    }

    private static class StubTool implements AgentTool {
        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("built_in", "内置工具", "{}");
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.ok(call.id(), call.name(), "built-in result");
        }
    }
}
