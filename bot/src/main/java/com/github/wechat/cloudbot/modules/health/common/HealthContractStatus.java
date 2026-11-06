package com.github.wechat.cloudbot.modules.health.common;

import java.util.List;

/**
 * 组长视角的健康共享契约状态，便于 Agent 入口直接输出总览。
 */
public record HealthContractStatus(
        String owner,
        String roadmap,
        List<String> sharedContracts,
        List<String> moduleEntrypoints,
        List<String> guardrails) {

    public HealthContractStatus {
        sharedContracts = sharedContracts == null ? List.of() : List.copyOf(sharedContracts);
        moduleEntrypoints = moduleEntrypoints == null ? List.of() : List.copyOf(moduleEntrypoints);
        guardrails = guardrails == null ? List.of() : List.copyOf(guardrails);
    }
}
