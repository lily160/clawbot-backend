package com.github.wechat.cloudbot.modules.media.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerateVideoToolTest {

    private GenerateVideoTool tool;
    private MediaGateway mediaGateway;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mediaGateway = mock(MediaGateway.class);
        objectMapper = new ObjectMapper();
        tool = new GenerateVideoTool(mediaGateway, objectMapper);
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("generate_video", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
        assertTrue(def.parametersJsonSchema().contains("prompt"));
    }

    @Test
    void generatesVideoSuccessfully() {
        when(mediaGateway.canGenerateVideo()).thenReturn(true);
        when(mediaGateway.generateVideo(anyString()))
                .thenReturn(new MediaGateway.GeneratedVideo(
                        new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, 5000));

        ToolResult result = tool.execute(new ToolCall("c1", "generate_video",
                "{\"prompt\":\"一段夏日海边视频\"}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("8 bytes"));
        assertTrue(result.content().contains("5000"));
        verify(mediaGateway).generateVideo(eq("一段夏日海边视频"));
    }

    @Test
    void failsWhenPromptEmpty() {
        when(mediaGateway.canGenerateVideo()).thenReturn(true);

        ToolResult result = tool.execute(new ToolCall("c2", "generate_video",
                "{\"prompt\":\"\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("prompt"));
    }

    @Test
    void failsWhenVideoUnavailable() {
        when(mediaGateway.canGenerateVideo()).thenReturn(false);

        ToolResult result = tool.execute(new ToolCall("c3", "generate_video",
                "{\"prompt\":\"航拍城市夜景\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("unavailable"));
    }

    @Test
    void includesCaptionInResult() {
        when(mediaGateway.canGenerateVideo()).thenReturn(true);
        when(mediaGateway.generateVideo(anyString()))
                .thenReturn(new MediaGateway.GeneratedVideo(
                        new byte[]{1, 2, 3}, 3000));

        ToolResult result = tool.execute(new ToolCall("c4", "generate_video",
                "{\"prompt\":\"雪景\",\"caption\":\"视频正在生成中…\"}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("视频正在生成中"));
    }

    @Test
    void handlesGatewayExceptionGracefully() {
        when(mediaGateway.canGenerateVideo()).thenReturn(true);
        when(mediaGateway.generateVideo(anyString()))
                .thenThrow(new RuntimeException("视频生成失败"));

        ToolResult result = tool.execute(new ToolCall("c5", "generate_video",
                "{\"prompt\":\"测试视频\"}", "u1"));

        assertFalse(result.success());
    }
}
