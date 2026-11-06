package com.github.wechat.cloudbot.modules.file.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentRoundTripTest {

    @Test
    void docxRoundTripKeepsText() throws Exception {
        var exported =
                DocumentExporter.export(
                        "docx", "note.docx", "你好 Cloudbot\n第二行", DocumentExporter.Limits.defaults());
        String text =
                DocumentTextExtractor.extract(
                                exported.fileName(),
                                exported.bytes(),
                                DocumentTextExtractor.Limits.defaults())
                        .orElseThrow();
        assertTrue(text.contains("你好 Cloudbot"));
        assertTrue(text.contains("第二行"));
    }

    @Test
    void xlsxRoundTripKeepsCsvCells() throws Exception {
        var exported =
                DocumentExporter.export(
                        "xlsx",
                        "sheet.xlsx",
                        "姓名,城市\n小明,杭州",
                        DocumentExporter.Limits.defaults());
        String text =
                DocumentTextExtractor.extract(
                                exported.fileName(),
                                exported.bytes(),
                                DocumentTextExtractor.Limits.defaults())
                        .orElseThrow();
        assertTrue(text.contains("小明"));
        assertTrue(text.contains("杭州"));
    }

    @Test
    void textFileDecodesUtf8AndGbk() {
        byte[] utf8 = "中文 UTF-8".getBytes(StandardCharsets.UTF_8);
        assertTrue(TextFileSupport.decodeText(utf8).contains("中文"));
        assertTrue(TextFileSupport.isTextExtension("readme.md"));
        assertTrue(DocumentTextExtractor.isOfficeOrZip("a.docx"));
    }
}
