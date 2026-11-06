package com.github.wechat.cloudbot.modules.health.profile.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.profile.service.ProfileService;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HealthProfileUpsertToolTest {

    private HealthProfileUpsertTool tool;
    private ProfileService profileService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        profileService = mock(ProfileService.class);
        objectMapper = new ObjectMapper();
        tool = new HealthProfileUpsertTool(profileService, objectMapper);
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("health_profile_upsert", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
        assertTrue(def.parametersJsonSchema().contains("relation"));
        assertTrue(def.parametersJsonSchema().contains("isConfirm"));
    }

    @Test
    void upsertWithBasicFields() {
        when(profileService.handleUpsert(any(), eq("u1"), eq(false)))
                .thenReturn("已为您整理好【本人】的档案。");

        ToolResult result = tool.execute(new ToolCall("c1", "health_profile_upsert",
                "{\"relation\":\"本人\",\"name\":\"张三\",\"gender\":\"男\",\"birthYear\":1990,\"isConfirm\":false,"
                + "\"allergies\":[\"青霉素\"],\"chronicDiseases\":[\"高血压\"]}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("本人"));
        verify(profileService).handleUpsert(argThat(e ->
                "本人".equals(e.getRelation()) &&
                "张三".equals(e.getName()) &&
                "男".equals(e.getGender()) &&
                e.getBirthYear() == 1990 &&
                e.getAllergies().contains("青霉素") &&
                e.getChronicDiseases().contains("高血压")
        ), eq("u1"), eq(false));
    }

    @Test
    void confirmSavesProfile() {
        when(profileService.handleUpsert(any(), eq("u1"), eq(true)))
                .thenReturn("✅ 已确认并保存档案。");

        ToolResult result = tool.execute(new ToolCall("c2", "health_profile_upsert",
                "{\"relation\":\"爸爸\",\"isConfirm\":true}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("确认") || result.content().contains("保存"));
        verify(profileService).handleUpsert(any(), eq("u1"), eq(true));
    }

    @Test
    void missingUserIdFails() {
        ToolResult result = tool.execute(new ToolCall("c3", "health_profile_upsert",
                "{\"relation\":\"本人\",\"isConfirm\":false}", null));

        assertFalse(result.success());
    }

    @Test
    void emptyArgsHandledGracefully() {
        when(profileService.handleUpsert(any(), eq("u1"), eq(false)))
                .thenReturn("已为您整理好档案");

        ToolResult result = tool.execute(new ToolCall("c4", "health_profile_upsert",
                "{\"relation\":\"本人\",\"isConfirm\":false}", "u1"));

        assertTrue(result.success());
    }

    @Test
    void listsWithoutDuplicates() {
        when(profileService.handleUpsert(any(), eq("u1"), eq(false)))
                .thenReturn("已为您整理好【本人】的档案。");

        ToolResult result = tool.execute(new ToolCall("c5", "health_profile_upsert",
                "{\"relation\":\"本人\",\"isConfirm\":false,\"allergies\":[\"青霉素\",\"头孢\"]}", "u1"));

        assertTrue(result.success());
        verify(profileService).handleUpsert(argThat(e ->
                e.getAllergies().size() == 2 &&
                e.getAllergies().contains("青霉素") &&
                e.getAllergies().contains("头孢")
        ), eq("u1"), eq(false));
    }
}
