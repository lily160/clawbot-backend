package com.github.wechat.cloudbot.modules.health.common;

import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;

import java.util.List;
import java.util.Map;

/**
 * 阶段图编排结果。所有集合均为不可变快照，允许调用方安全保存和恢复。
 */
public record HealthFlowResult(
        HealthFlowSnapshot snapshot,
        PatientRef patientRef,
        TriageResult triageResult,
        List<SlotOption> slots,
        BookingReceipt bookingReceipt,
        HealthReportParseResult reportResult,
        List<String> followUpSuggestions,
        List<String> checkupSuggestions,
        List<String> medicationSummary,
        List<String> reminderSummary,
        List<String> degradedStages,
        Map<String, StageStatus> stageStatuses) {

    public HealthFlowResult {
        slots = slots == null ? List.of() : List.copyOf(slots);
        followUpSuggestions = followUpSuggestions == null ? List.of() : List.copyOf(followUpSuggestions);
        checkupSuggestions = checkupSuggestions == null ? List.of() : List.copyOf(checkupSuggestions);
        medicationSummary = medicationSummary == null ? List.of() : List.copyOf(medicationSummary);
        reminderSummary = reminderSummary == null ? List.of() : List.copyOf(reminderSummary);
        degradedStages = degradedStages == null ? List.of() : List.copyOf(degradedStages);
        stageStatuses = stageStatuses == null ? Map.of() : Map.copyOf(stageStatuses);
    }

    public boolean hasDegradedStages() {
        return !degradedStages.isEmpty();
    }

    public enum StageStatus {
        COMPLETED,
        SKIPPED,
        DEGRADED,
        BLOCKED
    }
}
