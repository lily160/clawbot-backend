package com.github.wechat.cloudbot.modules.health.profile.api;

import com.github.wechat.cloudbot.modules.health.common.PatientRef;
import com.github.wechat.cloudbot.modules.health.profile.service.ProfileService;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class HealthProfileGetToolTest {

    private HealthProfileGetTool tool;
    private ProfileService profileService;

    @BeforeEach
    void setUp() {
        profileService = mock(ProfileService.class);
        tool = new HealthProfileGetTool(profileService);
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("health_profile_get", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
    }

    @Test
    void returnsProfilesWhenFound() {
        PatientRef ref = new PatientRef("p1", "妈妈", "妈妈", "女", 55,
                List.of("青霉素"), List.of("糖尿病"), "2020年胆囊切除术");
        when(profileService.getProfiles(eq("u1"))).thenReturn(List.of(ref));

        ToolResult result = tool.execute(new ToolCall("c1", "health_profile_get", "{}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("妈妈"));
        assertTrue(result.content().contains("55岁"));
        assertTrue(result.content().contains("青霉素"));
        assertTrue(result.content().contains("糖尿病"));
    }

    @Test
    void returnsMultipleProfiles() {
        PatientRef ref1 = new PatientRef("p1", "本人", "本人", "男", 30,
                List.of(), List.of(), null);
        PatientRef ref2 = new PatientRef("p2", "爸爸", "爸爸", "男", 62,
                List.of("头孢"), List.of("高血压", "高血脂"), null);
        when(profileService.getProfiles(eq("u1"))).thenReturn(List.of(ref1, ref2));

        ToolResult result = tool.execute(new ToolCall("c2", "health_profile_get", "{}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("本人"));
        assertTrue(result.content().contains("爸爸"));
        assertTrue(result.content().contains("高血压"));
        assertTrue(result.content().contains("高血脂"));
    }

    @Test
    void returnsEmptyMessageWhenNoProfiles() {
        when(profileService.getProfiles(eq("u1"))).thenReturn(List.of());

        ToolResult result = tool.execute(new ToolCall("c3", "health_profile_get", "{}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("未查询到"));
    }

    @Test
    void returnsEmptyMessageWhenNull() {
        when(profileService.getProfiles(eq("u1"))).thenReturn(null);

        ToolResult result = tool.execute(new ToolCall("c4", "health_profile_get", "{}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("未查询到"));
    }

    @Test
    void missingUserIdFails() {
        ToolResult result = tool.execute(new ToolCall("c5", "health_profile_get", "{}", null));

        assertFalse(result.success());
    }
}
