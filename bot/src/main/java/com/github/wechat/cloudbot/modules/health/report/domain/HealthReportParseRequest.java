package com.github.wechat.cloudbot.modules.health.report.domain;

import java.nio.file.Path;
import java.util.Objects;

public record HealthReportParseRequest(Path pdfPath, String originalFilename) {

    public HealthReportParseRequest {
        pdfPath = Objects.requireNonNull(pdfPath, "pdfPath");
        originalFilename = Objects.requireNonNull(originalFilename, "originalFilename");
    }
}
