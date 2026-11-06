package com.github.wechat.cloudbot.platform.agent.plan;

import java.util.List;
import java.util.Optional;

/**
 * 编排草案，先把步骤和暂停点显式化。
 */
public record PlanDraft(
        String title,
        List<PlanStep> steps,
        boolean resumable,
        String snapshot) {

    public PlanDraft {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public PlanDraft(String title, List<PlanStep> steps, boolean resumable) {
        this(title, steps, resumable, null);
    }

    public Optional<String> snapshotText() {
        return Optional.ofNullable(snapshot).filter(value -> !value.isBlank());
    }

    public Optional<PlanStep> firstWaitUserStep() {
        return steps.stream().filter(step -> step.action() == PlanAction.WAIT_USER).findFirst();
    }

    public boolean waitingForUser() {
        return firstWaitUserStep().isPresent();
    }

    public Optional<String> waitingHint() {
        return firstWaitUserStep().map(step -> step.prompt() == null || step.prompt().isBlank() ? step.name() : step.prompt());
    }

    public Optional<PlanStep> nextStepAfter(String stepName) {
        if (stepName == null || stepName.isBlank()) {
            return Optional.empty();
        }
        for (int i = 0; i < steps.size() - 1; i++) {
            if (stepName.equals(steps.get(i).name())) {
                return Optional.of(steps.get(i + 1));
            }
        }
        return Optional.empty();
    }

    public Optional<PlanStep> nextRunnableStepAfterWait() {
        return firstWaitUserStep().flatMap(step -> nextStepAfter(step.name()));
    }

    public String summary() {
        return title + " / steps=" + steps.size() + " / resumable=" + resumable + snapshotText().map(text -> " / snapshot=" + text).orElse("");
    }
}
