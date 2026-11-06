package com.github.wechat.cloudbot.platform.agent.plan;

import java.util.List;

/**
 * 单条编排步骤。
 */
public record PlanStep(
        PlanAction action,
        String name,
        String prompt,
        List<String> tags) {

    public PlanStep {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
