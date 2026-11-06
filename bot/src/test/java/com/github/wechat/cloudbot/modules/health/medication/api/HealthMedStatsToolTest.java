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

class HealthMedStatsToolTest {

    @TempDir
    Path tempDir;

    private HealthMedStatsTool tool;
    private MedicationService medicationService;

    @BeforeEach
    void setUp() {
        medicationService = newMedicationService();
        tool = new HealthMedStatsTool(medicationService, new ObjectMapper());
    }

    @Test
    void statsWithRecords() {
        medicationService.add("u1", "阿司匹林", "1片", "每日一次", 30, "");
        medicationService.add("u1", "维生素C", "1片", "每日一次", 0, "");
        medicationService.add("u1", "硝苯地平", "1片", "每日两次", 7, "");

        ToolResult result = tool.execute(new ToolCall("c1", "health_med_stats", "{\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("3"));
        assertTrue(result.content().contains("每日一次"));
        assertTrue(result.content().contains("每日两次"));
    }

    @Test
    void statsEmpty() {
        ToolResult result = tool.execute(new ToolCall("c2", "health_med_stats", "{\"user_id\":\"empty\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("0"));
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("health_med_stats", def.name());
        assertNotNull(def.parametersJsonSchema());
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
        DriverManagerDataSource ds = h2DataSource("healthmedstatstooltest");
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
