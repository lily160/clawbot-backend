package com.github.wechat.cloudbot.modules.health.report.service;

import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportSafetyFormatterTest {

    @Test
    void includesDisclaimerReviewPromptAndAbnormalSummary() {
        HealthReportParseResult result = resultWithAbnormalIndicators();

        String text = new ReportSafetyFormatter().format(result);

        assertTrue(text.contains(HealthDisclaimers.REPORT_DISCLAIMER));
        assertTrue(text.contains("请结合原始报告并咨询专业医生复核"));
        assertTrue(text.contains("空腹血糖"));
        assertTrue(text.contains("2页"));
        // 新格式应包含统计摘要和偏离判断
        assertTrue(text.contains("统计摘要"));
        assertTrue(text.contains("偏离判断"));
        assertTrue(text.contains("偏高"));
        assertTrue(text.contains("正常"));
        // 不应包含医疗建议性语言
        assertFalse(text.contains("确诊"));
        assertFalse(text.contains("建议服用"));
        assertFalse(text.contains("停药"));
        assertFalse(text.contains("治疗方案"));
    }

    @Test
    void statesNoAbnormalIndicatorsSafely() {
        HealthIndicator normal = new HealthIndicator(
                "血红蛋白",
                "138",
                "g/L",
                "120-160",
                IndicatorStatus.NORMAL,
                1,
                "血红蛋白 138 g/L 120-160",
                0.93);
        HealthReportParseResult result = new HealthReportParseResult(
                "体检报告",
                "report.pdf",
                1,
                List.of(normal),
                List.of(normal),
                HealthDisclaimers.REPORT_DISCLAIMER,
                "请结合原始报告并咨询专业医生复核");

        String text = new ReportSafetyFormatter().format(result);

        assertTrue(text.contains("本次解析未发现报告明确标注的偏离项"));
        assertTrue(text.contains(HealthDisclaimers.REPORT_DISCLAIMER));
        assertTrue(text.contains("统计摘要"));
        assertTrue(text.contains("共识别 1 项指标"));
        assertTrue(text.contains("未发现明显偏离项"));
    }

    @Test
    void statisticsCountsEachStatusCorrectly() {
        HealthIndicator normal = new HealthIndicator(
                "血红蛋白", "138", "g/L", "120-160", IndicatorStatus.NORMAL,
                1, "血红蛋白 138 g/L 120-160", 0.93);
        HealthIndicator high = new HealthIndicator(
                "空腹血糖", "7.2", "mmol/L", "3.9-6.1", IndicatorStatus.HIGH,
                2, "空腹血糖 7.2 mmol/L 3.9-6.1", 0.88);
        HealthIndicator low = new HealthIndicator(
                "总蛋白", "58", "g/L", "60-83", IndicatorStatus.LOW,
                2, "总蛋白 58 g/L 60-83", 0.85);
        HealthIndicator abnormal = new HealthIndicator(
                "尿蛋白", "阳性", "", "阴性", IndicatorStatus.ABNORMAL,
                3, "尿蛋白 阳性 阴性", 0.91);
        HealthIndicator unknown = new HealthIndicator(
                "维生素D", "25", "ng/mL", "", IndicatorStatus.UNKNOWN,
                3, "维生素D 25 ng/mL", 0.70);

        HealthReportParseResult result = new HealthReportParseResult(
                "体检报告", "report.pdf", 3,
                List.of(normal, high, low, abnormal, unknown),
                List.of(normal, high, low, abnormal, unknown),
                HealthDisclaimers.REPORT_DISCLAIMER,
                "请结合原始报告并咨询专业医生复核");

        String text = new ReportSafetyFormatter().format(result);

        assertTrue(text.contains("共识别 5 项指标"));
        assertTrue(text.contains("正常 1 项"));
        assertTrue(text.contains("偏高 1"));
        assertTrue(text.contains("偏低 1"));
        assertTrue(text.contains("异常 1"));
        assertTrue(text.contains("无参考范围 1 项"));
        assertTrue(text.contains("偏离判断"));
        assertTrue(text.contains("【偏高】空腹血糖"));
        assertTrue(text.contains("【偏低】总蛋白"));
        assertTrue(text.contains("【异常】尿蛋白"));
    }

    private static HealthReportParseResult resultWithAbnormalIndicators() {
        HealthIndicator normal = new HealthIndicator(
                "血红蛋白",
                "138",
                "g/L",
                "120-160",
                IndicatorStatus.NORMAL,
                1,
                "血红蛋白 138 g/L 120-160",
                0.93);
        HealthIndicator high = new HealthIndicator(
                "空腹血糖",
                "7.2",
                "mmol/L",
                "3.9-6.1",
                IndicatorStatus.HIGH,
                2,
                "空腹血糖 7.2 mmol/L 3.9-6.1",
                0.88);
        return new HealthReportParseResult(
                "体检报告",
                "report.pdf",
                2,
                List.of(normal, high),
                List.of(normal, high),
                HealthDisclaimers.REPORT_DISCLAIMER,
                "请结合原始报告并咨询专业医生复核");
    }
}
