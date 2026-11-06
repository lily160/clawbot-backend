package com.github.wechat.cloudbot.modules.health.checkin.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.checkin.service.CheckinService;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

class HealthCheckinAddToolTest {

    private HealthCheckinAddTool tool;

    @BeforeEach
    void setUp() {
        CheckinService checkinService = new CheckinService(h2DataSource("checkinaddtooltest"));
        checkinService.ensureTable();
        tool = new HealthCheckinAddTool(checkinService, new ObjectMapper());
    }

    @Test
    void recordsDrinkWater() {
        ToolResult result = tool.execute(new ToolCall("c1", "health_checkin_add",
                "{\"kind\":\"drink_water\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("喝水"));
        assertTrue(result.content().contains("仅供参考"));
    }

    @Test
    void accumulatesCountSameDay() {
        tool.execute(new ToolCall("c1", "health_checkin_add",
                "{\"kind\":\"drink_water\",\"count\":2,\"user_id\":\"u1\"}"));
        ToolResult result = tool.execute(new ToolCall("c2", "health_checkin_add",
                "{\"kind\":\"drink_water\",\"count\":3,\"user_id\":\"u1\"}"));
        assertTrue(result.content().contains("× 5"), "同一天同类应累加，实际=" + result.content());
    }

    @Test
    void followupKindNotSupportedForCheckin() {
        ToolResult result = tool.execute(new ToolCall("c3", "health_checkin_add",
                "{\"kind\":\"followup\",\"user_id\":\"u1\"}"));
        assertFalse(result.success());
    }

    @Test
    void unknownKindFails() {
        ToolResult result = tool.execute(new ToolCall("c4", "health_checkin_add",
                "{\"kind\":\"smoke\",\"user_id\":\"u1\"}"));
        assertFalse(result.success());
    }

    @Test
    void badDateFails() {
        ToolResult result = tool.execute(new ToolCall("c5", "health_checkin_add",
                "{\"kind\":\"drink_water\",\"date\":\"2026-13-99\",\"user_id\":\"u1\"}"));
        assertFalse(result.success());
    }

    @Test
    void missingUserIdFails() {
        ToolResult result = tool.execute(new ToolCall("c6", "health_checkin_add",
                "{\"kind\":\"drink_water\"}"));
        assertFalse(result.success());
    }

    @Test
    void definitionIsValid() {
        var def = tool.definition();
        assertEquals("health_checkin_add", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
    }

    private static DriverManagerDataSource h2DataSource(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:" + name + "_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        return ds;
    }
}
