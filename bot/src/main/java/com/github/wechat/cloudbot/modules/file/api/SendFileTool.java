package com.github.wechat.cloudbot.modules.file.api;

import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 工具定义：send_file（纯文本附件）。出站由编排层旁路发送。
 */
@Component
public class SendFileTool implements AgentTool {

    private final FileDeliveryService fileDeliveryService;

    public SendFileTool(FileDeliveryService fileDeliveryService) {
        this.fileDeliveryService = fileDeliveryService;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "send_file",
                "当用户明确要求生成/导出/发送一个【纯文本】文件时调用"
                        + "（如「导出成 md」「生成一份 txt」）。"
                        + "若用户要 Word/Excel/zip，请改用 export_document。"
                        + "不要用此工具发图片或视频。"
                        + "普通聊天请直接回复文字，不要调用本工具。",
                """
                {"type":"object","properties":{"file_name":{"type":"string","description":"附件文件名，含扩展名。仅允许文本类：txt/md/json/csv/log/xml/yml/yaml/html/css/js/ts/java/py/properties/sql/conf/ini"},"content":{"type":"string","description":"写入文件的完整纯文本内容（不要用 base64）"},"caption":{"type":"string","description":"发给用户的简短说明，例如「已为你生成笔记文件」"}},"required":["file_name","content"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            if (!fileDeliveryService.isEnabled()) {
                return ToolResult.fail(call.id(), call.name(), "文件功能未开启");
            }
            FileDeliveryService.OutboundFile file =
                    fileDeliveryService.buildSendFile(call.argumentsJson());
            return ToolResult.ok(
                    call.id(),
                    call.name(),
                    "已准备文本附件 "
                            + file.fileName()
                            + "（"
                            + file.bytes().length
                            + " bytes），由通道发送。");
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), e.getMessage());
        }
    }
}
