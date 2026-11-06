package com.github.wechat.cloudbot.modules.location.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueryPoiToolTest {

    private QueryPoiTool tool;
    private LocationGateway locationGateway;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        locationGateway = mock(LocationGateway.class);
        objectMapper = new ObjectMapper();
        tool = new QueryPoiTool(locationGateway, objectMapper);
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("query_poi", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
        assertTrue(def.parametersJsonSchema().contains("keywords"));
    }

    @Test
    void searchesPoiSuccessfully() {
        when(locationGateway.isAvailable()).thenReturn(true);
        when(locationGateway.searchPoi(anyString(), any(), any()))
                .thenReturn("找到 3 家咖啡店：\n1. 星巴克 (距离 200m)\n2. 瑞幸 (距离 350m)");

        ToolResult result = tool.execute(new ToolCall("c1", "query_poi",
                "{\"keywords\":\"咖啡\",\"city\":\"杭州\"}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("咖啡店"));
        verify(locationGateway).searchPoi(eq("咖啡"), eq("杭州"), eq(""));
    }

    @Test
    void searchesWithStreet() {
        when(locationGateway.isAvailable()).thenReturn(true);
        when(locationGateway.searchPoi(anyString(), any(), any()))
                .thenReturn("附近火锅店：海底捞 (800m)");

        ToolResult result = tool.execute(new ToolCall("c2", "query_poi",
                "{\"keywords\":\"火锅\",\"city\":\"北京\",\"street\":\"望京\"}", "u1"));

        assertTrue(result.success());
        verify(locationGateway).searchPoi(eq("火锅"), eq("北京"), eq("望京"));
    }

    @Test
    void failsWhenKeywordsEmpty() {
        when(locationGateway.isAvailable()).thenReturn(true);

        ToolResult result = tool.execute(new ToolCall("c3", "query_poi",
                "{\"keywords\":\"\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("keywords"));
    }

    @Test
    void failsWhenServiceUnavailable() {
        when(locationGateway.isAvailable()).thenReturn(false);

        ToolResult result = tool.execute(new ToolCall("c4", "query_poi",
                "{\"keywords\":\"餐馆\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("未配置") || result.content().contains("AMAP"));
    }

    @Test
    void handlesGatewayExceptionGracefully() {
        when(locationGateway.isAvailable()).thenReturn(true);
        when(locationGateway.searchPoi(anyString(), any(), any()))
                .thenThrow(new RuntimeException("高德 API 限流"));

        ToolResult result = tool.execute(new ToolCall("c5", "query_poi",
                "{\"keywords\":\"地铁站\"}", "u1"));

        assertFalse(result.success());
    }
}
