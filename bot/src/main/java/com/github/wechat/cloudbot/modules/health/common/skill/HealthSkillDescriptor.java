package com.github.wechat.cloudbot.modules.health.common.skill;

import java.util.List;

/**
 * 健康管家 skill 的可沉淀元信息：后续可以直接映射到独立 skill 文档/配置。
 */
public record HealthSkillDescriptor(
        String id,
        String title,
        HealthSkillScope scope,
        String purpose,
        List<String> capabilities,
        List<String> dependencies,
        List<String> guardrails,
        List<String> entryPoints) {

    public HealthSkillDescriptor {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        guardrails = guardrails == null ? List.of() : List.copyOf(guardrails);
        entryPoints = entryPoints == null ? List.of() : List.copyOf(entryPoints);
    }
}
