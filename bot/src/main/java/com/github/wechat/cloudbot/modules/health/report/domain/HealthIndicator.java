package com.github.wechat.cloudbot.modules.health.report.domain;

public record HealthIndicator(
        String name,
        String value,
        String unit,
        String referenceRange,
        IndicatorStatus status,
        int pageNo,
        String evidenceText,
        Double confidence
) {
}
