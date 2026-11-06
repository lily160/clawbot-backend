package com.github.wechat.cloudbot.platform.agent.plan;

import com.github.wechat.cloudbot.modules.health.common.HealthContractOverview;
import com.github.wechat.cloudbot.modules.health.common.HealthFlowOrchestrator;
import com.github.wechat.cloudbot.modules.health.common.HealthRedlinePrompts;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * 组长层的最小编排器：把计划、等待恢复、红线和健康总览先串起来。
 */
@Component
public class PlanOrchestrator {

    private final PlanRepository planRepository;
    private final HealthFlowOrchestrator healthFlowOrchestrator;

    public PlanOrchestrator(PlanRepository planRepository,
                            HealthFlowOrchestrator healthFlowOrchestrator) {
        this.planRepository = planRepository;
        this.healthFlowOrchestrator = healthFlowOrchestrator;
    }

    public PlanDraft buildGroupLeadDraft(String userId) {
        PlanDraft draft = new PlanDraft(
                "组长今天任务编排",
                List.of(
                        new PlanStep(PlanAction.CALL_TOOL, "health_overview", HealthContractOverview.overviewText(), List.of("health", "overview")),
                        new PlanStep(PlanAction.CALL_TOOL, "health_redline", HealthRedlinePrompts.REDLINE_PROMPT, List.of("health", "redline")),
                        new PlanStep(PlanAction.CALL_TOOL, "health_flow", healthFlowOrchestrator.summarize(), List.of("health", "flow")),
                        new PlanStep(PlanAction.WAIT_USER, "wait_user", "等待用户确认下一步或补充信息", List.of("wait_user")),
                        new PlanStep(PlanAction.END, "done", "组长编排完成", List.of("end"))
                ),
                true);
        planRepository.save(userId, draft);
        return draft;
    }

    public PlanDraft buildHealthFlowDraft(String userId, String snapshotText) {
        PlanDraft draft = new PlanDraft(
                "健康链路编排",
                List.of(
                        new PlanStep(PlanAction.CALL_TOOL, "triage", "导诊+挂号", List.of("health", "triage")),
                        new PlanStep(PlanAction.WAIT_USER, "wait_user", "等待用户补充症状或确认挂号", List.of("wait_user")),
                        new PlanStep(PlanAction.END, "done", "健康链路完成", List.of("end"))
                ),
                true,
                snapshotText);
        planRepository.save(userId, draft);
        return draft;
    }

    public Optional<PlanDraft> resume(String userId) {
        return planRepository.load(userId);
    }

    public Optional<PlanDraft> consume(String userId) {
        Optional<PlanDraft> draft = planRepository.load(userId);
        draft.ifPresent(d -> planRepository.clear(userId));
        return draft;
    }

    public Optional<PlanDraft> advancePastWaitUser(String userId) {
        Optional<PlanDraft> loaded = planRepository.load(userId);
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        PlanDraft current = loaded.get();
        Optional<PlanStep> waitStep = current.firstWaitUserStep();
        if (waitStep.isEmpty()) {
            return Optional.of(current);
        }
        Optional<PlanStep> next = current.nextStepAfter(waitStep.get().name());
        List<PlanStep> remaining = next.map(nextStep -> {
            int idx = current.steps().indexOf(nextStep);
            return current.steps().subList(idx, current.steps().size()).stream().toList();
        }).orElseGet(() -> List.of(new PlanStep(PlanAction.END, "done", "等待点已是最后一步", List.of("end"))));
        PlanDraft advanced = new PlanDraft(current.title(), remaining, current.resumable(), current.snapshot());
        planRepository.save(userId, advanced);
        return Optional.of(advanced);
    }

    public Optional<PlanStepExecutor.StepOutcome> executeNext(String userId, Function<String, String> toolExecutor) {
        Optional<PlanDraft> loaded = planRepository.load(userId);
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        PlanDraft current = loaded.get();
        Optional<PlanStep> step = current.steps().stream()
                .filter(candidate -> candidate.action() != PlanAction.WAIT_USER)
                .findFirst();
        if (step.isEmpty()) {
            return Optional.empty();
        }
        PlanStepExecutor.StepOutcome outcome = PlanStepExecutor.execute(step.get(), toolExecutor);
        List<PlanStep> remaining = current.nextStepAfter(step.get().name())
                .map(next -> current.steps().subList(current.steps().indexOf(next), current.steps().size()).stream().toList())
                .orElseGet(List::of);
        planRepository.save(userId, new PlanDraft(current.title(), remaining, current.resumable(), current.snapshot()));
        return Optional.of(outcome);
    }

    public void clear(String userId) {
        planRepository.clear(userId);
    }

    public String summary(String userId) {
        return resume(userId)
                .map(PlanDraft::summary)
                .orElse("暂无待恢复编排");
    }
}
