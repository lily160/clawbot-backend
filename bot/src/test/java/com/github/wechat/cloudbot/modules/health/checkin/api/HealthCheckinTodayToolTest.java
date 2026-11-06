package com.github.wechat.cloudbot.modules.health.checkin.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthReminderKind;
import com.github.wechat.cloudbot.modules.health.checkin.service.CheckinService;
import com.github.wechat.cloudbot.modules.health.checkin.service.HealthReminderService;
import com.github.wechat.cloudbot.modules.task.infra.ReminderProperties;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckinTodayToolTest {

    private HealthCheckinTodayTool tool;
    private CheckinService checkinService;
    private HealthReminderService healthReminderService;

    @BeforeEach
    void setUp() {
        DataSource ds = h2DataSource("checkintodaytooltest");
        checkinService = new CheckinService(ds);
        checkinService.ensureTable();

        ReminderService reminderService = new ReminderService(
                ds, new ObjectMapper(), noPersistenceProps(), new ReminderProperties(), event -> {});
        reminderService.loadFromDisk();
        healthReminderService = new HealthReminderService(reminderService);

        tool = new HealthCheckinTodayTool(checkinService, healthReminderService, new ObjectMapper());
    }

    @Test
    void showsCheckinsAndReminderLinkage() {
        checkinService.checkin("u1", HealthReminderKind.DRINK_WATER, LocalDate.now(), 2, "");
        healthReminderService.setReminder("u1", HealthReminderKind.SLEEP_EARLY, "22:30", "");

        ToolResult result = tool.execute(new ToolCall("c1", "health_checkin_today",
                "{\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("今日打卡"));
        assertTrue(result.content().contains("喝水"));
        assertTrue(result.content().contains("早睡"), "应列出健康提醒（打卡与提醒联动），实际=" + result.content());
        assertTrue(result.content().contains("22:30"));
    }

    @Test
    void emptyTodayEncourages() {
        ToolResult result = tool.execute(new ToolCall("c2", "health_checkin_today",
                "{\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("还没有打卡"), "空数据应给出引导，实际=" + result.content());
    }

    @Test
    void weeklyViewShowsActiveDaysAndMedStreak() {
        checkinService.checkin("u1", HealthReminderKind.DRINK_WATER, LocalDate.now(), 1, "");
        checkinService.checkin("u1", HealthReminderKind.EXERCISE, LocalDate.now().minusDays(1), 1, "");
        checkinService.medTake("u1", "阿司匹林", LocalDate.now());
        ToolResult result = tool.execute(new ToolCall("c4", "health_checkin_today",
                "{\"user_id\":\"u1\",\"days\":7}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("最近 7 天有打卡 2 天"),
                "周报应统计活跃天数，实际=" + result.content());
        assertTrue(result.content().contains("服药连续 1 天"),
                "周报应显示服药连续天数，实际=" + result.content());
    }

    @Test
    void missingUserIdFails() {
        ToolResult result = tool.execute(new ToolCall("c3", "health_checkin_today", "{}"));
        assertFalse(result.success());
    }

    @Test
    void definitionIsValid() {
        var def = tool.definition();
        assertEquals("health_checkin_today", def.name());
        assertNotNull(def.description());
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
