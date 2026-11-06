package com.github.wechat.cloudbot.modules.task.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.task.service.Reminder;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import com.github.wechat.cloudbot.platform.channel.wechat.OutboundGateway;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import com.github.wechat.ilink.sdk.ILinkClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderTaskSchedulerTest {

    @TempDir
    Path tempDir;

    private ReminderService service;
    private ReminderProperties props;

    @BeforeEach
    void setUp() {
        props = reminderProps();
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:reminder_scheduler_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        service = new ReminderService(ds, new ObjectMapper(), cloudbotProps(), props, event -> {});
        service.loadFromDisk();
    }

    @Test
    void savedReminderCanBeScheduled() {
        ReminderTaskScheduler scheduler = new ReminderTaskScheduler(service, new OutboundGateway(), dummyClient());
        Reminder reminder = service.add("u1", "喝水", LocalDateTime.now().plusMinutes(1), 0, "once");
        scheduler.scheduleIfAbsent(reminder);
        assertTrue(scheduler.hasTask(reminder));
    }

    @Test
    void deletedReminderCancelsSchedule() {
        ReminderTaskScheduler scheduler = new ReminderTaskScheduler(service, new OutboundGateway(), dummyClient());
        Reminder reminder = service.add("u2", "取消", LocalDateTime.now().plusMinutes(1), 0, "once");
        scheduler.scheduleIfAbsent(reminder);
        scheduler.cancel(reminder);
        assertFalse(scheduler.hasTask(reminder));
    }

    @Test
    void triggeredRecurringReminderKeepsTaskAlive() {
        ReminderTaskScheduler scheduler = new ReminderTaskScheduler(service, new OutboundGateway(), dummyClient());
        Reminder reminder = service.add("u3", "晨练", LocalDateTime.now().plusMinutes(1), 0, "0 0 9 * * ?");
        scheduler.scheduleIfAbsent(reminder);
        scheduler.scheduleIfAbsent(reminder); // 重复登记幂等
        assertTrue(scheduler.hasTask(reminder));
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

    private ILinkClient dummyClient() {
        return Mockito.mock(ILinkClient.class);
    }
}
