package com.github.wechat.cloudbot.modules.health.checkin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthReminderKind;
import com.github.wechat.cloudbot.modules.task.infra.ReminderProperties;
import com.github.wechat.cloudbot.modules.task.service.Reminder;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HealthReminderServiceTest {

    private HealthReminderService service;
    private ReminderService reminderService;

    @BeforeEach
    void setUp() {
        reminderService = new ReminderService(
                h2DataSource("healthreminderservicetest"),
                new ObjectMapper(),
                noPersistenceProps(),
                new ReminderProperties(),
                event -> {});
        reminderService.loadFromDisk();
        service = new HealthReminderService(reminderService);
    }

    @Test
    void drinkWaterUsesDefaultTime() {
        Reminder reminder = service.setReminder("u1", HealthReminderKind.DRINK_WATER, "", "");
        assertEquals("0 0 9 * * ?", reminder.cronExpression(), "缺省时间应为 09:00");
        assertTrue(reminder.content().contains("喝水"));
        assertTrue(reminder.content().contains("喝水打卡"), "提醒文案应带打卡引导");
        assertTrue(reminder.content().contains("[健康打卡:drink_water]"), "应含归属标记");
        assertTrue(reminder.content().contains("仅供参考"));
    }

    @Test
    void customTimeOverridesDefault() {
        Reminder reminder = service.setReminder("u1", HealthReminderKind.SLEEP_EARLY, "23:00", "");
        assertEquals("0 0 23 * * ?", reminder.cronExpression());
        assertTrue(reminder.content().contains("早睡"));
    }

    @Test
    void exerciseCron() {
        Reminder reminder = service.setReminder("u1", HealthReminderKind.EXERCISE, "07:30", "");
        assertEquals("0 30 7 * * ?", reminder.cronExpression());
        assertTrue(reminder.content().contains("活动活动"));
    }

    @Test
    void customRequiresNote() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setReminder("u1", HealthReminderKind.CUSTOM, "08:00", ""));
    }

    @Test
    void customWithNote() {
        Reminder reminder = service.setReminder("u1", HealthReminderKind.CUSTOM, "21:30", " 做眼保健操 ");
        assertTrue(reminder.content().contains("做眼保健操"));
        assertFalse(reminder.content().contains("  做眼保健操  "));
    }

    @Test
    void cancelReminderByKind() {
        service.setReminder("u1", HealthReminderKind.DRINK_WATER, "08:00", "");
        service.setReminder("u1", HealthReminderKind.EXERCISE, "19:00", "");
        assertTrue(service.cancelReminder("u1", HealthReminderKind.DRINK_WATER));
        List<Reminder> remaining = service.listReminders("u1");
        assertEquals(1, remaining.size());
        assertTrue(remaining.get(0).content().contains("[健康打卡:exercise]"));
    }

    @Test
    void cancelNonexistentReturnsFalse() {
        assertFalse(service.cancelReminder("u1", HealthReminderKind.SLEEP_EARLY));
    }

    @Test
    void listFiltersOnlyHealthReminders() {
        reminderService.add("u1", "普通提醒", LocalDateTime.now().plusHours(1), 0, null);
        service.setReminder("u1", HealthReminderKind.DRINK_WATER, "09:00", "");
        assertEquals(1, service.listReminders("u1").size());
    }

    @Test
    void blankUserIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setReminder("", HealthReminderKind.DRINK_WATER, "09:00", ""));
    }

    @Test
    void invalidTimeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setReminder("u1", HealthReminderKind.DRINK_WATER, "25:99", ""));
    }

    @Test
    void kindOfParsesMarker() {
        Reminder reminder = service.setReminder("u1", HealthReminderKind.EXERCISE, "19:00", "");
        assertEquals(HealthReminderKind.EXERCISE, HealthReminderService.kindOf(reminder));
        Reminder plain = reminderService.add("u1", "普通提醒", LocalDateTime.now().plusHours(1), 0, null);
        assertNull(HealthReminderService.kindOf(plain));
    }

    private static DriverManagerDataSource h2DataSource(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:" + name + "_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        return ds;
    }

    private static CloudbotProperties noPersistenceProps() {
        CloudbotProperties cloudbot = new CloudbotProperties();
        cloudbot.getPersistence().setEnabled(false);
        return cloudbot;
    }
}
