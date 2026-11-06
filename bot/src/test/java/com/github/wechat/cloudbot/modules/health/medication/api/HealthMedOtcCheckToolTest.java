package com.github.wechat.cloudbot.modules.health.medication.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.medication.infra.MedicationProperties;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HealthMedOtcCheckToolTest {

    @TempDir
    Path tempDir;

    private HealthMedOtcCheckTool tool;
    private MedicationService medicationService;

    @BeforeEach
    void setUp() {
        medicationService = newMedicationService();
        tool = new HealthMedOtcCheckTool(medicationService, new ObjectMapper());
    }

    @Test
    void pairWithKnownConflict() {
        ToolResult result = tool.execute(new ToolCall("c1", "health_med_otc_check",
                "{\"drug_a\":\"阿司匹林\",\"drug_b\":\"布洛芬\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("阿司匹林"));
        assertTrue(result.content().contains("布洛芬"));
        assertTrue(result.content().contains("不构成医嘱"));
    }

    @Test
    void pairReversedAlsoMatches() {
        ToolResult result = tool.execute(new ToolCall("c2", "health_med_otc_check",
                "{\"drug_a\":\"布洛芬\",\"drug_b\":\"阿司匹林\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("不构成医嘱"));
    }

    @Test
    void pairWithoutConflict() {
        ToolResult result = tool.execute(new ToolCall("c3", "health_med_otc_check",
                "{\"drug_a\":\"阿司匹林\",\"drug_b\":\"维生素C\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("暂未发现"));
    }

    @Test
    void singleAgainstExistingConflicts() {
        medicationService.add("u1", "布洛芬", "1片", "每日两次", 3, "");
        ToolResult result = tool.execute(new ToolCall("c4", "health_med_otc_check",
                "{\"drug_a\":\"阿司匹林\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("OTC"));
    }

    @Test
    void singleAgainstExistingNoConflict() {
        medicationService.add("u1", "维生素C", "1片", "每日一次", 30, "");
        ToolResult result = tool.execute(new ToolCall("c5", "health_med_otc_check",
                "{\"drug_a\":\"阿司匹林\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("暂未发现"));
    }

    @Test
    void missingBothDrugsFails() {
        ToolResult result = tool.execute(new ToolCall("c6", "health_med_otc_check",
                "{\"user_id\":\"u1\"}"));
        assertFalse(result.success());
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("health_med_otc_check", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
    }

    @Test
    void demoCephalosporinAndAlcoholDanger() {
        ToolResult result = tool.execute(new ToolCall("d1", "health_med_otc_check",
                "{\"drug_a\":\"头孢类抗生素\",\"drug_b\":\"酒精\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("双硫仑样反应"), "实际=" + result.content());
        assertTrue(result.content().contains("禁止饮酒"));
    }

    @Test
    void demoBrandedColdMedicinesDuplicateAcetaminophen() {
        ToolResult result = tool.execute(new ToolCall("d2", "health_med_otc_check",
                "{\"drug_a\":\"泰诺（对乙酰氨基酚）\",\"drug_b\":\"日夜百服宁\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("肝损伤"), "实际=" + result.content());
    }

    @Test
    void demoMultipleConflictsAgainstExisting() {
        medicationService.add("u1", "布洛芬", "1片", "每日两次", 3, "");
        medicationService.add("u1", "阿司匹林", "1片", "每日一次", 7, "");
        ToolResult result = tool.execute(new ToolCall("d3", "health_med_otc_check",
                "{\"drug_a\":\"银杏叶\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("出血"), "实际=" + result.content());
    }

    private CloudbotProperties noPersistenceProps() {
        CloudbotProperties cloudbot = new CloudbotProperties();
        cloudbot.getPersistence().setEnabled(false);
        return cloudbot;
    }

    private MedicationProperties medicationProps() {
        MedicationProperties props = new MedicationProperties();
        props.setEnabled(true);
        props.setMaxMedicationsPerUser(10);
        return props;
    }

    private MedicationService newMedicationService() {
        DriverManagerDataSource ds = h2DataSource("otccheck");
        ReminderService reminderService = reminderService(ds);
        MedicationService ms = new MedicationService(medicationProps(), reminderService, ds);
        ms.init();
        return ms;
    }

    private ReminderService reminderService(DriverManagerDataSource ds) {
        CloudbotProperties cp = new CloudbotProperties();
        cp.getPersistence().setEnabled(false);
        ReminderService reminderService = new ReminderService(
                ds,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                cp,
                new com.github.wechat.cloudbot.modules.task.infra.ReminderProperties(),
                event -> {});
        reminderService.ensureTable();
        return reminderService;
    }

    private static DriverManagerDataSource h2DataSource(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:" + name + "_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        return ds;
    }
}
