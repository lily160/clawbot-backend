package com.github.wechat.cloudbot.modules.health.report.service;

import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseRequest;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus;
import com.github.wechat.cloudbot.modules.health.report.domain.ReportPageText;
import com.github.wechat.cloudbot.modules.health.report.infra.AliyunOcrClient;
import com.github.wechat.cloudbot.modules.health.report.infra.AliyunOcrProperties;
import com.github.wechat.cloudbot.modules.health.report.infra.PdfReportExtractor;
import com.github.wechat.cloudbot.modules.health.report.infra.OcrException;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthReportParseServiceTest {

    @Test
    void usesOcrOnlyForRequiredPagesAndBuildsStructuredResult() {
        FakePdfReportExtractor extractor = new FakePdfReportExtractor(List.of(
                new ReportPageText(1, "血红蛋白 138 g/L 120-160", "PDF_TEXT"),
                new ReportPageText(2, "", "OCR_REQUIRED"),
                new ReportPageText(3, "", "PDF_TEXT")));
        FakeOcrClient ocrClient = new FakeOcrClient("空腹血糖 7.2 mmol/L 3.9-6.1");

        HealthReportParseService service =
                new HealthReportParseService(extractor, ocrClient, new HealthIndicatorParser());

        HealthReportParseResult result =
                service.parse(new HealthReportParseRequest(Path.of("sample-report.pdf"), "sample-report.pdf"));

        assertEquals(1, ocrClient.callCount.get());
        assertEquals(List.of(2), extractor.renderedPages);
        assertEquals("体检报告", result.reportType());
        assertEquals("sample-report.pdf", result.sourceFilename());
        assertEquals(3, result.pageCount());
        assertEquals(2, result.indicators().size());
        assertEquals(1, result.abnormalIndicators().size());
        assertEquals("空腹血糖", result.abnormalIndicators().get(0).name());
        assertTrue(result.disclaimer().contains(HealthDisclaimers.REPORT_DISCLAIMER));
        assertTrue(result.reviewPrompt().contains("请结合原始报告并咨询专业医生复核"));
    }

    @Test
    void reportsOcrFailureWithPageContext_degradesToEmptyPage() {
        FakePdfReportExtractor extractor = new FakePdfReportExtractor(List.of(
                new ReportPageText(1, "血红蛋白 138 g/L 120-160", "PDF_TEXT"),
                new ReportPageText(2, "", "OCR_REQUIRED")));
        FakeOcrClient ocrClient = new FakeOcrClient(new OcrException("INVALID_RESULT", "req-1", "bad ocr"));

        HealthReportParseService service =
                new HealthReportParseService(extractor, ocrClient, new HealthIndicatorParser());

        // 单页 OCR 失败应降级为空文本页，不影响其余页面解析（整份报告不应因此全部失败）
        HealthReportParseResult result = service.parse(
                new HealthReportParseRequest(Path.of("sample-report.pdf"), "sample-report.pdf"));

        assertEquals(2, result.pageCount(), "两份页面都应保留");
        assertEquals(1, ocrClient.callCount.get());
        assertEquals(List.of(2), extractor.renderedPages);
    }

    private static final class FakePdfReportExtractor extends PdfReportExtractor {
        private final List<ReportPageText> pages;
        private final List<Integer> renderedPages = new ArrayList<>();

        private FakePdfReportExtractor(List<ReportPageText> pages) {
            this.pages = pages;
        }

        @Override
        public List<ReportPageText> extractText(Path pdfPath) {
            return pages;
        }

        @Override
        public byte[] renderPage(Path pdfPath, int pageNo, int dpi) {
            renderedPages.add(pageNo);
            return ("page-" + pageNo).getBytes();
        }
    }

    private static final class FakeOcrClient extends AliyunOcrClient {
        private final String result;
        private final RuntimeException failure;
        private final AtomicInteger callCount = new AtomicInteger();

        private FakeOcrClient(String result) {
            super(new AliyunOcrProperties(), java.net.http.HttpClient.newHttpClient());
            this.result = result;
            this.failure = null;
        }

        private FakeOcrClient(RuntimeException failure) {
            super(new AliyunOcrProperties(), java.net.http.HttpClient.newHttpClient());
            this.result = null;
            this.failure = failure;
        }

        @Override
        public String recognize(byte[] imageBytes, String imageFormat) {
            callCount.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
