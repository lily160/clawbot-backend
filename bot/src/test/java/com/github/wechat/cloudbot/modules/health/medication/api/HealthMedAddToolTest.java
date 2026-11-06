package com.github.wechat.cloudbot.modules.health.medication.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.common.PatientRef;
import com.github.wechat.cloudbot.modules.health.medication.infra.MedicationProperties;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService;
import com.github.wechat.cloudbot.modules.health.profile.service.ProfileService;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HealthMedAddToolTest {

    @TempDir
    Path tempDir;

    private HealthMedAddTool tool;
    private MedicationService medicationService;
    private ReminderService reminderService;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = h2DataSource("healthmedaddtooltest");
        reminderService = reminderService(ds);
        medicationService = new MedicationService(medicationProps(), reminderService, ds);
        medicationService.init();
        tool = new HealthMedAddTool(medicationService, null, new ObjectMapper());
    }

    @Test
    void addMedication() {
        ToolResult result = tool.execute(new ToolCall("c1", "health_med_add",
                "{\"drug_name\":\"阿司匹林肠溶片\",\"dosage\":\"1片\",\"frequency\":\"每日一次\",\"course_days\":7,\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("阿司匹林肠溶片"));
        assertTrue(result.content().contains("不构成医嘱"));
        // 提醒必须真实注册成功（防“测试绿但提醒没注册”的盲区）
        var reminders = reminderService.list("u1");
        assertTrue(reminders.stream().anyMatch(r -> r.content().contains("阿司匹林肠溶片")),
                "录药后应注册用药提醒，实际提醒数=" + reminders.size());
        assertTrue(reminders.stream().anyMatch(r -> r.content().contains("已吃药")),
                "提醒文案应引导回复「已吃药」完成服药打卡");
    }

    @Test
    void addDetectsOtcConflict() {
        // Add one drug first
        medicationService.add("u1", "布洛芬", "1片", "每日两次", 3, "");
        // Now add a conflicting drug
        ToolResult result = tool.execute(new ToolCall("c2", "health_med_add",
                "{\"drug_name\":\"阿司匹林\",\"dosage\":\"1片\",\"frequency\":\"每日一次\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("OTC"));
        // 布洛芬（每日两次）注册 2 条 + 阿司匹林（每日一次）注册 1 条 = 3 条
        var reminders = reminderService.list("u1");
        assertEquals(3, reminders.size(), "布洛芬 2 条 + 阿司匹林 1 条 = 3 条提醒");
        assertTrue(reminders.stream().anyMatch(r -> r.content().contains("布洛芬")));
        assertTrue(reminders.stream().anyMatch(r -> r.content().contains("阿司匹林")));
    }

    @Test
    void missingRequiredFieldFails() {
        ToolResult result = tool.execute(new ToolCall("c3", "health_med_add",
                "{\"drug_name\":\"阿司匹林\",\"user_id\":\"u1\"}"));
        assertFalse(result.success());
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("health_med_add", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
    }

    @Test
    void addDetectsAllergyFromProfile() {
        tool = new HealthMedAddTool(medicationService, profileWithAllergy("青霉素"), new ObjectMapper());
        ToolResult result = tool.execute(new ToolCall("c5", "health_med_add",
                "{\"drug_name\":\"阿莫西林\",\"dosage\":\"1片\",\"frequency\":\"每日一次\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("过敏"));
        assertTrue(result.content().contains("青霉素"));
        // 过敏提示优先于普通 OTC 提示
        assertTrue(result.content().indexOf("过敏风险提示") < result.content().indexOf("OTC 配伍风险提示") - 1
                || !result.content().contains("OTC 配伍风险提示"));
    }

    @Test
    void addWithoutProfileStillWorks() {
        // 未建档 / 无过敏史时正常录药，不报过敏
        ToolResult result = tool.execute(new ToolCall("c6", "health_med_add",
                "{\"drug_name\":\"维生素C\",\"dosage\":\"1片\",\"frequency\":\"每日一次\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertFalse(result.content().contains("过敏风险"));
    }

    @Test
    void addWithCustomReminderTimes() {
        ToolResult result = tool.execute(new ToolCall("c7", "health_med_add",
                "{\"drug_name\":\"硝苯地平\",\"dosage\":\"1片\",\"frequency\":\"每日两次\",\"reminder_times\":[\"07:30\",\"21:15\"],\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        var reminders = reminderService.list("u1");
        assertEquals(2, reminders.size(), "每日两次应注册两条提醒");
        assertTrue(reminders.stream().anyMatch(r -> r.triggerTime().getHour() == 7 && r.triggerTime().getMinute() == 30),
                "第一条提醒应使用自定义时间 07:30");
        assertTrue(reminders.stream().anyMatch(r -> r.triggerTime().getHour() == 21 && r.triggerTime().getMinute() == 15),
                "第二条提醒应使用自定义时间 21:15");
    }

    @Test
    void addWithInvalidReminderTimeFails() {
        ToolResult result = tool.execute(new ToolCall("c8", "health_med_add",
                "{\"drug_name\":\"硝苯地平\",\"dosage\":\"1片\",\"frequency\":\"每日一次\",\"reminder_times\":[\"8点\"],\"user_id\":\"u1\"}"));
        assertFalse(result.success());
        assertTrue(result.content().contains("HH:mm"));
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
                new com.fasterxml.jackson.databind.ObjectMapper(),
                cp,
                new com.github.wechat.cloudbot.modules.task.infra.ReminderProperties(),
                event -> {});
        // 直接构造不走 @PostConstruct，需手动建 scheduled_task 表，
        // 否则提醒注册会静默失败（bad SQL grammar）而测试仍通过。
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

    /** 构造带指定过敏史的档案桩（仅用于工具层测试）。 */
    private ProfileService profileWithAllergy(String... allergies) {
        return new ProfileService(null) {
            @Override
            public List<PatientRef> getProfiles(String wechatUserId) {
                return List.of(new PatientRef(
                        "1", "本人", "本人", "女", 30,
                        List.of(allergies), List.of(), null));
            }
        };
    }
}
