package com.github.wechat.cloudbot.platform.agent;

import com.github.wechat.cloudbot.modules.health.booking.service.BookingService;
import com.github.wechat.cloudbot.modules.health.booking.service.HospitalRankResult;
import com.github.wechat.cloudbot.modules.health.common.BookingGuide;
import com.github.wechat.cloudbot.modules.health.common.HealthContractOverview;
import com.github.wechat.cloudbot.modules.health.common.HealthFlowAction;
import com.github.wechat.cloudbot.modules.health.common.HealthFlowOrchestrator;
import com.github.wechat.cloudbot.modules.health.common.HealthFlowSnapshot;
import com.github.wechat.cloudbot.modules.health.common.HealthRedlinePrompts;
import com.github.wechat.cloudbot.modules.health.common.PatientRef;
import com.github.wechat.cloudbot.modules.health.common.SlotOption;
import com.github.wechat.cloudbot.modules.health.common.TriageResult;
import com.github.wechat.cloudbot.modules.health.profile.service.ProfileService;
import com.github.wechat.cloudbot.modules.health.triage.service.TriageService;
import com.github.wechat.cloudbot.modules.health.triage.service.TriageSessionResult;
import com.github.wechat.cloudbot.platform.agent.plan.PlanDraft;
import com.github.wechat.cloudbot.platform.agent.plan.PlanOrchestrator;
import com.github.wechat.cloudbot.platform.agent.plan.PlanRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 健康相关保留指令路由，给组长主流程留一个稳定入口。
 */
@Component
public class HealthCommandRouter {

    private static final Pattern SLOT_SELECTION_PATTERN = Pattern.compile("^(确认挂号|确认预约|预约|挂号|确认第(?<index>\\d+)个|预约第(?<index2>\\d+)个)$");

    private final PlanOrchestrator planOrchestrator;
    private final HealthFlowOrchestrator healthFlowOrchestrator;
    private final ProfileService profileService;
    private final TriageService triageService;
    private final BookingService bookingService;

    public HealthCommandRouter(PlanOrchestrator planOrchestrator,
                               HealthFlowOrchestrator healthFlowOrchestrator,
                               ProfileService profileService,
                               TriageService triageService,
                               BookingService bookingService) {
        this.planOrchestrator = planOrchestrator;
        this.healthFlowOrchestrator = healthFlowOrchestrator;
        this.profileService = profileService;
        this.triageService = triageService;
        this.bookingService = bookingService;
    }

    public Optional<String> route(String userId, String text) {
        if (text == null) {
            return Optional.empty();
        }
        String normalized = text.trim();
        if (normalized.isBlank()) {
            return Optional.empty();
        }

        if (looksLikeSymptomText(normalized)) {
            return Optional.of(handleHealthFlow(userId, normalized));
        }
        if (normalized.contains("组长任务") || normalized.contains("总览") || normalized.contains("health overview")) {
            return Optional.of(HealthContractOverview.overviewText());
        }
        if (normalized.contains("契约") || normalized.contains("共享") || normalized.contains("contract")) {
            return Optional.of(HealthContractOverview.contractText());
        }
        if (normalized.contains("主流程") || normalized.contains("路由") || normalized.contains("flow")) {
            return Optional.of(HealthContractOverview.roadmapText());
        }
        if (normalized.contains("红线") || normalized.contains("医学红线") || normalized.contains("redline")) {
            return Optional.of(HealthContractOverview.redlineText() + "\n\n" + HealthRedlinePrompts.REDLINE_PROMPT);
        }
        if (normalized.contains("wait_user") || normalized.contains("恢复") || normalized.contains("继续") || normalized.contains("plan")) {
            return Optional.of(resumePlan(userId));
        }
        return Optional.empty();
    }

    private boolean looksLikeSymptomText(String text) {
        if (text.startsWith("没有") || text.startsWith("不是") || text.startsWith("不咳")
                || text.startsWith("不痛") || text.startsWith("不烧") || text.startsWith("无")) {
            return false;
        }
        return text.contains("痛") || text.contains("咳") || text.contains("烧") || text.contains("腹") || text.contains("胸") || text.contains("头") || text.contains("吐") || text.contains("泻") || text.contains("酸") || text.contains("症状");
    }

    private String resumePlan(String userId) {
        return PlanRunner.advance(planOrchestrator, userId, this::executePlanTool)
                .map(PlanRunner::formatAdvance)
                .orElseGet(() -> {
                    PlanDraft rebuilt = planOrchestrator.buildGroupLeadDraft(userId);
                    return "暂无可恢复的编排，已重新生成：" + rebuilt.summary() + "\n" + healthFlowOrchestrator.summarize();
                });
    }

    private String executePlanTool(String toolName) {
        if ("triage".equals(toolName)) {
            return "导诊已就绪，可继续补充症状或确认挂号";
        }
        if ("done".equals(toolName)) {
            return "流程结束";
        }
        return "执行：" + toolName;
    }

    private String handleHealthFlow(String userId, String symptomText) {
        List<PatientRef> profiles = profileService.getProfiles(userId);
        PatientRef patientRef = profiles.isEmpty() ? null : profiles.get(0);

        PlanDraft previousDraft = planOrchestrator.resume(userId).orElse(null);
        Optional<String> previousSymptoms = Optional.ofNullable(previousDraft)
                .flatMap(PlanDraft::snapshotText)
                .map(HealthCommandRouter::extractSymptomFromSnapshot)
                .filter(prev -> !prev.isBlank() && !prev.equals(symptomText));
        int previousRound = extractFollowUpRound(previousDraft);
        List<String> previouslyAsked = extractFollowUpQuestions(previousDraft);
        String mergedSymptom = previousSymptoms
                .map(prev -> prev + " " + symptomText)
                .orElse(symptomText);

        TriageSessionResult triageSessionResult = triageService.triage(mergedSymptom, previousRound, previouslyAsked);
        TriageResult triageResult = triageSessionResult.triageResult();
        String resumeNote = previousSymptoms.isPresent()
                ? "已合并上轮补充信息，继续导诊"
                : "导诊追问阶段";
        if (triageSessionResult.needsMoreInfo()) {
            List<String> accumulatedAsked = new ArrayList<>(previouslyAsked);
            for (String q : triageSessionResult.followUpQuestions()) {
                if (!accumulatedAsked.contains(q)) {
                    accumulatedAsked.add(q);
                }
            }
            HealthFlowSnapshot snapshot = HealthFlowSnapshot.of(
                    userId,
                    HealthFlowAction.TRIAGE,
                    mergedSymptom,
                    patientRef,
                    triageResult,
                    List.of(),
                    null,
                    null,
                    null,
                    true,
                    accumulatedAsked,
                    List.of());
            planOrchestrator.buildHealthFlowDraft(userId, snapshot.toString());
            return friendlyFollowUp(triageResult, triageSessionResult.followUpQuestions());
        }

        String department = triageResult == null || triageResult.suggestedDepartments().isEmpty()
                ? "全科" : triageResult.suggestedDepartments().get(0);
        HospitalRankResult hospitalRank = bookingService.rankHospitals(department);
        BookingGuide bookingGuide = buildBookingGuide(department, hospitalRank);
        HealthFlowSnapshot snapshot = HealthFlowSnapshot.of(
                userId,
                HealthFlowAction.BOOKING,
                mergedSymptom,
                patientRef,
                triageResult,
                List.of(),
                bookingGuide,
                null,
                null,
                false,
                List.of(),
                List.of());
        planOrchestrator.buildHealthFlowDraft(userId, snapshot.toString());
        return friendlyBookingReply(triageResult, bookingGuide);
    }

    private String normalizedBookingText(String text) {
        return text == null ? "" : text.trim();
    }

    private Integer parseSlotSelection(String text) {
        Matcher matcher = SLOT_SELECTION_PATTERN.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        String index = matcher.group("index") != null ? matcher.group("index") : matcher.group("index2");
        if (index == null || index.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(index);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean isBookingConfirmation(String text) {
        String normalized = text == null ? "" : text.trim();
        return normalized.contains("确认挂号") || normalized.contains("确认预约")
                || normalized.equals("确认") || normalized.equals("挂号");
    }

    private static int extractFollowUpRound(PlanDraft draft) {
        if (draft == null) {
            return 0;
        }
        return draft.snapshotText()
                .map(text -> {
                    for (String line : text.split("\n")) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("- 追问轮次：")) {
                            try {
                                return Integer.parseInt(trimmed.substring("- 追问轮次：".length()).trim());
                            } catch (NumberFormatException e) {
                                return 0;
                            }
                        }
                    }
                    return 0;
                })
                .orElse(0);
    }

    /**
     * 从快照中提取上一轮已问过的追问问题，用于去重避免反复问同样的问题。
     */
    private static List<String> extractFollowUpQuestions(PlanDraft draft) {
        if (draft == null) {
            return List.of();
        }
        return draft.snapshotText()
                .map(text -> {
                    for (String line : text.split("\n")) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("- 追问：")) {
                            String value = trimmed.substring("- 追问：".length()).trim();
                            if ("—".equals(value) || value.isBlank()) {
                                return List.<String>of();
                            }
                            return List.of(value.split("；"));
                        }
                    }
                    return List.<String>of();
                })
                .orElse(List.of());
    }

    private String friendlyFollowUp(TriageResult triageResult, List<String> questions) {
        StringBuilder sb = new StringBuilder("为了更准确地帮你判断，想再确认几个情况：\n");
        for (int i = 0; i < questions.size(); i++) {
            sb.append(i + 1).append(". ").append(questions.get(i)).append("\n");
        }
        if (triageResult != null && !triageResult.suggestedDepartments().isEmpty()) {
            sb.append("初步看可能挂").append(String.join("或", triageResult.suggestedDepartments())).append("，你先补充下细节我再帮你看看。");
        } else {
            sb.append("你先补充下细节，我再帮你看看。");
        }
        return sb.toString();
    }

    private String friendlyBookingReply(TriageResult triageResult, BookingGuide bookingGuide) {
        StringBuilder sb = new StringBuilder();
        if (triageResult != null && !triageResult.suggestedDepartments().isEmpty()) {
            sb.append("建议挂号\n");
            sb.append("└ ").append(String.join(" / ", triageResult.suggestedDepartments())).append("\n\n");
        }
        if (bookingGuide != null && !bookingGuide.hospitals().isEmpty()) {
            sb.append("医院推荐\n");
            for (BookingGuide.HospitalEntry item : bookingGuide.hospitals()) {
                String entry = item.miniProgramEntry() == null || item.miniProgramEntry().isBlank()
                        ? item.miniProgramName()
                        : item.miniProgramEntry();
                sb.append("\n")
                        .append("【Top").append(item.rank()).append("】").append(item.hospitalName()).append("\n")
                        .append("   推荐理由：").append(item.reason() == null || item.reason().isBlank() ? "暂无" : item.reason()).append("\n")
                        .append("   小程序入口：").append(entry).append("\n");
            }
            sb.append("\n").append(bookingGuide.disclaimer());
        } else {
            sb.append("暂时没有可用入口，你可以稍后再试。\n")
                    .append(bookingService.miniProgramHint());
        }
        sb.append("\n\n仅供导诊参考，不替代医生当面诊断。");
        return sb.toString();
    }

    private BookingGuide buildBookingGuide(String department, HospitalRankResult rankResult) {
        if (rankResult == null || rankResult.items().isEmpty()) {
            return new BookingGuide(department, List.of(), bookingService.miniProgramHint());
        }
        List<BookingGuide.HospitalEntry> entries = rankResult.items().stream()
                .map(item -> new BookingGuide.HospitalEntry(
                        item.rank(),
                        item.hospitalName(),
                        item.reason(),
                        item.miniProgramName(),
                        item.miniProgramPath()))
                .toList();
        return new BookingGuide(department, entries, bookingService.miniProgramHint());
    }

    private static String extractSymptomFromSnapshot(String snapshotText) {
        if (snapshotText == null) {
            return "";
        }
        for (String line : snapshotText.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- 症状：")) {
                String value = trimmed.substring("- 症状：".length()).trim();
                return "—".equals(value) ? "" : value;
            }
        }
        return "";
    }

}
