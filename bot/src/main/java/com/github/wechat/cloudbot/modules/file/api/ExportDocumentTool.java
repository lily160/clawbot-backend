package com.github.wechat.cloudbot.modules.file.api;

import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 工具定义：export_document（docx/xlsx/zip）。出站由编排层旁路发送。
 */
@Component
public class ExportDocumentTool implements AgentTool {

    private final FileDeliveryService fileDeliveryService;

    public ExportDocumentTool(FileDeliveryService fileDeliveryService) {
        this.fileDeliveryService = fileDeliveryService;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "export_document",
                "当用户明确要求导出 Word(docx)、Excel(xlsx) 或 zip 压缩包时调用。"
                        + "纯文本附件请用 send_file；图片/视频请用 generate_image / generate_video。"
                        + "普通聊天不要调用。"
                        + "content 约定：docx=正文纯文本（换行即新段落）；"
                        + "xlsx=CSV/TSV 或 JSON 二维数组；"
                        + "zip=JSON 数组 [{\"path\":\"a.txt\",\"text\":\"...\"},...]。",
                """
                {"type":"object","properties":{"format":{"type":"string","description":"docx / xlsx / zip"},"file_name":{"type":"string","description":"期望文件名，含扩展名"},"content":{"type":"string","description":"导出内容：docx 正文；xlsx 为 CSV/TSV 或 JSON 二维数组；zip 为 JSON 条目数组"},"caption":{"type":"string","description":"发给用户的简短说明"}},"required":["format","file_name","content"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            if (!fileDeliveryService.isEnabled()) {
                return ToolResult.fail(call.id(), call.name(), "文件功能未开启");
            }
            FileDeliveryService.OutboundFile file =
                    fileDeliveryService.buildExportDocument(call.argumentsJson());
            return ToolResult.ok(
                    call.id(),
                    call.name(),
                    "已准备文档 "
                            + file.fileName()
                            + "（"
                            + file.bytes().length
                            + " bytes），由通道发送。");
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), e.getMessage());
        }
    }
}
