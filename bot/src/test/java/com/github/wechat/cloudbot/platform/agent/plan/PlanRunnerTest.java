package com.github.wechat.cloudbot.platform.agent.plan;

import com.github.wechat.cloudbot.modules.health.common.HealthFlowOrchestrator;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class PlanRunnerTest {

    @Test
    void advance_returnsEmptyWhenNoPlan() {
        PlanRepository repo = new InMemoryPlanRepository();
        HealthFlowOrchestrator flow = Mockito.mock(HealthFlowOrchestrator.class);
        when(flow.summarize()).thenReturn("summary");
        PlanOrchestrator orchestrator = new PlanOrchestrator(repo, flow);

        assertTrue(PlanRunner.advance(orchestrator, "nobody", Function.identity()).isEmpty());
    }

    @Test
    void advance_formatsWhenPlanExists() {
        PlanRepository repo = new InMemoryPlanRepository();
        HealthFlowOrchestrator flow = Mockito.mock(HealthFlowOrchestrator.class);
        when(flow.summarize()).thenReturn("summary");
        PlanOrchestrator orchestrator = new PlanOrchestrator(repo, flow);
        orchestrator.buildHealthFlowDraft("u1", "健康链路快照\n- 症状：咳嗽\n- 等待用户：true");

        PlanRunner.AdvanceResult result = PlanRunner.advance(orchestrator, "u1", name -> "executed:" + name).orElseThrow();

        assertNotNull(result.nextOutcome());
        String formatted = PlanRunner.formatAdvance(result);
        assertTrue(formatted.contains("已推进编排到下一步"));
        assertTrue(formatted.contains("等待点：等待用户补充症状或确认挂号"));
        assertTrue(formatted.contains("快照：健康链路快照"));
    }
}
