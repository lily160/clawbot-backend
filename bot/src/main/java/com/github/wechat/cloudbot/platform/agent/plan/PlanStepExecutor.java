package com.github.wechat.cloudbot.platform.agent.plan;

import java.util.Optional;
import java.util.function.Function;

/**
 * 通用步骤执行器：把 {@link PlanAction} 映射成可执行动作，
 * 让 HealthCommandRouter 之外的未来流程也能复用同一套推进语义。
 */
public final class PlanStepExecutor {

    /** 执行单个步骤后的结果。 */
    public record StepOutcome(
            PlanAction action,
            String name,
            String message,
            boolean terminal) {

        public static StepOutcome of(PlanStep step, String message, boolean terminal) {
            return new StepOutcome(step.action(), step.name(), message, terminal);
        }
    }

    /**
     * 执行给定步骤，返回对外可见的话术与是否终止。
     *
     * @param step         要执行的步骤
     * @param toolExecutor 名称到执行函数的映射；仅对 {@link PlanAction#CALL_TOOL} 生效
     */
    public static StepOutcome execute(PlanStep step, Function<String, String> toolExecutor) {
        if (step == null) {
            return new StepOutcome(PlanAction.END, "done", "无可执行步骤", true);
        }
        return switch (step.action()) {
            case CALL_TOOL -> {
                String result = toolExecutor == null ? "" : toolExecutor.apply(step.name());
                yield StepOutcome.of(step, result == null ? "" : result, false);
            }
            case WAIT_USER -> StepOutcome.of(
                    step,
                    step.prompt() == null || step.prompt().isBlank() ? step.name() : step.prompt(),
                    false);
            case REPLY -> StepOutcome.of(
                    step,
                    step.prompt() == null || step.prompt().isBlank() ? step.name() : step.prompt(),
                    false);
            case END -> StepOutcome.of(
                    step,
                    step.prompt() == null || step.prompt().isBlank() ? "流程结束" : step.prompt(),
                    true);
        };
    }

    /** 当前步骤之后是否还有可推进的下一步。 */
    public static Optional<PlanStep> nextOf(PlanDraft draft, PlanStep current) {
        if (draft == null || current == null) {
            return Optional.empty();
        }
        return draft.nextStepAfter(current.name());
    }

    private PlanStepExecutor() {
    }
}
