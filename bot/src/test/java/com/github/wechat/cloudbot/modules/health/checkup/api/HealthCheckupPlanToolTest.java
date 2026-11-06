package com.github.wechat.cloudbot.modules.health.checkup.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.file.api.FileDeliveryService;
import com.github.wechat.cloudbot.modules.health.checkup.infra.CheckupProperties;
import com.github.wechat.cloudbot.modules.health.checkup.service.CheckupService;
import com.github.wechat.cloudbot.modules.health.common.PatientRef;
import com.github.wechat.cloudbot.modules.health.profile.service.ProfileService;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthCheckupPlanToolTest {

    private HealthCheckupPlanTool tool;
    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        CheckupService service = new CheckupService(new CheckupProperties());
        service.init();
        CloudbotProperties cp = new CloudbotProperties();
        cp.getFile().setEnabled(false);
        FileDeliveryService fds = new FileDeliveryService(cp, new ObjectMapper());
        profileService = mock(ProfileService.class);
        tool = new HealthCheckupPlanTool(service, new ObjectMapper(), fds, profileService);
    }

    @Test
    void youngAdultCheckup() {
        ToolResult result = tool.execute(new ToolCall("c1", "health_checkup_plan",
                "{\"age\":25,\"gender\":\"male\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("推荐检查项目"));
        assertTrue(result.content().contains("仅供参考"));
    }

    @Test
    void middleAgedWithConditions() {
        ToolResult result = tool.execute(new ToolCall("c2", "health_checkup_plan",
                "{\"age\":55,\"gender\":\"female\",\"chronic_conditions\":[\"高血压\",\"糖尿病\"]}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("高血压"));
        assertTrue(result.content().contains("糖尿病"));
    }

    @Test
    void pregnancyCheckup() {
        ToolResult result = tool.execute(new ToolCall("c3", "health_checkup_plan",
                "{\"age\":28,\"gender\":\"female\",\"pregnancy_stage\":\"pregnant\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("孕期") || result.content().contains("备孕"));
    }

    @Test
    void teenCheckup() {
        ToolResult result = tool.execute(new ToolCall("c6", "health_checkup_plan",
                "{\"age\":12,\"gender\":\"male\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("青少年"));
    }

    @Test
    void invalidAgeFails() {
        ToolResult result = tool.execute(new ToolCall("c4", "health_checkup_plan",
                "{\"age\":-1}"));
        assertFalse(result.success());
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("health_checkup_plan", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
    }

    @Test
    void exportWithDisabledFileServiceReturnsNormal() {
        ToolResult result = tool.execute(new ToolCall("c5", "health_checkup_plan",
                "{\"age\":30,\"gender\":\"male\",\"export\":true}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("推荐检查项目"));
    }

    @Test
    void bmiAndSmokerAddScreening() {
        ToolResult result = tool.execute(new ToolCall("c7", "health_checkup_plan",
                "{\"age\":45,\"gender\":\"male\",\"bmi\":28.5,\"smoker\":true,\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("脂肪肝"), "肥胖应追加脂肪肝筛查，实际=" + result.content());
        assertTrue(result.content().contains("肺功能"), "吸烟应追加肺功能检测");
    }

    @Test
    void profileAutoFillWhenAgeMissing() {
        when(profileService.getProfiles("u1")).thenReturn(List.of(
                new PatientRef("1", "本人", "本人", "M", 40, List.of(), List.of("高血压"), null)));
        ToolResult result = tool.execute(new ToolCall("c8", "health_checkup_plan",
                "{\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("中老年") || result.content().contains("深度"),
                "应从档案读取年龄落到中老年套餐，实际=" + result.content());
        assertTrue(result.content().contains("高血压"));
        assertTrue(result.content().contains("动态血压监测"));
    }

    @Test
    void profileGenderNormalized() {
        when(profileService.getProfiles("u1")).thenReturn(List.of(
                new PatientRef("2", "母亲", "母亲", "F", 30, List.of(), List.of(), null)));
        ToolResult result = tool.execute(new ToolCall("c9", "health_checkup_plan",
                "{\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("乳腺B超"), "女性档案应追加乳腺/妇科检查，实际=" + result.content());
    }

    @Test
    void explicitParamsWinOverProfile() {
        when(profileService.getProfiles("u1")).thenReturn(List.of(
                new PatientRef("3", "本人", "本人", "M", 25, List.of(), List.of(), null)));
        ToolResult result = tool.execute(new ToolCall("c10", "health_checkup_plan",
                "{\"age\":45,\"gender\":\"male\",\"user_id\":\"u1\"}"));
        assertTrue(result.success());
        assertTrue(result.content().contains("中老年") || result.content().contains("深度"),
                "显式年龄应优先于档案，实际=" + result.content());
    }

    @Test
    void missingAgeAndNoProfileFails() {
        when(profileService.getProfiles("u1")).thenReturn(List.of());
        ToolResult result = tool.execute(new ToolCall("c11", "health_checkup_plan",
                "{\"user_id\":\"u1\"}"));
        assertFalse(result.success());
    }
}
