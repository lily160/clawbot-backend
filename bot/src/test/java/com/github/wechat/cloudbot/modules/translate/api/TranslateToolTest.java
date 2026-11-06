package com.github.wechat.cloudbot.modules.translate.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.chat.api.LlmGateway;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import com.github.wechat.cloudbot.platform.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TranslateToolTest {

    private TranslateTool tool;
    private LlmGateway llmGateway;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        llmGateway = mock(LlmGateway.class);
        objectMapper = new ObjectMapper();
        tool = new TranslateTool(llmGateway, objectMapper);
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("translate", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
        assertTrue(def.parametersJsonSchema().contains("text"));
        assertTrue(def.parametersJsonSchema().contains("target_lang"));
    }

    @Test
    void translatesTextSuccessfully() {
        when(llmGateway.chat(anyList()))
                .thenReturn("Hello, how are you?");

        ToolResult result = tool.execute(new ToolCall("c1", "translate",
                "{\"text\":\"你好，你怎么样？\",\"target_lang\":\"英文\"}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("Hello"));
        assertTrue(result.content().contains("英文"));
    }

    @Test
    void translatesWithSourceLang() {
        when(llmGateway.chat(anyList()))
                .thenReturn("Guten Morgen");

        ToolResult result = tool.execute(new ToolCall("c2", "translate",
                "{\"text\":\"早上好\",\"target_lang\":\"德文\",\"source_lang\":\"中文\"}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("Guten Morgen"));
        assertTrue(result.content().contains("德文"));
    }

    @Test
    void failsWhenTextEmpty() {
        ToolResult result = tool.execute(new ToolCall("c3", "translate",
                "{\"text\":\"\",\"target_lang\":\"英文\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("text"));
    }

    @Test
    void failsWhenTargetLangEmpty() {
        ToolResult result = tool.execute(new ToolCall("c4", "translate",
                "{\"text\":\"Hello\",\"target_lang\":\"\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("target_lang"));
    }

    @Test
    void failsWhenTargetLangMissing() {
        ToolResult result = tool.execute(new ToolCall("c5", "translate",
                "{\"text\":\"Hello\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("target_lang"));
    }

    @Test
    void handlesLlmExceptionGracefully() {
        when(llmGateway.chat(anyList()))
                .thenThrow(new RuntimeException("LLM 调用超时"));

        ToolResult result = tool.execute(new ToolCall("c6", "translate",
                "{\"text\":\"测试文本\",\"target_lang\":\"英文\"}", "u1"));

        assertFalse(result.success());
    }
}
