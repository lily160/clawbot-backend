package com.github.wechat.cloudbot.modules.health.common;

import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 健康主流程编排器契约。兼容保留旧的单阶段入口，同时提供阶段图统一入口。
 */
public interface HealthFlowOrchestrator {

    Optional<PatientRef> loadPatient(String patientId);

    Optional<TriageResult> triage(String symptomText, PatientRef patientRef);

    Optional<BookingReceipt> book(String symptomText, PatientRef patientRef, TriageResult triageResult);

    default HealthFlowResult execute(HealthFlowRequest request) {
        throw new UnsupportedOperationException("当前编排器未实现阶段图执行");
    }

    default Optional<HealthReportParseResult> parseReport(Path pdfPath, String originalFilename) {
        return Optional.empty();
    }

    String summarize();
}
