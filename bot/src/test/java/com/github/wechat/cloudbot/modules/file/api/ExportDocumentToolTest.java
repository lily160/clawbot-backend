package com.github.wechat.cloudbot.modules.file.api;

import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExportDocumentToolTest {

    private ExportDocumentTool tool;
    private FileDeliveryService fileDeliveryService;

    @BeforeEach
    void setUp() {
        fileDeliveryService = mock(FileDeliveryService.class);
        tool = new ExportDocumentTool(fileDeliveryService);
    }

    @Test
    void toolDefinitionIsValid() {
        var def = tool.definition();
        assertEquals("export_document", def.name());
        assertNotNull(def.description());
        assertNotNull(def.parametersJsonSchema());
        assertTrue(def.parametersJsonSchema().contains("format"));
        assertTrue(def.parametersJsonSchema().contains("file_name"));
        assertTrue(def.parametersJsonSchema().contains("content"));
    }

    @Test
    void exportsDocxSuccessfully() throws Exception {
        when(fileDeliveryService.isEnabled()).thenReturn(true);
        when(fileDeliveryService.buildExportDocument(anyString()))
                .thenReturn(new FileDeliveryService.OutboundFile(
                        "report.docx", new byte[]{1, 2, 3, 4, 5}, "文档已生成"));

        ToolResult result = tool.execute(new ToolCall("c1", "export_document",
                "{\"format\":\"docx\",\"file_name\":\"report.docx\",\"content\":\"Hello World\"}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("report.docx"));
        assertTrue(result.content().contains("5 bytes"));
    }

    @Test
    void exportsXlsxSuccessfully() throws Exception {
        when(fileDeliveryService.isEnabled()).thenReturn(true);
        when(fileDeliveryService.buildExportDocument(anyString()))
                .thenReturn(new FileDeliveryService.OutboundFile(
                        "data.xlsx", new byte[]{10, 20, 30}, null));

        ToolResult result = tool.execute(new ToolCall("c2", "export_document",
                "{\"format\":\"xlsx\",\"file_name\":\"data.xlsx\",\"content\":\"name,age\\nAlice,30\"}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("data.xlsx"));
    }

    @Test
    void exportsZipSuccessfully() throws Exception {
        when(fileDeliveryService.isEnabled()).thenReturn(true);
        when(fileDeliveryService.buildExportDocument(anyString()))
                .thenReturn(new FileDeliveryService.OutboundFile(
                        "archive.zip", new byte[]{1, 2}, null));

        ToolResult result = tool.execute(new ToolCall("c3", "export_document",
                "{\"format\":\"zip\",\"file_name\":\"archive.zip\",\"content\":\"[{\\\"path\\\":\\\"a.txt\\\",\\\"text\\\":\\\"hi\\\"}]\"}", "u1"));

        assertTrue(result.success());
        assertTrue(result.content().contains("archive.zip"));
    }

    @Test
    void failsWhenServiceNotEnabled() {
        when(fileDeliveryService.isEnabled()).thenReturn(false);

        ToolResult result = tool.execute(new ToolCall("c4", "export_document",
                "{\"format\":\"docx\",\"file_name\":\"test.docx\",\"content\":\"test\"}", "u1"));

        assertFalse(result.success());
        assertTrue(result.content().contains("未开启"));
    }

    @Test
    void handlesBuildExceptionGracefully() throws Exception {
        when(fileDeliveryService.isEnabled()).thenReturn(true);
        when(fileDeliveryService.buildExportDocument(anyString()))
                .thenThrow(new IllegalArgumentException("导出内容为空"));

        ToolResult result = tool.execute(new ToolCall("c5", "export_document",
                "{\"format\":\"docx\",\"file_name\":\"empty.docx\",\"content\":\"\"}", "u1"));

        assertFalse(result.success());
    }
}
