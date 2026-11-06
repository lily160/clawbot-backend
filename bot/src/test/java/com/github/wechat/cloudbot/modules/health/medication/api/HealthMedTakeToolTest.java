package com.github.wechat.cloudbot.modules.health.medication.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.checkin.service.CheckinService;
import com.github.wechat.cloudbot.modules.health.medication.infra.MedicationProperties;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class HealthMedTakeToolTest {

    private HealthMedTakeTool tool;
    private MedicationService medicationService;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = h2DataSource("medtaketooltest");
        medicationService = new MedicationService(medicationProps(), reminderService(ds), ds);
        medicationService.init();
        CheckinService checkinService = new CheckinService(ds);
        checkinService.ensureTable();
        tool = new HealthMedTakeTool(medicationService, checkinService, new ObjectMapper());
    }

    @Test
    void recordsTakeForNamedDrug() {
        medicationService.add("u1", "阿司匹林", "1片", "每日一次", 7, "");
        ToolResult result = tool.execute(new ToolCall("t1", "health_med_take",
                "{\"drug_name\":\"阿司匹林\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("已记录服药打卡"));
        assertTrue(result.content().contains("阿司匹林"));
        assertTrue(result.content().contains("1/1"));
    }

    @Test
    void recordsAllActiveWhenNoDrug() {
        medicationService.add("u1", "阿司匹林", "1片", "每日一次", 7, "");
        medicationService.add("u1", "维生素C", "1片", "每日一次", 7, "");
        ToolResult result = tool.execute(new ToolCall("t2", "health_med_take",
                "{\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("阿司匹林"));
        assertTrue(result.content().contains("维生素C"));
        assertTrue(result.content().contains("2/2"));
    }

    @Test
    void noActiveMedShowsGuidance() {
        ToolResult result = tool.execute(new ToolCall("t3", "health_med_take",
                "{\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("没有进行中的用药记录"));
    }

    @Test
    void repeatedTakeAccumulatesAndShowsStreak() {
        medicationService.add("u1", "阿司匹林", "1片", "每日一次", 7, "");
        tool.execute(new ToolCall("t4", "health_med_take",
                "{\"drug_name\":\"阿司匹林\",\"user_id\":\"u1\"}"));
        ToolResult second = tool.execute(new ToolCall("t5", "health_med_take",
                "{\"drug_name\":\"阿司匹林\",\"user_id\":\"u1\"}"));
        assertTrue(second.success());
        assertTrue(second.content().contains("今日第 2 次"));
        assertTrue(second.content().contains("连续服药打卡 1 天"));
    }

    @Test
    void backfillDateWorks() {
        medicationService.add("u1", "阿司匹林", "1片", "每日一次", 7, "");
        ToolResult result = tool.execute(new ToolCall("t6", "health_med_take",
                "{\"drug_name\":\"阿司匹林\",\"date\":\"2026-08-04\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("补卡"));
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

    private ReminderService reminderService(DriverManagerDataSource ds) {
        CloudbotProperties cp = new CloudbotProperties();
        cp.getPersistence().setEnabled(false);
        ReminderService reminderService = new ReminderService(
                ds,
                new ObjectMapper(),
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
