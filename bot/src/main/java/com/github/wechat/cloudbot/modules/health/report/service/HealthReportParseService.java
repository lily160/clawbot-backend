package com.github.wechat.cloudbot.modules.health.report.service;

import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseRequest;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import com.github.wechat.cloudbot.modules.health.report.domain.ReportPageText;
import com.github.wechat.cloudbot.modules.health.report.infra.AliyunOcrClient;
import com.github.wechat.cloudbot.modules.health.report.infra.PdfReportExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 体检报告解析编排器：PDF 原生文本优先，扫描页走 OCR 兜底。
 */
@Service
public class HealthReportParseService {

    private static final Logger log = LoggerFactory.getLogger(HealthReportParseService.class);

    private static final int OCR_DPI = 120;
    private static final String REPORT_TYPE = "体检报告";
    private static final String REVIEW_PROMPT = "请结合原始报告并咨询专业医生复核。";

    private final PdfReportExtractor pdfReportExtractor;
    private final AliyunOcrClient ocrClient;
    private final HealthIndicatorParser indicatorParser;

    @Autowired
    public HealthReportParseService(
            PdfReportExtractor pdfReportExtractor,
            AliyunOcrClient ocrClient,
            HealthIndicatorParser indicatorParser) {
        this.pdfReportExtractor = pdfReportExtractor;
        this.ocrClient = ocrClient;
        this.indicatorParser = indicatorParser;
    }

    /**
     * 仅供测试继承时使用，避免测试为了替身还要伪造完整依赖图。
     */
    protected HealthReportParseService() {
        this.pdfReportExtractor = null;
        this.ocrClient = null;
        this.indicatorParser = null;
    }

    /**
     * 解析体检 PDF：原生文本直接走指标解析，扫描页先渲染再 OCR。
     */
    public HealthReportParseResult parse(HealthReportParseRequest request) {
        Objects.requireNonNull(request, "request");
        if (pdfReportExtractor == null || ocrClient == null || indicatorParser == null) {
            throw new IllegalStateException("HealthReportParseService is not initialized");
        }

        Path pdfPath = request.pdfPath();
        List<ReportPageText> extractedPages = pdfReportExtractor.extractText(pdfPath);
        List<ReportPageText> normalizedPages = new ArrayList<>(extractedPages.size());
        for (ReportPageText page : extractedPages) {
            normalizedPages.add(resolvePage(pdfPath, page));
        }

        List<HealthIndicator> indicators = indicatorParser.parse(normalizedPages);
        return new HealthReportParseResult(
                REPORT_TYPE,
                request.originalFilename(),
                normalizedPages.size(),
                indicators,
                indicators,
                HealthDisclaimers.REPORT_DISCLAIMER,
                REVIEW_PROMPT);
    }

    private ReportPageText resolvePage(Path pdfPath, ReportPageText page) {
        if (page == null) {
            return new ReportPageText(0, "", "PDF_TEXT");
        }
        if (!requiresOcr(page)) {
            return page;
        }
        try {
            byte[] imageBytes = pdfReportExtractor.renderPage(pdfPath, page.pageNo(), OCR_DPI);
            String ocrText = ocrClient.recognize(imageBytes, "jpg");
            return new ReportPageText(page.pageNo(), ocrText == null ? "" : ocrText.trim(), "OCR");
        } catch (Exception e) {
            // 单页 OCR 失败降级为空文本页，避免整份多页报告全部解析失败
            log.warn("第 {} 页 OCR 失败，降级为空文本页（不影响其余页面）: {}", page.pageNo(), e.getMessage());
            return new ReportPageText(page.pageNo(), "", "OCR_FAILED");
        }
    }

    private static boolean requiresOcr(ReportPageText page) {
        String source = page.source();
        return source == null || source.isBlank() || "OCR_REQUIRED".equalsIgnoreCase(source.trim());
    }
}
