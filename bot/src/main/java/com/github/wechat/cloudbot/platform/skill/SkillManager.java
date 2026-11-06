package com.github.wechat.cloudbot.platform.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Skill 动态加载器。
 *
 * <p>启动时扫描 {@code src/skills/{skill}/SKILL.md}，通过统一协议创建 Skill，
 * 并注册到现有 {@link ToolRegistry}。新增 Skill 不需要修改本类，也不需要
 * 增加按名称判断的 if-else。
 */
@Component
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);

    private final ToolRegistry toolRegistry;
    private final SkillManifestParser parser;
    private final SkillProperties properties;
    private final Map<String, MarkdownSkill> loadedSkills = new ConcurrentHashMap<>();

    public SkillManager(
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            SkillProperties properties) {
        this.toolRegistry = toolRegistry;
        this.parser = new SkillManifestParser(objectMapper);
        this.properties = properties;
    }

    /**
     * 应用启动后扫描并注册全部 Skill。
     */
    @PostConstruct
    public void loadOnStartup() {
        if (!properties.isEnabled()) {
            log.info("Skill 动态加载已关闭");
            return;
        }
        ReloadResult result = reload();
        log.info("Skill 扫描完成：loaded={}, failed={}, directory={}",
                result.loaded(), result.failed(), skillDirectory().toAbsolutePath());
    }

    /**
     * 重新扫描目录，使新增、修改和删除的 Skill 生效。
     *
     * @return 本次扫描统计
     */
    public synchronized ReloadResult reload() {
        Path directory = skillDirectory();
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            log.error("无法创建 Skill 目录 {}：{}", directory, e.getMessage());
            return new ReloadResult(0, 1, Set.copyOf(loadedSkills.keySet()));
        }

        Map<String, MarkdownSkill> discovered = new LinkedHashMap<>();
        int failed = 0;
        for (Path manifest : listManifests(directory)) {
            try {
                MarkdownSkill skill = parser.parse(manifest);
                String name = skill.definition().name();
                if (discovered.putIfAbsent(name, skill) != null) {
                    throw new IllegalArgumentException("Skill 名称重复：" + name);
                }
            } catch (Exception e) {
                failed++;
                log.error("加载 Skill 失败 file={}：{}", manifest, e.getMessage());
            }
        }

        // 不允许声明式 Skill 静默覆盖内置工具。
        discovered.entrySet().removeIf(entry -> {
            String name = entry.getKey();
            boolean conflicts = toolRegistry.isRegistered(name) && !loadedSkills.containsKey(name);
            if (conflicts) {
                log.error("Skill 名称与已有工具冲突，已跳过：{}", name);
            }
            return conflicts;
        });

        Set<String> removed = new TreeSet<>(loadedSkills.keySet());
        removed.removeAll(discovered.keySet());
        removed.forEach(toolRegistry::unregister);

        for (Map.Entry<String, MarkdownSkill> entry : discovered.entrySet()) {
            toolRegistry.register(entry.getKey(), new SkillAgentToolAdapter(entry.getValue()));
        }

        loadedSkills.clear();
        loadedSkills.putAll(discovered);
        return new ReloadResult(discovered.size(), failed, Set.copyOf(discovered.keySet()));
    }

    /**
     * 返回当前已加载的 Skill 名称。
     */
    public Set<String> loadedNames() {
        return Set.copyOf(loadedSkills.keySet());
    }

    /**
     * 返回当前 Skill 根目录。
     */
    public Path skillDirectory() {
        return Path.of(properties.getDirectory()).normalize();
    }

    private static List<Path> listManifests(Path directory) {
        try (Stream<Path> children = Files.list(directory)) {
            return children
                    .filter(Files::isDirectory)
                    .map(path -> path.resolve("SKILL.md"))
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("扫描 Skill 目录失败：" + directory, e);
        }
    }

    /**
     * Skill 重载统计。
     */
    public record ReloadResult(int loaded, int failed, Set<String> names) {}
}
