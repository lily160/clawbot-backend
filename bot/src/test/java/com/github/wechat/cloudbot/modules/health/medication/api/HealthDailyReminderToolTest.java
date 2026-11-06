package com.github.wechat.cloudbot.modules.health.medication.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.checkin.service.HealthReminderService;
import com.github.wechat.cloudbot.modules.task.infra.ReminderProperties;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class HealthDailyReminderToolTest {

    private HealthDailyReminderTool tool;
    private ReminderService reminderService;

    @BeforeEach
    void setUp() {
        reminderService = new ReminderService(
                h2DataSource("healthdailyremindertooltest"),
                new ObjectMapper(),
                noPersistenceProps(),
                new ReminderProperties(),
                event -> {});
        reminderService.loadFromDisk();
        tool = new HealthDailyReminderTool(new HealthReminderService(reminderService), new ObjectMapper());
    }

    @Test
    void setDrinkWaterReminder() {
        ToolResult result = tool.execute(new ToolCall("c1", "health_daily_reminder",
                "{\"kind\":\"drink_water\",\"time\":\"08:00\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("每天"));
        assertTrue(result.content().contains("喝水"));
        assertTrue(result.content().contains("仅供参考"));
        assertEquals(1, reminderService.list("u1").size());
    }

    @Test
    void customReminder() {
        ToolResult result = tool.execute(new ToolCall("c2", "health_daily_reminder",
                "{\"kind\":\"custom\",\"time\":\"21:30\",\"note\":\"做眼保健操\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("21:30"));
        assertTrue(result.content().contains("做眼保健操"));
    }

    @Test
    void missingKindFails() {
        ToolResult result = tool.execute(new ToolCall("c3", "health_daily_reminder",
                "{\"time\":\"08:00\",\"user_id\":\"u1\"}"));
        assertFalse(result.success());
    }

    @Test
    void invalidTimeFails() {
        ToolResult result = tool.execute(new ToolCall("c4", "health_daily_reminder",
                "{\"kind\":\"exercise\",\"time\":\"25:99\",\"user_id\":\"u1\"}"));
        assertFalse(result.success());
        assertTrue(result.content().contains("HH:mm"));
    }

    @Test
    void missingUserIdFails() {
        ToolResult result = tool.execute(new ToolCall("c5", "health_daily_reminder",
                "{\"kind\":\"exercise\",\"time\":\"08:00\"}"));
        assertFalse(result.success());
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("health_daily_reminder", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
    }

    @Test
    void demoExerciseAt0730ReplyFormat() {
        ToolResult result = tool.execute(new ToolCall("c6", "health_daily_reminder",
                "{\"kind\":\"exercise\",\"time\":\"07:30\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("每天 07:30 运动"), "实际=" + result.content());
        assertTrue(result.content().contains("到点我会主动发消息提醒你"));
        // 已持久化一条每日 07:30 的 cron 提醒，可被调度推送
        assertEquals("0 30 7 * * ?", reminderService.list("u1").get(0).cronExpression());
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
