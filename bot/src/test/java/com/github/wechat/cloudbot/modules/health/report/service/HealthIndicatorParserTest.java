package com.github.wechat.cloudbot.modules.health.report.service;

import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus;
import com.github.wechat.cloudbot.modules.health.report.domain.ReportPageText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthIndicatorParserTest {

    @Test
    void parsesNumericRangesAndPositiveNegativeMarkers() {
        List<ReportPageText> pages = List.of(new ReportPageText(2, """
                血红蛋白 115 g/L 120-160
                空 腹 血 糖 7.2 mmol/L 3.9-6.1
                收缩压 150 90-140
                尿蛋白 阳性 阴性
                尿潜血 阴性 阴性
                维生素D 25 ng/mL
                """, "PDF_TEXT"));

        List<HealthIndicator> indicators = new HealthIndicatorParser().parse(pages);

        assertIndicator(indicators, "血红蛋白", "115", "g/L", "120-160", IndicatorStatus.LOW, 2);
        assertIndicator(indicators, "空腹血糖", "7.2", "mmol/L", "3.9-6.1", IndicatorStatus.HIGH, 2);
        assertIndicator(indicators, "收缩压", "150", "", "90-140", IndicatorStatus.HIGH, 2);
        assertIndicator(indicators, "尿蛋白", "阳性", "", "阴性", IndicatorStatus.ABNORMAL, 2);
        assertIndicator(indicators, "尿潜血", "阴性", "", "阴性", IndicatorStatus.NORMAL, 2);
        assertIndicator(indicators, "维生素D", "25", "ng/mL", "", IndicatorStatus.UNKNOWN, 2);
    }

    @Test
    void parsesArrowMarkersAndTruncatesEvidence() {
        String longEvidence = "甘油三酯 2.4 mmol/L ↑ 0.2-1.7 " + "x".repeat(260);
        List<ReportPageText> pages = List.of(new ReportPageText(5, longEvidence, "OCR"));

        List<HealthIndicator> indicators = new HealthIndicatorParser().parse(pages);

        HealthIndicator indicator = find(indicators, "甘油三酯");
        assertEquals(IndicatorStatus.HIGH, indicator.status());
        assertEquals("0.2-1.7", indicator.referenceRange());
        assertEquals(5, indicator.pageNo());
        assertTrue(indicator.evidenceText().length() <= 200);
    }

    // ── 偏离判断专项测试 ──

    @Test
    void deviationBoundaryValuesAreNormal() {
        // 边界值：恰好等于参考范围上下限，应判为正常
        List<ReportPageText> pages = List.of(new ReportPageText(1, """
                血红蛋白 120 g/L 120-160
                血红蛋白 160 g/L 120-160
                肌酐 44 umol/L 44-133
                肌酐 133 umol/L 44-133
                """, "PDF_TEXT"));

        List<HealthIndicator> indicators = new HealthIndicatorParser().parse(pages);

        // 边界值 = NORMAL（按值和参考范围查找）
        HealthIndicator hb120 = findByValue(indicators, "血红蛋白", "120");
        HealthIndicator hb160 = findByValue(indicators, "血红蛋白", "160");
        HealthIndicator cr44 = findByValue(indicators, "肌酐", "44");
        HealthIndicator cr133 = findByValue(indicators, "肌酐", "133");

        assertEquals(IndicatorStatus.NORMAL, hb120.status());
        assertEquals(IndicatorStatus.NORMAL, hb160.status());
        assertEquals(IndicatorStatus.NORMAL, cr44.status());
        assertEquals(IndicatorStatus.NORMAL, cr133.status());
    }

    @Test
    void deviationArrowMarkersOverrideNumericComparison() {
        // 箭头标记优先于数值比较
        // 用不同指标名来区分两个测试用例
        List<ReportPageText> pages = List.of(new ReportPageText(1, """
                ALT 45 U/L ↑ 9-50
                AST 12 U/L ↓ 15-40
                """, "PDF_TEXT"));

        List<HealthIndicator> indicators = new HealthIndicatorParser().parse(pages);

        // ALT 45 在 9-50 范围内本应正常，但 ↑ 标记覆盖为 HIGH
        HealthIndicator alt = find(indicators, "ALT");
        assertEquals(IndicatorStatus.HIGH, alt.status());
        assertTrue(alt.evidenceText().contains("↑"));

        // AST 12 在 15-40 下界外，但 ↓ 标记覆盖为 LOW（与数值判断一致）
        HealthIndicator ast = find(indicators, "AST");
        assertEquals(IndicatorStatus.LOW, ast.status());
        assertTrue(ast.evidenceText().contains("↓"));
    }

    @Test
    void deviationWithoutReferenceRangeIsUnknown() {
        // 没有参考范围且没有箭头标记 → UNKNOWN
        List<ReportPageText> pages = List.of(new ReportPageText(1, """
                维生素D 25 ng/mL
                总胆固醇 5.2
                """, "PDF_TEXT"));

        List<HealthIndicator> indicators = new HealthIndicatorParser().parse(pages);

        assertEquals(IndicatorStatus.UNKNOWN, find(indicators, "维生素D").status());
        assertEquals(IndicatorStatus.UNKNOWN, find(indicators, "总胆固醇").status());
    }

    @Test
    void deviationDetectsHighAndLowAcrossVariousFormats() {
        List<ReportPageText> pages = List.of(new ReportPageText(1, """
                空腹血糖 7.2 mmol/L 3.9-6.1
                总蛋白 58 g/L 60-83
                收缩压 150 90-140
                舒张压 55 60-90
                尿酸 480 umol/L 150-440↑
                甘油三酯 2.8 mmol/L 参考范围：0.2-1.7
                低密度脂蛋白 3.8 参考值：2.07-3.37
                白细胞计数 3.5 ×10^9/L 3.5-9.5
                """, "PDF_TEXT"));

        List<HealthIndicator> indicators = new HealthIndicatorParser().parse(pages);

        assertIndicator(indicators, "空腹血糖", "7.2", "mmol/L", "3.9-6.1", IndicatorStatus.HIGH, 1);
        assertIndicator(indicators, "总蛋白", "58", "g/L", "60-83", IndicatorStatus.LOW, 1);
        assertIndicator(indicators, "收缩压", "150", "", "90-140", IndicatorStatus.HIGH, 1);
        assertIndicator(indicators, "舒张压", "55", "", "60-90", IndicatorStatus.LOW, 1);
        assertIndicator(indicators, "尿酸", "480", "umol/L", "150-440", IndicatorStatus.HIGH, 1);
        assertIndicator(indicators, "甘油三酯", "2.8", "mmol/L", "0.2-1.7", IndicatorStatus.HIGH, 1);
        assertIndicator(indicators, "低密度脂蛋白", "3.8", "", "2.07-3.37", IndicatorStatus.HIGH, 1);
        // 白细胞计数 3.5 在参考范围 3.5-9.5 下界 → NORMAL
        HealthIndicator wbc = find(indicators, "白细胞计数");
        assertEquals("3.5", wbc.value());
        assertEquals(IndicatorStatus.NORMAL, wbc.status());
        assertEquals("3.5-9.5", wbc.referenceRange());
    }

    @Test
    void deviationHandlesNegativeValues() {
        List<ReportPageText> pages = List.of(new ReportPageText(1, """
                碱剩余 -5.0 mmol/L -3.0-3.0
                碱剩余 2.0 mmol/L -3.0-3.0
                """, "PDF_TEXT"));

        List<HealthIndicator> indicators = new HealthIndicatorParser().parse(pages);

        // -5.0 低于下界 -3.0 → LOW
        HealthIndicator low = findByValue(indicators, "碱剩余", "-5.0");
        // 2.0 在 -3.0~3.0 之间 → NORMAL
        HealthIndicator normal = findByValue(indicators, "碱剩余", "2.0");
        assertEquals(IndicatorStatus.LOW, low.status());
        assertEquals(IndicatorStatus.NORMAL, normal.status());
    }

    @Test
    void deviationHandlesRangeSeparators() {
        // 测试各种参考范围分隔符：- ～ ~ – — 至
        List<ReportPageText> pages = List.of(new ReportPageText(1, """
                TSH 0.35 mIU/L 0.35～4.94
                FT3 2.63 pmol/L 2.63~5.70
                FT4 9.01 pmol/L 9.01–19.05
                总T3 1.60 nmol/L 1.60—3.10
                总T4 66.0 nmol/L 66至181
                """, "PDF_TEXT"));

        List<HealthIndicator> indicators = new HealthIndicatorParser().parse(pages);

        // 这些值都在参考范围下限，应判定为 NORMAL 或 LOW
        // TSH 0.35 在 0.35～4.94 下界 → NORMAL
        assertEquals(IndicatorStatus.NORMAL, find(indicators, "TSH").status());
        assertEquals(IndicatorStatus.NORMAL, find(indicators, "FT3").status());
        assertEquals(IndicatorStatus.NORMAL, find(indicators, "FT4").status());
    }

    @Test
    void deviationHandlesSingleSidedReferenceRange() {
        // 单侧参考范围：<3.37 表示值应低于 3.37
        List<ReportPageText> pages = List.of(new ReportPageText(1, """
                低密度脂蛋白 3.80 mmol/L <3.37
                低密度脂蛋白 3.00 mmol/L <3.37
                肌酐清除率 55 mL/min >60
                肌酐清除率 72 mL/min >60
                """, "PDF_TEXT"));

        List<HealthIndicator> indicators = new HealthIndicatorParser().parse(pages);

        // 3.80 >= 3.37 → HIGH
        HealthIndicator high = findByValue(indicators, "低密度脂蛋白", "3.80");
        assertEquals(IndicatorStatus.HIGH, high.status());

        // 3.00 < 3.37 → NORMAL
        HealthIndicator normal = findByValue(indicators, "低密度脂蛋白", "3.00");
        assertEquals(IndicatorStatus.NORMAL, normal.status());

        // 55 <= 60 → LOW
        HealthIndicator low = findByValue(indicators, "肌酐清除率", "55");
        assertEquals(IndicatorStatus.LOW, low.status());

        // 72 > 60 → NORMAL
        HealthIndicator normal2 = findByValue(indicators, "肌酐清除率", "72");
        assertEquals(IndicatorStatus.NORMAL, normal2.status());
    }

    @Test
    void deviationAllNormalReportHasNoAbnormalFlags() {
        List<ReportPageText> pages = List.of(new ReportPageText(1, """
                血红蛋白 138 g/L 120-160
                白细胞计数 6.5 ×10^9/L 3.5-9.5
                血小板 220 ×10^9/L 125-350
                空腹血糖 5.2 mmol/L 3.9-6.1
                总蛋白 72 g/L 60-83
                肌酐 80 umol/L 44-133
                尿蛋白 阴性 阴性
                尿潜血 阴性 阴性
                """, "PDF_TEXT"));

        List<HealthIndicator> indicators = new HealthIndicatorParser().parse(pages);

        long abnormalCount = indicators.stream()
                .filter(i -> i.status() == IndicatorStatus.HIGH
                        || i.status() == IndicatorStatus.LOW
                        || i.status() == IndicatorStatus.ABNORMAL)
                .count();
        assertEquals(0, abnormalCount, "All indicators should be normal");
        assertTrue(indicators.size() >= 8);
    }

    @Test
    void parsesChineseIndicatorNamesAndStatusMarkers() {
        List<ReportPageText> pages = List.of(new ReportPageText(1, """
                谷丙转氨酶 65 U/L ↑ 9-50
                谷草转氨酶 18 U/L 15-40
                乙肝表面抗原 阳性 阴性
                甲胎蛋白 阴性 阴性
                血红蛋白电泳 正常
                """, "PDF_TEXT"));

        List<HealthIndicator> indicators = new HealthIndicatorParser().parse(pages);

        assertIndicator(indicators, "谷丙转氨酶", "65", "U/L", "9-50", IndicatorStatus.HIGH, 1);
        assertIndicator(indicators, "谷草转氨酶", "18", "U/L", "15-40", IndicatorStatus.NORMAL, 1);
        assertIndicator(indicators, "乙肝表面抗原", "阳性", "", "阴性", IndicatorStatus.ABNORMAL, 1);
        assertIndicator(indicators, "甲胎蛋白", "阴性", "", "阴性", IndicatorStatus.NORMAL, 1);
    }

    private static void assertIndicator(
            List<HealthIndicator> indicators,
            String name,
            String value,
            String unit,
            String referenceRange,
            IndicatorStatus status,
            int pageNo) {
        HealthIndicator indicator = find(indicators, name);
        assertEquals(value, indicator.value());
        assertEquals(unit, indicator.unit());
        assertEquals(referenceRange, indicator.referenceRange());
        assertEquals(status, indicator.status());
        assertEquals(pageNo, indicator.pageNo());
    }

    private static HealthIndicator find(List<HealthIndicator> indicators, String name) {
        return indicators.stream()
                .filter(indicator -> indicator.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing indicator: " + name));
    }

    private static HealthIndicator findByValue(List<HealthIndicator> indicators, String name, String value) {
        return indicators.stream()
                .filter(indicator -> indicator.name().equals(name) && indicator.value().equals(value))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing indicator: " + name + "=" + value));
    }
}
