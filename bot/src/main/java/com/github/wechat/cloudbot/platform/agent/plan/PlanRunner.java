package com.github.wechat.cloudbot.platform.agent.plan;

import java.util.Optional;
import java.util.function.Function;

/**
 * 计划推进器：把“恢复 → 推进 WAIT_USER → 执行下一步 → 组装话术”收口到一处，
 * 让 HealthCommandRouter 只负责识别意图，不再编排细节。
 */
public final class PlanRunner {

    public record AdvanceResult(
            String waitingHint,
            PlanStepExecutor.StepOutcome nextOutcome,
            String snapshot,
            String draftSummary) {
    }

    private PlanRunner() {
    }

    public static Optional<AdvanceResult> advance(PlanOrchestrator orchestrator,
                                                  String userId,
                                                  Function<String, String> toolExecutor) {
        Optional<PlanDraft> resumed = orchestrator.resume(userId);
        if (resumed.isEmpty()) {
            return Optional.empty();
        }
        PlanDraft current = resumed.get();
        PlanDraft advanced = orchestrator.advancePastWaitUser(userId).orElseThrow();
        PlanStepExecutor.StepOutcome nextOutcome = orchestrator.executeNext(userId, toolExecutor).orElse(null);
        return Optional.of(new AdvanceResult(
                current.waitingHint().orElse("无"),
                nextOutcome,
                advanced.snapshotText().orElse(null),
                advanced.summary()));
    }

    public static String formatAdvance(AdvanceResult result) {
        StringBuilder sb = new StringBuilder("已推进编排到下一步：" + result.draftSummary());
        sb.append("\n等待点：").append(result.waitingHint);
        if (result.nextOutcome != null) {
            sb.append("\n下一步：").append(result.nextOutcome.action()).append(" / ").append(result.nextOutcome.name());
            if (!result.nextOutcome.message().isBlank()) {
                sb.append("\n结果：").append(result.nextOutcome.message());
            }
        } else {
            sb.append("\n下一步：已是最后一步");
        }
        if (result.snapshot != null) {
            sb.append("\n快照：").append(result.snapshot);
        }
        return sb.toString();
    }
}
