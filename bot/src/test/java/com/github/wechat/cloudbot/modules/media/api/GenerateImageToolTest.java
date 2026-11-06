package com.github.wechat.cloudbot.modules.media.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerateImageToolTest {

    private GenerateImageTool tool;
    private MediaGateway mediaGateway;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mediaGateway = mock(MediaGateway.class);
        objectMapper = new ObjectMapper();
        tool = new GenerateImageTool(mediaGateway, objectMapper);
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("generate_image", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
        assertTrue(def.parametersJsonSchema().contains("prompt"));
    }

    @Test
    void generatesImageSuccessfully() {
        when(mediaGateway.canGenerateImage()).thenReturn(true);
        when(mediaGateway.generateImage(anyString()))
                .thenReturn(new byte[]{1, 2, 3, 4, 5});

        ToolResult result = tool.execute(new ToolCall("c1", "generate_image",
                "{\"prompt\":\"一只可爱的猫\"}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("5 bytes"));
        verify(mediaGateway).generateImage(eq("一只可爱的猫"));
    }

    @Test
    void failsWhenPromptEmpty() {
        when(mediaGateway.canGenerateImage()).thenReturn(true);

        ToolResult result = tool.execute(new ToolCall("c2", "generate_image",
                "{\"prompt\":\"\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("prompt"));
    }

    @Test
    void failsWhenPromptMissing() {
        when(mediaGateway.canGenerateImage()).thenReturn(true);

        ToolResult result = tool.execute(new ToolCall("c3", "generate_image",
                "{}", "u1"));

        assertFalse(result.success());
    }

    @Test
    void failsWhenImageUnavailable() {
        when(mediaGateway.canGenerateImage()).thenReturn(false);

        ToolResult result = tool.execute(new ToolCall("c4", "generate_image",
                "{\"prompt\":\"一幅风景画\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("unavailable"));
    }

    @Test
    void includesCaptionInResult() {
        when(mediaGateway.canGenerateImage()).thenReturn(true);
        when(mediaGateway.generateImage(anyString()))
                .thenReturn(new byte[]{10, 20});

        ToolResult result = tool.execute(new ToolCall("c5", "generate_image",
                "{\"prompt\":\"日出\",\"caption\":\"正在为您绘制…\"}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("正在为您绘制"));
    }

    @Test
    void handlesGatewayExceptionGracefully() {
        when(mediaGateway.canGenerateImage()).thenReturn(true);
        when(mediaGateway.generateImage(anyString()))
                .thenThrow(new RuntimeException("火山 API 超时"));

        ToolResult result = tool.execute(new ToolCall("c6", "generate_image",
                "{\"prompt\":\"测试图\"}", "u1"));

        assertFalse(result.success());
    }
}
