package com.github.wechat.cloudbot.platform.agent;

import com.github.wechat.cloudbot.modules.health.common.BookingReceipt;
import com.github.wechat.cloudbot.modules.health.common.HealthFlowAction;
import com.github.wechat.cloudbot.modules.health.common.HealthFlowSnapshot;
import com.github.wechat.cloudbot.modules.health.common.SlotOption;
import com.github.wechat.cloudbot.modules.health.common.TriageResult;

import java.util.List;
import java.util.Optional;

/**
 * 健康链路的统一卡片输出，供路由层返回给 Agent，也便于后续观测和恢复。
 */
public record HealthFlowCard(
        HealthFlowAction action,
        String summary,
        List<String> suggestedDepartments,
        List<String> commonExams,
        List<SlotOption> slots,
        BookingReceipt receipt,
        boolean waitingForUser,
        HealthFlowSnapshot snapshot) {

    public HealthFlowCard {
        suggestedDepartments = suggestedDepartments == null ? List.of() : List.copyOf(suggestedDepartments);
        commonExams = commonExams == null ? List.of() : List.copyOf(commonExams);
        slots = slots == null ? List.of() : List.copyOf(slots);
    }

    public static HealthFlowCard of(TriageResult triageResult,
                                    List<SlotOption> slots,
                                    BookingReceipt receipt,
                                    boolean waitingForUser,
                                    HealthFlowSnapshot snapshot) {
        List<String> departments = triageResult == null ? List.of() : triageResult.suggestedDepartments();
        List<String> exams = triageResult == null ? List.of() : triageResult.commonExams();
        HealthFlowAction action = snapshot == null ? HealthFlowAction.TRIAGE : snapshot.currentAction();
        String summary = buildSummary(action, departments, exams, slots, receipt, waitingForUser, snapshot);
        return new HealthFlowCard(action, summary, departments, exams, slots, receipt, waitingForUser, snapshot);
    }

    public Optional<String> resumeHint() {
        if (snapshot == null) {
            return Optional.empty();
        }
        if (waitingForUser) {
            return Optional.of("请补充症状细节后继续导诊，或确认是否按当前建议执行挂号。");
        }
        if (receipt != null) {
            return Optional.of("如需继续，可沿当前挂号结果推进；如有新症状，可重新发起导诊。");
        }
        if (!slots.isEmpty()) {
            return Optional.of("当前已有候选挂号信息，可确认一个候选项继续推进。");
        }
        return Optional.of("可继续补充信息以推进当前健康链路。");
    }

    private static String buildSummary(HealthFlowAction action,
                                       List<String> departments,
                                       List<String> exams,
                                       List<SlotOption> slots,
                                       BookingReceipt receipt,
                                       boolean waitingForUser,
                                       HealthFlowSnapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("健康链路卡片 / 阶段：").append(action == null ? "—" : action.name()).append("\n");
        if (snapshot != null) {
            sb.append("用户：").append(snapshot.userId() == null ? "—" : snapshot.userId()).append("\n");
            if (!snapshot.followUpQuestions().isEmpty()) {
                sb.append("追问：").append(String.join("；", snapshot.followUpQuestions())).append("\n");
            }
        }
        if (!departments.isEmpty()) {
            sb.append("导诊科室：").append(String.join("、", departments)).append("\n");
        }
        if (!exams.isEmpty()) {
            sb.append("常见检查：").append(String.join("、", exams)).append("\n");
        }
        if (slots.isEmpty()) {
            sb.append("挂号候选：暂未获取到在线号源，当前环境未接入在线预约\n");
        } else {
            sb.append("挂号候选：").append(slots.size()).append(" 个\n");
        }
        if (receipt != null) {
            sb.append("预约结果：")
                    .append(receipt.hospitalName())
                    .append(" / ")
                    .append(receipt.department())
                    .append(" / ")
                    .append(receipt.visitTimeText())
                    .append(" / ")
                    .append(receipt.orderNo())
                    .append("\n");
        }
        sb.append("等待用户：").append(waitingForUser);
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(summary);
        resumeHint().ifPresent(hint -> sb.append("\n提示：").append(hint));
        if (snapshot != null) {
            sb.append("\n\n").append(snapshot);
        }
        return sb.toString();
    }
}
