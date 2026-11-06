package com.github.wechat.cloudbot.modules.weather.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.weather.infra.AmapProperties;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueryWeatherToolTest {

    private QueryWeatherTool tool;
    private WeatherGateway weatherGateway;
    private AmapProperties amapProperties;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        weatherGateway = mock(WeatherGateway.class);
        amapProperties = new AmapProperties();
        amapProperties.setRefinePrompt("""
                【当前日期】
                {today}

                【天气】
                {weather}

                用户问：{question}
                请回答。""");
        objectMapper = new ObjectMapper();
        tool = new QueryWeatherTool(weatherGateway, amapProperties, objectMapper);
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("query_weather", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
        assertTrue(def.parametersJsonSchema().contains("city"));
    }

    @Test
    void queriesWeatherForCity() {
        when(weatherGateway.isAvailable()).thenReturn(true);
        when(weatherGateway.query(eq("杭州"), anyString()))
                .thenReturn("杭州 晴 25℃");

        ToolResult result = tool.execute(new ToolCall("c1", "query_weather",
                "{\"city\":\"杭州\"}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("晴"));
        assertTrue(result.content().contains("25℃"));
        assertTrue(result.content().contains("杭州"));
    }

    @Test
    void failsWhenCityIsEmpty() {
        when(weatherGateway.isAvailable()).thenReturn(true);

        ToolResult result = tool.execute(new ToolCall("c2", "query_weather",
                "{\"city\":\"\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("city"));
    }

    @Test
    void failsWhenServiceUnavailable() {
        when(weatherGateway.isAvailable()).thenReturn(false);

        ToolResult result = tool.execute(new ToolCall("c3", "query_weather",
                "{\"city\":\"北京\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("未配置") || result.content().contains("AMAP"));
    }

    @Test
    void usesExtensionsFromArgs() {
        when(weatherGateway.isAvailable()).thenReturn(true);
        when(weatherGateway.query(eq("上海"), anyString()))
                .thenReturn("上海 多云 22℃");

        ToolResult result = tool.execute(new ToolCall("c4", "query_weather",
                "{\"city\":\"上海\",\"extensions\":\"base\"}", "u1"));

        assertTrue(result.success());
        verify(weatherGateway).query(eq("上海"), anyString());
    }

    @Test
    void handlesGatewayExceptionGracefully() {
        when(weatherGateway.isAvailable()).thenReturn(true);
        when(weatherGateway.query(anyString(), anyString()))
                .thenThrow(new RuntimeException("网络超时"));

        ToolResult result = tool.execute(new ToolCall("c5", "query_weather",
                "{\"city\":\"深圳\"}", "u1"));

        assertFalse(result.success());
    }

    @Test
    void handlesOverseasExceptionGracefully() {
        when(weatherGateway.isAvailable()).thenReturn(true);
        when(weatherGateway.query(anyString(), anyString()))
                .thenThrow(new IllegalStateException("仅支持中国大陆城市"));

        ToolResult result = tool.execute(new ToolCall("c6", "query_weather",
                "{\"city\":\"东京\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("web_search"));
    }
}
