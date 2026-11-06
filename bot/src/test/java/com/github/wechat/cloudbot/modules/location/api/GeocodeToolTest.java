package com.github.wechat.cloudbot.modules.location.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeocodeToolTest {

    private GeocodeTool tool;
    private LocationGateway locationGateway;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        locationGateway = mock(LocationGateway.class);
        objectMapper = new ObjectMapper();
        tool = new GeocodeTool(locationGateway, objectMapper);
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("geocode", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
        assertTrue(def.parametersJsonSchema().contains("address"));
    }

    @Test
    void geocodesAddressSuccessfully() {
        when(locationGateway.isAvailable()).thenReturn(true);
        when(locationGateway.geocode(anyString(), any()))
                .thenReturn("杭州市余杭区文一西路：120.12, 30.28 [查看地图](https://uri.amap.com/...)");

        ToolResult result = tool.execute(new ToolCall("c1", "geocode",
                "{\"address\":\"杭州市余杭区文一西路\"}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("余杭区"));
        verify(locationGateway).geocode(eq("杭州市余杭区文一西路"), eq(""));
    }

    @Test
    void geocodesWithCityBias() {
        when(locationGateway.isAvailable()).thenReturn(true);
        when(locationGateway.geocode(anyString(), any()))
                .thenReturn("北京市朝阳区望京SOHO：116.48, 39.99");

        ToolResult result = tool.execute(new ToolCall("c2", "geocode",
                "{\"address\":\"望京SOHO\",\"city\":\"北京\"}", "u1"));

        assertTrue(result.success());
        verify(locationGateway).geocode(eq("望京SOHO"), eq("北京"));
    }

    @Test
    void failsWhenAddressEmpty() {
        when(locationGateway.isAvailable()).thenReturn(true);

        ToolResult result = tool.execute(new ToolCall("c3", "geocode",
                "{\"address\":\"\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("address"));
    }

    @Test
    void failsWhenServiceUnavailable() {
        when(locationGateway.isAvailable()).thenReturn(false);

        ToolResult result = tool.execute(new ToolCall("c4", "geocode",
                "{\"address\":\"上海东方明珠\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("未配置") || result.content().contains("AMAP"));
    }

    @Test
    void handlesGatewayExceptionGracefully() {
        when(locationGateway.isAvailable()).thenReturn(true);
        when(locationGateway.geocode(anyString(), any()))
                .thenThrow(new RuntimeException("服务异常"));

        ToolResult result = tool.execute(new ToolCall("c5", "geocode",
                "{\"address\":\"测试地址\"}", "u1"));

        assertFalse(result.success());
    }
}
