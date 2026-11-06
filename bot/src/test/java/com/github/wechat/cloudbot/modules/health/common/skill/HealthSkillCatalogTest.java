package com.github.wechat.cloudbot.modules.health.common.skill;

import com.github.wechat.cloudbot.modules.health.common.HealthContractOverview;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthSkillCatalogTest {

    @Test
    void overviewContainsMainFlowAndContracts() {
        String overview = HealthContractOverview.overviewText();
        assertTrue(overview.contains("组长"));
        assertTrue(overview.contains("建档 → 导诊 → 挂号 → 报告 → 提醒"));
        assertTrue(overview.contains("PatientRef"));
        assertTrue(overview.contains("HealthFlowOrchestrator"));
    }

    @Test
    void contractAndRoadmapViewsAreReadable() {
        assertTrue(HealthContractOverview.contractText().contains("BookingReceipt"));
        assertTrue(HealthContractOverview.roadmapText().contains("主流程"));
        assertTrue(HealthSkillCatalog.all().size() >= 7);
    }
}
