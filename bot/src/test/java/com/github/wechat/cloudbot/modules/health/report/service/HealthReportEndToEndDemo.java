package com.github.wechat.cloudbot.modules.health.report.service;

import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus;
import com.github.wechat.cloudbot.modules.health.report.domain.ReportPageText;
import com.github.wechat.cloudbot.modules.health.report.infra.PdfReportExtractor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端演示 + 全链路自测：PDF 生成 → 文本提取 → 指标解析 → 偏离判断 →
 * 趋势对比 → 分级复查建议 → 跟进计划 → 人性化响应 → Word 导出。
 *
 * <p>覆盖五种场景：
 * <ol>
 *   <li>多异常报告（首次上传）</li>
 *   <li>全正常报告</li>
 *   <li>有历史数据的趋势对比</li>
 *   <li>分级复查建议 + 跟进计划</li>
 *   <li>人性化响应完整输出（打印供人工审核）</li>
 * </ol>
 *
 * <p>可直接运行：{@code mvn test -Dtest=HealthReportEndToEndDemo}
 */
class HealthReportEndToEndDemo {

    private final HealthIndicatorParser parser = new HealthIndicatorParser();
    private final ReportSafetyFormatter formatter = new ReportSafetyFormatter();
    private final ReportTrendAnalyzer trendAnalyzer = new ReportTrendAnalyzer();
    private final PdfReportExtractor extractor = new PdfReportExtractor();

    // ═══════════════════════════════════════════════════════════════
    // 场景 1：多异常报告（首次上传，无历史数据）
    // ═══════════════════════════════════════════════════════════════

    @Test
    void scenario1_multiAbnormalFirstReport() throws Exception {
        Path pdf = writeSimulatedHealthReportPdf();
        List<ReportPageText> pages = extractor.extractText(pdf);
        List<HealthIndicator> indicators = parser.parse(pages);

        assertTrue(indicators.size() >= 8, "Should extract at least 8 indicators");

        HealthReportParseResult result = new HealthReportParseResult(
                "体检报告", "demo-report.pdf", pages.size(),
                indicators, indicators,
                HealthDisclaimers.REPORT_DISCLAIMER,
                "请结合原始报告并咨询专业医生复核");

        // 异常项验证
        List<HealthIndicator> abnormals = result.abnormalIndicators();
        assertTrue(abnormals.size() >= 4, "Should have at least 4 abnormal indicators");

        // 无历史 → 趋势为空
        List<ReportTrendAnalyzer.TrendItem> trends = trendAnalyzer.analyze(indicators, null);
        assertTrue(trends.isEmpty());

        // 复查建议（基于当前异常项）
        ReportTrendAnalyzer.FollowUpSuggestions followUp =
                trendAnalyzer.suggest(abnormals, trends);
        assertNotNull(followUp);
        assertFalse(followUp.items().isEmpty());
        assertTrue(followUp.items().stream().anyMatch(s -> s.contains("血糖")),
                "Should include glucose-specific advice");

        // 跟进计划
        List<ReportTrendAnalyzer.FollowUpPlan> plans =
                trendAnalyzer.buildFollowUpPlans(abnormals, trends);
        assertEquals(abnormals.size(), plans.size());
        for (ReportTrendAnalyzer.FollowUpPlan plan : plans) {
            assertNotNull(plan.suggestedDepartment());
            assertNotNull(plan.suggestedTimeline());
            assertFalse(plan.actionItems().isEmpty());
        }

        // 人性化格式化（无历史）
        String text = formatter.formatRich(result, trends, followUp, false);
        verifyHumanFriendlyOutput(text, false);

        Files.deleteIfExists(pdf);
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景 2：全正常报告
    // ═══════════════════════════════════════════════════════════════

    @Test
    void scenario2_allNormalReport() throws Exception {
        Path pdf = writeAllNormalReportPdf();
        List<ReportPageText> pages = extractor.extractText(pdf);
        List<HealthIndicator> indicators = parser.parse(pages);

        assertTrue(indicators.size() >= 3);

        HealthReportParseResult result = new HealthReportParseResult(
                "体检报告", "normal-report.pdf", pages.size(),
                indicators, indicators,
                HealthDisclaimers.REPORT_DISCLAIMER,
                "请结合原始报告并咨询专业医生复核");

        // 无异常项
        assertTrue(result.abnormalIndicators().isEmpty());

        // 复查建议为空
        ReportTrendAnalyzer.FollowUpSuggestions followUp =
                trendAnalyzer.suggest(result.abnormalIndicators(), List.of());
        assertTrue(followUp.items().isEmpty());

        // 人性化输出应传递正面情绪
        String text = formatter.formatRich(result, List.of(), followUp, false);
        assertTrue(text.contains("良好"));
        assertTrue(text.contains("点赞"));

        Files.deleteIfExists(pdf);
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景 3：趋势对比（有历史数据）
    // ═══════════════════════════════════════════════════════════════

    @Test
    void scenario3_trendComparisonWithHistory() throws Exception {
        Path pdf = writeSimulatedHealthReportPdf();
        List<ReportPageText> pages = extractor.extractText(pdf);
        List<HealthIndicator> current = parser.parse(pages);

        // 模拟上次报告（部分指标好转，部分新增异常）
        Map<String, HealthIndicator> previous = new HashMap<>();
        previous.put("Hemoglobin", new HealthIndicator("Hemoglobin", "128", "g/L",
                "120-160", IndicatorStatus.NORMAL, 1, "", null));
        previous.put("Glucose", new HealthIndicator("Glucose", "5.8", "mmol/L",
                "3.9-6.1", IndicatorStatus.NORMAL, 1, "", null)); // 上次正常 → 本次 7.2 HIGH = 新增异常
        previous.put("UricAcid", new HealthIndicator("UricAcid", "500", "umol/L",
                "150-440", IndicatorStatus.HIGH, 1, "", null)); // 上次 500 → 本次 480 = 改善但依然偏高
        previous.put("TotalCholesterol", new HealthIndicator("TotalCholesterol", "6.0", "mmol/L",
                "3.1-5.7", IndicatorStatus.HIGH, 1, "", null)); // 上次 HIGH → 本次 5.2 NORMAL = 好转
        previous.put("TotalProtein", new HealthIndicator("TotalProtein", "62", "g/L",
                "60-83", IndicatorStatus.NORMAL, 1, "", null)); // 上次 NORMAL → 本次 58 LOW = 新增异常

        // 趋势分析
        List<ReportTrendAnalyzer.TrendItem> trends = trendAnalyzer.analyze(current, previous);
        assertFalse(trends.isEmpty(), "Should have trend items");

        // 验证趋势方向
        assertTrue(trends.stream().anyMatch(t -> t.name().equals("Glucose") && "新增异常".equals(t.direction())),
                "Glucose should be '新增异常' (NORMAL → HIGH)");
        assertTrue(trends.stream().anyMatch(t -> t.name().equals("TotalCholesterol") && "好转".equals(t.direction())),
                "TotalCholesterol should be '好转' (HIGH → NORMAL)");
        assertTrue(trends.stream().anyMatch(t -> t.name().equals("TotalProtein") && "新增异常".equals(t.direction())),
                "TotalProtein should be '新增异常' (NORMAL → LOW)");

        HealthReportParseResult result = new HealthReportParseResult(
                "体检报告", "demo-report.pdf", pages.size(),
                current, current,
                HealthDisclaimers.REPORT_DISCLAIMER,
                "请结合原始报告并咨询专业医生复核");

        ReportTrendAnalyzer.FollowUpSuggestions followUp =
                trendAnalyzer.suggest(result.abnormalIndicators(), trends);

        // hasNewAbnormal 应为 true（Glucose 和 TotalProtein 都是新增异常）
        assertTrue(followUp.hasNewAbnormal());

        // 人性化输出（有历史）
        String text = formatter.formatRich(result, trends, followUp, true);
        verifyHumanFriendlyOutput(text, true);

        Files.deleteIfExists(pdf);
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景 4：分级复查建议 + 紧急程度
    // ═══════════════════════════════════════════════════════════════

    @Test
    void scenario4_gradedUrgencySuggestions() {
        // 模拟一组涵盖三种严重程度的异常指标
        List<HealthIndicator> abnormals = List.of(
                new HealthIndicator("HBsAg", "阳性", "", "阴性",
                        IndicatorStatus.ABNORMAL, 2, "HBsAg 阳性 阴性", 0.95),
                new HealthIndicator("Creatinine", "250", "umol/L", "44-133",
                        IndicatorStatus.HIGH, 1, "Creatinine 250 umol/L 44-133", 0.90),
                new HealthIndicator("空腹血糖", "7.2", "mmol/L", "3.9-6.1",
                        IndicatorStatus.HIGH, 1, "空腹血糖 7.2 mmol/L 3.9-6.1", 0.88),
                new HealthIndicator("维生素D", "25", "ng/mL", "",
                        IndicatorStatus.UNKNOWN, 3, "维生素D 25 ng/mL", 0.70)
        );

        ReportTrendAnalyzer.FollowUpSuggestions followUp =
                trendAnalyzer.suggest(abnormals, List.of());

        // 抗原阳性 + 肌酐显著高 → 紧急
        assertEquals("urgent", followUp.urgency(),
                "HBsAg positive + high Creatinine should trigger urgent urgency");

        // 应包含分级标记
        String allItems = String.join("\n", followUp.items());
        assertTrue(allItems.contains("紧急"), "Should have urgent section");
        assertTrue(allItems.contains("关注"), "Should have attention section");

        // 跟进计划验证
        List<ReportTrendAnalyzer.FollowUpPlan> plans =
                trendAnalyzer.buildFollowUpPlans(abnormals, List.of());
        assertEquals(3, plans.size(), "3 abnormal indicators (UNKNOWN excluded)");

        // HBsAg → 肝病科/消化内科
        ReportTrendAnalyzer.FollowUpPlan hbsagPlan = plans.stream()
                .filter(p -> p.indicatorName().equals("HBsAg"))
                .findFirst().orElseThrow();
        assertTrue(hbsagPlan.suggestedDepartment().contains("肝病") || hbsagPlan.suggestedDepartment().contains("感染"));
        assertEquals("urgent", hbsagPlan.urgency());
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景 5：完整人性化响应输出（打印供人工审核）
    // ═══════════════════════════════════════════════════════════════

    @Test
    void scenario5_fullHumanFriendlyOutputForManualReview() throws Exception {
        Path pdf = writeSimulatedHealthReportPdf();
        List<ReportPageText> pages = extractor.extractText(pdf);
        List<HealthIndicator> current = parser.parse(pages);

        // 模拟上次报告
        Map<String, HealthIndicator> previous = new HashMap<>();
        previous.put("Glucose", new HealthIndicator("Glucose", "5.8", "mmol/L",
                "3.9-6.1", IndicatorStatus.NORMAL, 1, "", null));
        previous.put("TotalCholesterol", new HealthIndicator("TotalCholesterol", "6.0", "mmol/L",
                "3.1-5.7", IndicatorStatus.HIGH, 1, "", null));
        previous.put("TotalProtein", new HealthIndicator("TotalProtein", "62", "g/L",
                "60-83", IndicatorStatus.NORMAL, 1, "", null));

        HealthReportParseResult result = new HealthReportParseResult(
                "体检报告", "demo-report.pdf", pages.size(),
                current, current,
                HealthDisclaimers.REPORT_DISCLAIMER,
                "请结合原始报告并咨询专业医生复核");

        List<ReportTrendAnalyzer.TrendItem> trends = trendAnalyzer.analyze(current, previous);
        ReportTrendAnalyzer.FollowUpSuggestions followUp =
                trendAnalyzer.suggest(result.abnormalIndicators(), trends);
        List<ReportTrendAnalyzer.FollowUpPlan> plans =
                trendAnalyzer.buildFollowUpPlans(result.abnormalIndicators(), trends);

        // ── 打印完整响应（供人工审核）──
        System.out.println("=".repeat(60));
        System.out.println("  体检报告解析 - 全链路人性化响应演示");
        System.out.println("=".repeat(60));
        System.out.println();

        String richText = formatter.formatRich(result, trends, followUp, true);
        System.out.println(richText);
        System.out.println();

        System.out.println("=".repeat(60));
        System.out.println("  复查建议（分级）");
        System.out.println("=".repeat(60));
        System.out.println("紧急程度: " + followUp.urgency());
        System.out.println("有新增异常: " + followUp.hasNewAbnormal());
        for (String item : followUp.items()) {
            System.out.println("  " + item);
        }
        System.out.println();

        System.out.println("=".repeat(60));
        System.out.println("  跟进计划");
        System.out.println("=".repeat(60));
        for (ReportTrendAnalyzer.FollowUpPlan plan : plans) {
            System.out.println("• " + plan.indicatorName()
                    + " (" + plan.currentValue() + ", 参考 " + plan.targetValue() + ")");
            System.out.println("  科室: " + plan.suggestedDepartment()
                    + " | 时间: " + plan.suggestedTimeline()
                    + " | 紧急度: " + plan.urgency());
            for (String action : plan.actionItems()) {
                System.out.println("    - " + action);
            }
            System.out.println();
        }

        System.out.println("=".repeat(60));
        System.out.println("  趋势对比明细");
        System.out.println("=".repeat(60));
        for (ReportTrendAnalyzer.TrendItem t : trends) {
            System.out.println("  " + t.name() + ": "
                    + t.previousValue() + " → " + t.currentValue()
                    + " (" + t.direction() + ")");
        }

        // ── 验证 ──
        verifyHumanFriendlyOutput(richText, true);

        // 安全检查
        assertFalse(richText.contains("确诊"));
        assertFalse(richText.contains("建议服用"));
        assertFalse(richText.contains("停药"));
        assertFalse(richText.contains("治疗方案"));

        // 趋势验证
        assertTrue(trends.stream().anyMatch(t -> "好转".equals(t.direction())),
                "Should have at least one '好转' trend");
        assertTrue(trends.stream().anyMatch(t -> "新增异常".equals(t.direction())),
                "Should have at least one '新增异常' trend");

        Files.deleteIfExists(pdf);
    }

    // ═══════════════════════════════════════════════════════════════
    // 场景 6：Word 导出验证
    // ═══════════════════════════════════════════════════════════════

    @Test
    void scenario6_wordExportRoundTrip() throws Exception {
        Path pdf = writeSimulatedHealthReportPdf();
        List<ReportPageText> pages = extractor.extractText(pdf);
        List<HealthIndicator> indicators = parser.parse(pages);

        HealthReportParseResult result = new HealthReportParseResult(
                "体检报告", "demo-report.pdf", pages.size(),
                indicators, indicators,
                HealthDisclaimers.REPORT_DISCLAIMER,
                "请结合原始报告并咨询专业医生复核");

        ReportTrendAnalyzer.FollowUpSuggestions followUp =
                trendAnalyzer.suggest(result.abnormalIndicators(), List.of());

        // Word 导出
        ReportWordExporter exporter = new ReportWordExporter();
        byte[] docxBytes = exporter.export(result, List.of(), followUp);

        assertTrue(docxBytes.length > 1000, "Word document should be non-trivial (got "
                + docxBytes.length + " bytes)");

        // 另存副本到临时目录供人工查看
        Path outputDocx = Path.of(System.getProperty("java.io.tmpdir"),
                "health-report-end-to-end-demo-output.docx");
        Files.write(outputDocx, docxBytes);
        System.out.println("Word 文档已导出到: " + outputDocx.toAbsolutePath());

        Files.deleteIfExists(pdf);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    private void verifyHumanFriendlyOutput(String text, boolean hasHistory) {
        // 头部问候
        assertTrue(text.contains("👋"), "Should have greeting emoji");

        // 报告概览
        assertTrue(text.contains("报告概览"), "Should have overview section");
        assertTrue(text.contains("共识别"), "Should show indicator count");

        // 偏离项
        assertTrue(text.contains("重点关注") || text.contains("均在参考范围内"),
                "Should have deviation section");

        // 趋势（有历史时）
        if (hasHistory) {
            assertTrue(text.contains("对比") || text.contains("趋势"),
                    "Should have trend section when history exists");
        }

        // 生活方式
        assertTrue(text.contains("生活"), "Should have lifestyle tips");

        // 下一步
        assertTrue(text.contains("您可以"), "Should have next steps");

        // 免责声明
        assertTrue(text.contains(HealthDisclaimers.REPORT_DISCLAIMER));
    }

    // ═══════════════════════════════════════════════════════════════
    // PDF 生成
    // ═══════════════════════════════════════════════════════════════

    /** 多异常体检报告（2页） */
    private static Path writeSimulatedHealthReportPdf() throws Exception {
        Path file = Files.createTempFile("health-report-demo-", ".pdf");
        try (PDDocument document = new PDDocument()) {

            PDPage page1 = new PDPage(PDRectangle.A4);
            document.addPage(page1);
            try (PDPageContentStream stream = new PDPageContentStream(document, page1)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 10);
                stream.newLineAtOffset(50, 750);
                for (String line : List.of(
                        "Health Report - General Checkup Center",
                        "Name: Zhang San   Sex: M   Age: 35",
                        "==========================================",
                        "Item              Result       Ref Range",
                        "Hemoglobin        138 g/L      120-160",
                        "WBC               6.5 10^9/L   3.5-9.5",
                        "Platelet          220 10^9/L   125-350",
                        "Glucose           7.2 mmol/L   3.9-6.1",
                        "TotalProtein      58 g/L       60-83",
                        "Creatinine        80 umol/L    44-133",
                        "UricAcid          480 umol/L   150-440",
                        "Triglyceride      2.8 mmol/L   0.2-1.7",
                        "LDL               3.80 mmol/L  <3.37",
                        "TotalCholesterol  5.2 mmol/L   3.1-5.7"
                )) {
                    stream.newLineAtOffset(0, -15);
                    stream.showText(line);
                }
                stream.endText();
            }

            PDPage page2 = new PDPage(PDRectangle.A4);
            document.addPage(page2);
            try (PDPageContentStream stream = new PDPageContentStream(document, page2)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 10);
                stream.newLineAtOffset(50, 750);
                for (String line : List.of(
                        "Urinalysis & Immunology",
                        "==========================================",
                        "Item              Result       Ref Range",
                        "UrineProtein      Pos(+)        Neg(-)",
                        "UrineBlood        Neg(-)        Neg(-)",
                        "VitaminD          25 ng/mL",
                        "TSH               0.35 mIU/L   0.35~4.94",
                        "ALT               65 U/L         9-50",
                        "AST               18 U/L        15-40",
                        "HBsAg             Pos(+)        Neg(-)",
                        "AFP               Neg(-)        Neg(-)"
                )) {
                    stream.newLineAtOffset(0, -15);
                    stream.showText(line);
                }
                stream.endText();
            }

            document.save(file.toFile());
        }
        return file;
    }

    /** 全正常报告 */
    private static Path writeAllNormalReportPdf() throws Exception {
        Path file = Files.createTempFile("health-report-normal-", ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 10);
                stream.newLineAtOffset(50, 750);
                for (String line : List.of(
                        "Health Report - Basic Checkup",
                        "==========================================",
                        "Item              Result       Ref Range",
                        "Hemoglobin        138 g/L      120-160",
                        "WBC               6.5 10^9/L   3.5-9.5",
                        "Platelet          220 10^9/L   125-350",
                        "Creatinine        80 umol/L    44-133"
                )) {
                    stream.newLineAtOffset(0, -15);
                    stream.showText(line);
                }
                stream.endText();
            }
            document.save(file.toFile());
        }
        return file;
    }
}
