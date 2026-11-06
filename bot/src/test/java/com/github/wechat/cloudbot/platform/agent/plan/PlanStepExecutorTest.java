package com.github.wechat.cloudbot.platform.agent.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanStepExecutorTest {

    @Test
    void execute_callToolDelegatesToExecutor() {
        PlanStep step = new PlanStep(PlanAction.CALL_TOOL, "triage", "导诊", List.of("health"));

        PlanStepExecutor.StepOutcome outcome = PlanStepExecutor.execute(step, name -> "executed:" + name);

        assertEquals(PlanAction.CALL_TOOL, outcome.action());
        assertEquals("triage", outcome.name());
        assertEquals("executed:triage", outcome.message());
        assertFalse(outcome.terminal());
    }

    @Test
    void execute_waitUserReturnsPrompt() {
        PlanStep step = new PlanStep(PlanAction.WAIT_USER, "wait_user", "等待用户补充症状", List.of("wait_user"));

        PlanStepExecutor.StepOutcome outcome = PlanStepExecutor.execute(step, null);

        assertEquals("等待用户补充症状", outcome.message());
        assertFalse(outcome.terminal());
    }

    @Test
    void execute_endIsTerminal() {
        PlanStep step = new PlanStep(PlanAction.END, "done", "完成", List.of("end"));

        PlanStepExecutor.StepOutcome outcome = PlanStepExecutor.execute(step, null);

        assertTrue(outcome.terminal());
        assertEquals("完成", outcome.message());
    }
}
