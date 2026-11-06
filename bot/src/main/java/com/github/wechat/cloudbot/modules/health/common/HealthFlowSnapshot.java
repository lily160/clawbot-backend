package com.github.wechat.cloudbot.modules.health.common;

import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;

import java.util.List;

/**
 * 健康链路执行快照，供编排层记录中间状态和恢复上下文。
 * 仅保存跨模块不可变 DTO，不保存数据库实体、Mapper 或工具调用原文。
 */
public record HealthFlowSnapshot(
        String userId,
        HealthFlowAction currentAction,
        String symptomText,
        PatientRef patientRef,
        TriageResult triageResult,
        List<SlotOption> slots,
        BookingGuide bookingGuide,
        BookingReceipt bookingReceipt,
        HealthReportParseResult reportResult,
        boolean waitingForUser,
        List<String> followUpQuestions,
        List<String> degradedStages) {

    public HealthFlowSnapshot {
        slots = slots == null ? List.of() : List.copyOf(slots);
        followUpQuestions = followUpQuestions == null ? List.of() : List.copyOf(followUpQuestions);
        degradedStages = degradedStages == null ? List.of() : List.copyOf(degradedStages);
    }

    /** 兼容既有调用方的快照工厂。 */
    public static HealthFlowSnapshot of(String userId,
                                        HealthFlowAction currentAction,
                                        String symptomText,
                                        TriageResult triageResult,
                                        List<SlotOption> slots,
                                        BookingGuide bookingGuide,
                                        boolean waitingForUser,
                                        List<String> followUpQuestions) {
        return new HealthFlowSnapshot(userId, currentAction, symptomText, null, triageResult,
                slots, bookingGuide, null, null, waitingForUser, followUpQuestions, List.of());
    }

    public static HealthFlowSnapshot of(String userId,
                                        HealthFlowAction currentAction,
                                        String symptomText,
                                        PatientRef patientRef,
                                        TriageResult triageResult,
                                        List<SlotOption> slots,
                                        BookingGuide bookingGuide,
                                        BookingReceipt bookingReceipt,
                                        HealthReportParseResult reportResult,
                                        boolean waitingForUser,
                                        List<String> followUpQuestions,
                                        List<String> degradedStages) {
        return new HealthFlowSnapshot(userId, currentAction, symptomText, patientRef, triageResult,
                slots, bookingGuide, bookingReceipt, reportResult, waitingForUser,
                followUpQuestions, degradedStages);
    }

    public boolean resumable() {
        return waitingForUser || !followUpQuestions.isEmpty() || !slots.isEmpty()
                || bookingGuide != null || bookingReceipt != null || reportResult != null;
    }

    @Override
    public String toString() {
        return "健康链路快照\n"
                + "- 用户：" + blankToDash(userId) + "\n"
                + "- 阶段：" + (currentAction == null ? "—" : currentAction.name()) + "\n"
                + "- 症状：" + blankToDash(symptomText) + "\n"
                + "- 档案：" + (patientRef == null ? "—" : blankToDash(patientRef.displayName())) + "\n"
                + "- 等待用户：" + waitingForUser + "\n"
                + "- 追问：" + (followUpQuestions.isEmpty() ? "—" : String.join("；", followUpQuestions)) + "\n"
                + "- 导诊科室：" + (triageResult == null || triageResult.suggestedDepartments().isEmpty()
                        ? "—" : String.join("、", triageResult.suggestedDepartments())) + "\n"
                + "- 推荐结果：" + (bookingGuide == null || bookingGuide.hospitals().isEmpty()
                        ? "—" : bookingGuide.hospitals().size() + " 家医院") + "\n"
                + "- 候选号源：" + slots.size() + " 个\n"
                + "- 降级阶段：" + (degradedStages.isEmpty() ? "—" : String.join("、", degradedStages));
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
