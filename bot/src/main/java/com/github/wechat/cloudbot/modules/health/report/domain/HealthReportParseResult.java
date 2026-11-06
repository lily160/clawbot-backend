package com.github.wechat.cloudbot.modules.health.report.domain;

import java.util.List;
import java.util.Objects;

public record HealthReportParseResult(
        String reportType,
        String sourceFilename,
        int pageCount,
        List<HealthIndicator> indicators,
        List<HealthIndicator> abnormalIndicators,
        String disclaimer,
        String reviewPrompt
) {

    public HealthReportParseResult {
        reportType = Objects.requireNonNull(reportType, "reportType");
        sourceFilename = Objects.requireNonNull(sourceFilename, "sourceFilename");
        // 结果对象一律转成不可变集合，避免上层继续改动解析后的内容。
        indicators = List.copyOf(Objects.requireNonNull(indicators, "indicators"));
        // 异常指标单独保留，而且只收纳真正需要复核的条目。
        abnormalIndicators = List.copyOf(Objects.requireNonNull(abnormalIndicators, "abnormalIndicators").stream()
                .filter(HealthReportParseResult::isAbnormal)
                .toList());
        disclaimer = Objects.requireNonNull(disclaimer, "disclaimer");
        reviewPrompt = Objects.requireNonNull(reviewPrompt, "reviewPrompt");
    }

    private static boolean isAbnormal(HealthIndicator indicator) {
        IndicatorStatus status = indicator == null ? null : indicator.status();
        return status == IndicatorStatus.HIGH
                || status == IndicatorStatus.LOW
                || status == IndicatorStatus.ABNORMAL;
    }
}
