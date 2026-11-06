package com.github.wechat.cloudbot.modules.memory.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import com.github.wechat.cloudbot.platform.model.ChatMessage;
import com.github.wechat.cloudbot.platform.persistence.ChatMemoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemorySearchToolTest {

    @Mock
    private ChatMemoryRepository memoryStore;

    @Test
    void acceptsUserIdInjectedAsSnakeCase() {
        when(memoryStore.getHistory("u1"))
                .thenReturn(List.of(ChatMessage.user("昨天说好去瑞幸"), ChatMessage.assistant("好的")));

        MemorySearchTool tool = new MemorySearchTool(memoryStore, new ObjectMapper());
        ToolResult result =
                tool.execute(
                        new ToolCall(
                                "c1",
                                "memory_search",
                                "{\"query\":\"瑞幸\",\"user_id\":\"u1\"}"));

        assertTrue(result.success());
        assertTrue(result.content().contains("瑞幸"));
    }

    @Test
    void acceptsUserIdFromToolCallField() {
        when(memoryStore.getHistory("u2"))
                .thenReturn(List.of(ChatMessage.user("提醒我买牛奶")));

        MemorySearchTool tool = new MemorySearchTool(memoryStore, new ObjectMapper());
        ToolResult result =
                tool.execute(
                        new ToolCall(
                                "c2",
                                "memory_search",
                                "{\"query\":\"牛奶\"}",
                                "u2"));

        assertTrue(result.success());
        assertTrue(result.content().contains("牛奶"));
    }

    @Test
    void failsWhenUserIdMissing() {
        MemorySearchTool tool = new MemorySearchTool(memoryStore, new ObjectMapper());
        ToolResult result =
                tool.execute(new ToolCall("c3", "memory_search", "{\"query\":\"任意\"}"));

        assertFalse(result.success());
        assertTrue(result.content().contains("userId missing"));
    }
}
