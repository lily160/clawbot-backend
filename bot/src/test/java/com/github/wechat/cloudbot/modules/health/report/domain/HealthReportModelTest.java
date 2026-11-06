package com.github.wechat.cloudbot.modules.health.report.domain;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HealthReportModelTest {

    @Test
    void filtersAbnormalIndicatorsAndProtectsCollections() {
        HealthIndicator normal = new HealthIndicator(
                "血红蛋白",
                "138",
                "g/L",
                "120-160",
                IndicatorStatus.NORMAL,
                1,
                "血红蛋白 138 g/L 120-160",
                0.93
        );
        HealthIndicator high = new HealthIndicator(
                "空腹血糖",
                "7.2",
                "mmol/L",
                "3.9-6.1",
                IndicatorStatus.HIGH,
                2,
                "空腹血糖 7.2 mmol/L 3.9-6.1",
                0.88
        );
        HealthIndicator unknown = new HealthIndicator(
                "肌酐",
                "89",
                "umol/L",
                "",
                IndicatorStatus.UNKNOWN,
                3,
                "肌酐 89 umol/L",
                0.62
        );

        HealthReportParseResult result = new HealthReportParseResult(
                "体检报告",
                "report.pdf",
                3,
                List.of(normal, high, unknown),
                List.of(normal, high, unknown),
                "免责声明",
                "请结合原始报告并咨询专业医生复核"
        );

        assertEquals(List.of(high), result.abnormalIndicators());
        assertThrows(UnsupportedOperationException.class, () -> result.indicators().add(high));
        assertThrows(UnsupportedOperationException.class, () -> result.abnormalIndicators().add(high));
    }

    @Test
    void keepsParseRequestInputs() {
        HealthReportParseRequest request = new HealthReportParseRequest(Path.of("sample.pdf"), "sample.pdf");

        assertEquals(Path.of("sample.pdf"), request.pdfPath());
        assertEquals("sample.pdf", request.originalFilename());
    }
}
