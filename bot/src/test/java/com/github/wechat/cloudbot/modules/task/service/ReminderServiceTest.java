package com.github.wechat.cloudbot.modules.task.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.task.infra.ReminderProperties;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReminderServiceTest {

    @TempDir
    Path tempDir;

    private ReminderService service;

    @BeforeEach
    void setUp() {
        service = newService();
    }

    @Test
    void addListDelete() {
        LocalDateTime trigger = LocalDateTime.now().plusMinutes(1);
        Reminder added = service.add("u1", "喝水", trigger, 0, null);
        List<Reminder> list = service.list("u1");
        assertTrue(list.size() >= 1);
        assertEquals("喝水", list.get(0).content());

        ReminderService reloaded = newService();
        assertTrue(reloaded.list("u1").size() >= 1);
        assertEquals("喝水", reloaded.list("u1").get(0).content());

        assertTrue(service.delete("u1", added.id()));
        assertTrue(service.list("u1").isEmpty());
        assertFalse(service.delete("u1", added.id()));
    }

    @Test
    void triggeredDailyReminderAdvancesToNextDay() {
        LocalDateTime trigger = LocalDateTime.now().minusMinutes(1);
        Reminder added = service.add("u2", "复盘", trigger, 0, "daily");
        Reminder updated = service.handleTriggered(added);
        assertTrue(updated.enabled());
        assertEquals(added.triggerTime().plusDays(1).withNano(0), updated.triggerTime().withNano(0));
    }

    @Test
    void triggeredOnceReminderDisablesIt() {
        LocalDateTime trigger = LocalDateTime.now().minusMinutes(1);
        Reminder added = service.add("u3", "一次性", trigger, 0, "once");
        Reminder updated = service.handleTriggered(added);
        assertFalse(updated.enabled());
    }

    private CloudbotProperties cloudbotProps() {
        CloudbotProperties cloudbot = new CloudbotProperties();
        cloudbot.getPersistence().setEnabled(true);
        cloudbot.getPersistence().setDir(tempDir.toString());
        return cloudbot;
    }

    private ReminderProperties reminderProps() {
        ReminderProperties props = new ReminderProperties();
        props.setEnabled(true);
        props.setMaxRemindersPerUser(10);
        return props;
    }

    private static final String JDBC_URL = "jdbc:h2:mem:reminder_shared;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;IFEXISTS=FALSE";

    private ReminderService newService() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl(JDBC_URL);
        ReminderService reminderService = new ReminderService(
                dataSource, new ObjectMapper(), cloudbotProps(), reminderProps(), event -> {});
        reminderService.loadFromDisk();
        return reminderService;
    }
}
