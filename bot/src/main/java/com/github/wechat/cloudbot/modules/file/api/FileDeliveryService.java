package com.github.wechat.cloudbot.modules.file.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.file.service.DocumentExporter;
import com.github.wechat.cloudbot.modules.file.service.TextFileSupport;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import com.github.wechat.cloudbot.platform.config.Strings;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 从 tool 参数组装出站文件（send_file / export_document）。
 */
@Service
public class FileDeliveryService {

    public record OutboundFile(String fileName, byte[] bytes, String caption) {}

    private final CloudbotProperties cloudbotProperties;
    private final ObjectMapper objectMapper;

    public FileDeliveryService(CloudbotProperties cloudbotProperties, ObjectMapper objectMapper) {
        this.cloudbotProperties = cloudbotProperties;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return cloudbotProperties.getFile().isEnabled();
    }

    public OutboundFile buildSendFile(String argumentsJson) throws Exception {
        CloudbotProperties.File cfg = cloudbotProperties.getFile();
        if (!cfg.isEnabled()) {
            throw new IllegalStateException("文件功能未开启");
        }
        JsonNode args = objectMapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
        String fileName = Strings.firstNonBlank(Strings.text(args, "file_name"), Strings.text(args, "filename"));
        String content = Strings.text(args, "content");
        if (content.isBlank()) {
            throw new IllegalArgumentException("生成的文件内容为空");
        }
        String safeName = TextFileSupport.sanitizeFileName(fileName);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        int maxOut = Math.max(1024, cfg.getMaxOutboundBytes());
        if (bytes.length > maxOut) {
            throw new IllegalArgumentException(
                    "要发送的文件太大（约 "
                            + TextFileSupport.formatSizeKb(bytes.length)
                            + "），上限 "
                            + TextFileSupport.formatSizeKb(maxOut));
        }
        String caption = blankToNull(Strings.text(args, "caption"));
        return new OutboundFile(safeName, bytes, caption);
    }

    public OutboundFile buildExportDocument(String argumentsJson) throws Exception {
        CloudbotProperties.File cfg = cloudbotProperties.getFile();
        if (!cfg.isEnabled()) {
            throw new IllegalStateException("文件功能未开启");
        }
        JsonNode args = objectMapper.readTree(argumentsJson == null ? "{}" : argumentsJson);
        String format = Strings.text(args, "format").toLowerCase();
        String fileName = Strings.firstNonBlank(Strings.text(args, "file_name"), Strings.text(args, "filename"));
        String content = Strings.text(args, "content");
        if (content.isBlank()) {
            throw new IllegalArgumentException("导出内容为空");
        }
        if (!format.equals("docx") && !format.equals("xlsx") && !format.equals("zip")) {
            throw new IllegalArgumentException("format 须为 docx/xlsx/zip，got: " + format);
        }
        DocumentExporter.Limits lim =
                new DocumentExporter.Limits(
                        cfg.getMaxXlsxRows(),
                        cfg.getMaxXlsxCols(),
                        cfg.getMaxZipEntries(),
                        cfg.getMaxOutboundBytes());
        DocumentExporter.ExportedFile exported =
                DocumentExporter.export(format, fileName, content, lim);
        return new OutboundFile(
                exported.fileName(), exported.bytes(), blankToNull(Strings.text(args, "caption")));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
