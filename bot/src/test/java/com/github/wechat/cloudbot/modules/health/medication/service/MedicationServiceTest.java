package com.github.wechat.cloudbot.modules.health.medication.service;

import com.github.wechat.cloudbot.modules.health.medication.infra.MedicationProperties;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MedicationServiceTest {

    private MedicationService service;
    private ReminderService reminderService;
    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = h2DataSource("medicationservicetest");
        reminderService = reminderService(dataSource);
        service = new MedicationService(medicationProps(), reminderService, dataSource);
        service.init();
    }

    @Test
    void addAndList() {
        service.add("user1", "阿司匹林肠溶片", "1片", "每日一次", 7, "饭后服用");
        List<MedicationRecord> list = service.list("user1");
        assertEquals(1, list.size());
        assertEquals("阿司匹林肠溶片", list.get(0).drugName());
        assertEquals("1片", list.get(0).dosage());
    }

    @Test
    void listReturnsEmptyForUnknownUser() {
        assertTrue(service.list("nonexistent").isEmpty());
    }

    @Test
    void deleteDisablesRecord() {
        service.add("user1", "维生素C", "1片", "每日一次", 30, "");
        List<MedicationRecord> before = service.list("user1");
        assertEquals(1, before.size());
        assertTrue(service.delete("user1", before.get(0).id()));
        assertTrue(service.list("user1").isEmpty());
    }

    @Test
    void otcInteractionDetected() {
        Optional<MedicationService.OtcInteraction> result = service.checkInteraction("阿司匹林", "布洛芬");
        assertTrue(result.isPresent());
        assertEquals("warn", result.get().level());
    }

    @Test
    void otcInteractionNoFalsePositive() {
        Optional<MedicationService.OtcInteraction> result = service.checkInteraction("阿司匹林", "维生素C");
        assertFalse(result.isPresent());
    }

    @Test
    void otcAliasMatch() {
        // 品牌名/别名命中：芬必得=布洛芬、拜新同=硝苯地平
        assertTrue(service.checkInteraction("芬必得", "阿司匹林").isPresent(),
                "芬必得应命中布洛芬×阿司匹林");
        assertTrue(service.checkInteraction("拜新同", "布洛芬").isPresent(),
                "拜新同应命中硝苯地平×布洛芬");
        assertTrue(service.checkInteraction("泰诺", "布洛芬").isPresent(),
                "泰诺应命中对乙酰氨基酚×布洛芬");
    }

    @Test
    void otcDosageSuffixMatch() {
        assertTrue(service.checkInteraction("布洛芬缓释胶囊", "阿司匹林").isPresent(),
                "带剂型后缀药名应命中");
        assertTrue(service.checkInteraction("华法林钠片", "阿司匹林").isPresent());
    }

    @Test
    void otcConflictsSortedBySeverity() {
        service.add("u1", "华法林", "2.5mg", "每日一次", 30, "");
        service.add("u1", "布洛芬", "1片", "每日两次", 3, "");
        var conflicts = service.checkInteractionsWithExisting("u1", "阿司匹林");
        assertTrue(conflicts.size() >= 2);
        assertEquals("danger", conflicts.get(0).level(), "danger 级应排最前");
        assertEquals("warn", conflicts.get(1).level());
    }

    @Test
    void otcInteractionReversible() {
        Optional<MedicationService.OtcInteraction> result = service.checkInteraction("布洛芬", "阿司匹林");
        assertTrue(result.isPresent());
    }

    @Test
    void checkInteractionsWithExisting() {
        service.add("user1", "布洛芬", "1片", "每日两次", 3, "发烧时服用");
        List<MedicationService.OtcInteraction> conflicts = service.checkInteractionsWithExisting("user1", "阿司匹林");
        assertFalse(conflicts.isEmpty());
        assertTrue(conflicts.stream().anyMatch(c -> c.a().contains("阿司匹林") || c.b().contains("阿司匹林")));
    }

    @Test
    void allergyDetectedForMatchingDrug() {
        var warnings = service.checkAllergies(List.of("青霉素"), "阿莫西林");
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.allergen().contains("青霉素")));
    }

    @Test
    void allergyDetectedWithDosageSuffix() {
        assertFalse(service.checkAllergies(List.of("青霉素"), "阿莫西林胶囊").isEmpty(),
                "带剂型后缀的药名也应命中过敏");
    }

    @Test
    void allergyMatchesProfileSynonym() {
        // 档案里写「青霉素过敏」也应命中
        assertFalse(service.checkAllergies(List.of("青霉素过敏"), "阿莫西林").isEmpty());
    }

    @Test
    void allergyNoFalsePositive() {
        assertTrue(service.checkAllergies(List.of("青霉素"), "维生素C").isEmpty());
        assertTrue(service.checkAllergies(List.of("海鲜"), "阿莫西林").isEmpty());
    }

    @Test
    void allergyCrossReactionIsWarnLevel() {
        var warnings = service.checkAllergies(List.of("青霉素"), "头孢类抗生素");
        assertFalse(warnings.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> "warn".equals(w.level())),
                "青霉素→头孢交叉过敏应提示医生评估（warn 级）");
    }

    @Test
    void parseFrequencyReturnsMultipleSchedules() {
        var two = MedicationService.parseFrequency("每日两次");
        assertEquals(2, two.size());
        assertEquals("早", two.get(0).label());
        assertEquals("晚", two.get(1).label());

        var three = MedicationService.parseFrequency("每日三次");
        assertEquals(3, three.size());
        assertEquals("早", three.get(0).label());
        assertEquals("中", three.get(1).label());
        assertEquals("晚", three.get(2).label());

        var once = MedicationService.parseFrequency("每日一次");
        assertEquals(1, once.size());

        var nightly = MedicationService.parseFrequency("每晚一次");
        assertEquals("0 0 21 * * ?", nightly.get(0).cron());

        assertTrue(MedicationService.parseFrequency("").isEmpty());
    }

    @Test
    void addWithBlankUserIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.add("", "药", "1片", "每日一次", 7, ""));
    }

    @Test
    void maxLimitReachedThrows() {
        MedicationProperties limited = new MedicationProperties();
        limited.setMaxMedicationsPerUser(1);
        MedicationService limitedService = new MedicationService(limited, reminderService(dataSource), dataSource);
        limitedService.init();
        limitedService.add("u1", "药A", "1片", "每日一次", 7, "");
        assertThrows(IllegalStateException.class,
                () -> limitedService.add("u1", "药B", "1片", "每日一次", 7, ""));
    }

    @Test
    void listDoesNotIncludeDisabled() {
        service.add("u1", "维生素C", "1片", "每日一次", 7, "");
        var records = service.list("u1");
        service.delete("u1", records.get(0).id());
        assertTrue(service.list("u1").isEmpty());
    }

    @Test
    void persistenceAcrossReload() throws Exception {
        MedicationService persistingService = new MedicationService(medicationProps(), reminderService(dataSource), dataSource);
        persistingService.init();
        persistingService.add("user2", "硝苯地平", "1片", "每日一次", 30, "");

        // 同一数据源上重建服务实例，模拟重启后数据仍在（DB_CLOSE_DELAY=-1）
        MedicationService reloaded = new MedicationService(medicationProps(), reminderService(dataSource), dataSource);
        reloaded.init();
        List<MedicationRecord> list = reloaded.list("user2");
        assertEquals(1, list.size());
        assertEquals("硝苯地平", list.get(0).drugName());
    }

    private MedicationProperties medicationProps() {
        MedicationProperties props = new MedicationProperties();
        props.setEnabled(true);
        props.setMaxMedicationsPerUser(10);
        return props;
    }

    @Test
    void customReminderTimesUsed() {
        service.add("u1", "硝苯地平", "1片", "每日两次", 30, "", List.of("07:30", "21:15"));
        var reminders = reminderService.list("u1");
        assertEquals(2, reminders.size());
        assertTrue(reminders.stream().anyMatch(r -> r.triggerTime().getHour() == 7 && r.triggerTime().getMinute() == 30));
        assertTrue(reminders.stream().anyMatch(r -> r.triggerTime().getHour() == 21 && r.triggerTime().getMinute() == 15));
    }

    @Test
    void invalidReminderTimeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.add("u1", "药", "1片", "每日一次", 7, "", List.of("8点")));
    }

    @Test
    void expiredCourseAutoDisabledAndReminderCleaned() {
        MedicationRecord record = service.add("u1", "阿莫西林", "1片", "每日一次", 3, "");
        // 回拨创建时间 4 天前 → 疗程(3天)已到期
        new JdbcTemplate(dataSource).update(
                "UPDATE health_medication SET created_at = DATEADD('DAY', -4, NOW()) WHERE id = ?", record.id());
        assertTrue(service.list("u1").isEmpty(), "到期记录应自动停用");
        assertTrue(reminderService.list("u1").isEmpty(), "到期后提醒应被清理");
    }

    @Test
    void statsListsTodayExpiring() {
        MedicationRecord record = service.add("u1", "维生素C", "1片", "每日一次", 3, "");
        // 创建于 3 天前 → 今日到期
        new JdbcTemplate(dataSource).update(
                "UPDATE health_medication SET created_at = DATEADD('DAY', -3, NOW()) WHERE id = ?", record.id());
        var stats = service.getStats("u1");
        assertFalse(stats.todayExpiring().isEmpty());
        assertTrue(stats.todayExpiring().get(0).contains("维生素C"));
    }

    private ReminderService reminderService(DataSource ds) {
        CloudbotProperties cp = new CloudbotProperties();
        cp.getPersistence().setEnabled(false);
        ReminderService reminderService = new ReminderService(
                ds,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                cp,
                new com.github.wechat.cloudbot.modules.task.infra.ReminderProperties(),
                event -> {});
        // 直接构造不走 @PostConstruct，需手动建 scheduled_task 表，
        // 供 delete/update 清理关联提醒的查询使用。
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
