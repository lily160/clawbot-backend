package com.github.wechat.cloudbot.platform.agent.plan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanDraftTest {

    @Test
    void waitingHint_returnsWaitSummaryWhenPresent() {
        PlanDraft draft = new PlanDraft("title", List.of(
                new PlanStep(PlanAction.CALL_TOOL, "triage", "导诊+挂号", List.of("health")),
                new PlanStep(PlanAction.WAIT_USER, "wait_user", "等待用户补充症状或确认挂号", List.of("wait_user"))
        ), true, "snapshot");

        assertTrue(draft.waitingForUser());
        assertEquals("等待用户补充症状或确认挂号", draft.waitingHint().orElseThrow());
        assertEquals("snapshot", draft.snapshotText().orElseThrow());
    }

    @Test
    void waitingHint_emptyWhenNoWaitStep() {
        PlanDraft draft = new PlanDraft("title", List.of(
                new PlanStep(PlanAction.CALL_TOOL, "triage", "导诊+挂号", List.of("health"))
        ), true);

        assertFalse(draft.waitingForUser());
        assertTrue(draft.waitingHint().isEmpty());
    }
}
