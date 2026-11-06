package com.github.wechat.cloudbot.platform.agent.plan;

import com.github.wechat.cloudbot.modules.health.common.HealthFlowOrchestrator;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class PlanOrchestratorTest {

    @Test
    void advancePastWaitUser_keepsRemainingStepsAndSnapshot() {
        PlanRepository repo = new InMemoryPlanRepository();
        HealthFlowOrchestrator flow = Mockito.mock(HealthFlowOrchestrator.class);
        when(flow.summarize()).thenReturn("summary");
        PlanOrchestrator orchestrator = new PlanOrchestrator(repo, flow);
        orchestrator.buildHealthFlowDraft("u1", "健康链路快照\n- 用户：u1\n- 症状：咳嗽\n- 等待用户：true");

        PlanDraft advanced = orchestrator.advancePastWaitUser("u1").orElseThrow();

        assertTrue(advanced.waitingForUser() == false || advanced.waitingHint().isEmpty() || advanced.steps().size() <= 2);
        assertTrue(advanced.snapshotText().orElse("").contains("咳嗽"));
    }
}
