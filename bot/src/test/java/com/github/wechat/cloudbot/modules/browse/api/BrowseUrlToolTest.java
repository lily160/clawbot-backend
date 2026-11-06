package com.github.wechat.cloudbot.modules.browse.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BrowseUrlToolTest {

    private BrowseUrlTool tool;
    private BrowseGateway browseGateway;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        browseGateway = mock(BrowseGateway.class);
        objectMapper = new ObjectMapper();
        tool = new BrowseUrlTool(browseGateway, objectMapper);
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("browse_url", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
        assertTrue(def.parametersJsonSchema().contains("url"));
    }

    @Test
    void fetchesPageSuccessfully() {
        when(browseGateway.isAvailable()).thenReturn(true);
        when(browseGateway.fetchPage(anyString()))
                .thenReturn("Page summary: this is a test article...");

        ToolResult result = tool.execute(new ToolCall("c1", "browse_url",
                "{\"url\":\"https://example.com/article\"}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("summary"));
        verify(browseGateway).fetchPage(eq("https://example.com/article"));
    }

    @Test
    void failsWhenUrlEmpty() {
        when(browseGateway.isAvailable()).thenReturn(true);

        ToolResult result = tool.execute(new ToolCall("c2", "browse_url",
                "{\"url\":\"\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("url"));
    }

    @Test
    void failsWhenServiceUnavailable() {
        when(browseGateway.isAvailable()).thenReturn(false);

        ToolResult result = tool.execute(new ToolCall("c3", "browse_url",
                "{\"url\":\"https://example.com\"}", "u1"));

        assertFalse(result.success());
    }

    @Test
    void handlesGatewayExceptionGracefully() {
        when(browseGateway.isAvailable()).thenReturn(true);
        when(browseGateway.fetchPage(anyString()))
                .thenThrow(new RuntimeException("连接超时"));

        ToolResult result = tool.execute(new ToolCall("c4", "browse_url",
                "{\"url\":\"https://timeout.example.com\"}", "u1"));

        assertFalse(result.success());
    }
}
