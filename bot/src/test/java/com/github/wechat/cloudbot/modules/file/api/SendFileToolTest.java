package com.github.wechat.cloudbot.modules.file.api;

import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SendFileToolTest {

    private SendFileTool tool;
    private FileDeliveryService fileDeliveryService;

    @BeforeEach
    void setUp() {
        fileDeliveryService = mock(FileDeliveryService.class);
        tool = new SendFileTool(fileDeliveryService);
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("send_file", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
        assertTrue(def.parametersJsonSchema().contains("file_name"));
        assertTrue(def.parametersJsonSchema().contains("content"));
    }

    @Test
    void sendsTextFileSuccessfully() throws Exception {
        when(fileDeliveryService.isEnabled()).thenReturn(true);
        when(fileDeliveryService.buildSendFile(anyString()))
                .thenReturn(new FileDeliveryService.OutboundFile(
                        "notes.md", "Hello World".getBytes(), "笔记已生成"));

        ToolResult result = tool.execute(new ToolCall("c1", "send_file",
                "{\"file_name\":\"notes.md\",\"content\":\"# Hello World\"}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("notes.md"));
    }

    @Test
    void sendsCsvFileSuccessfully() throws Exception {
        when(fileDeliveryService.isEnabled()).thenReturn(true);
        when(fileDeliveryService.buildSendFile(anyString()))
                .thenReturn(new FileDeliveryService.OutboundFile(
                        "data.csv", "a,b,c".getBytes(), null));

        ToolResult result = tool.execute(new ToolCall("c2", "send_file",
                "{\"file_name\":\"data.csv\",\"content\":\"a,b,c\"}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("data.csv"));
    }

    @Test
    void failsWhenServiceNotEnabled() {
        when(fileDeliveryService.isEnabled()).thenReturn(false);

        ToolResult result = tool.execute(new ToolCall("c3", "send_file",
                "{\"file_name\":\"test.txt\",\"content\":\"hello\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("未开启"));
    }

    @Test
    void handlesBuildExceptionGracefully() throws Exception {
        when(fileDeliveryService.isEnabled()).thenReturn(true);
        when(fileDeliveryService.buildSendFile(anyString()))
                .thenThrow(new IllegalArgumentException("生成的文件内容为空"));

        ToolResult result = tool.execute(new ToolCall("c4", "send_file",
                "{\"file_name\":\"empty.txt\",\"content\":\"\"}", "u1"));

        assertFalse(result.success());
    }
}
