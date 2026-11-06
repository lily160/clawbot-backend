package com.github.wechat.cloudbot.modules.health.common;

import com.github.wechat.cloudbot.modules.health.booking.service.BookingService;
import com.github.wechat.cloudbot.modules.health.checkin.service.CheckinService;
import com.github.wechat.cloudbot.modules.health.checkup.service.CheckupService;
import com.github.wechat.cloudbot.modules.health.checkup.service.CheckupService.CheckupPlan;
import com.github.wechat.cloudbot.modules.health.common.skill.HealthSkillCatalog;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService;
import com.github.wechat.cloudbot.modules.health.profile.service.ProfileService;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseRequest;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import com.github.wechat.cloudbot.modules.health.report.service.HealthReportParseService;
import com.github.wechat.cloudbot.modules.health.report.service.HealthReportRepository;
import com.github.wechat.cloudbot.modules.health.report.service.ReportTrendAnalyzer;
import com.github.wechat.cloudbot.modules.health.triage.service.TriageService;
import com.github.wechat.cloudbot.modules.health.triage.service.TriageSessionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 健康主流程编排器：按阶段图执行主干串行 + 侧枝并行，并把中间结果统一收敛到快照。
 */
@Component
public class DefaultHealthFlowOrchestrator implements HealthFlowOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultHealthFlowOrchestrator.class);

    private final ProfileService profileService;
    private final TriageService triageService;
    private final BookingService bookingService;
    private final HealthReportParseService reportParseService;
    private final HealthReportRepository reportRepository;
    private final ReportTrendAnalyzer trendAnalyzer;
    private final CheckupService checkupService;
    private final MedicationService medicationService;
    private final CheckinService checkinService;

    public DefaultHealthFlowOrchestrator(
            ProfileService profileService,
            TriageService triageService,
            BookingService bookingService,
            HealthReportParseService reportParseService,
            HealthReportRepository reportRepository,
            ReportTrendAnalyzer trendAnalyzer,
            CheckupService checkupService,
            MedicationService medicationService,
            CheckinService checkinService) {
        this.profileService = profileService;
        this.triageService = triageService;
        this.bookingService = bookingService;
        this.reportParseService = reportParseService;
        this.reportRepository = reportRepository;
        this.trendAnalyzer = trendAnalyzer;
        this.checkupService = checkupService;
        this.medicationService = medicationService;
        this.checkinService = checkinService;
    }

    @Override
    public Optional<PatientRef> loadPatient(String patientId) {
        if (patientId == null || patientId.isBlank()) {
            return Optional.empty();
        }
        return profileService.getProfiles(patientId).stream().findFirst();
    }

    @Override
    public Optional<TriageResult> triage(String symptomText, PatientRef patientRef) {
        TriageSessionResult sessionResult = triageService.triage(symptomText);
        return Optional.ofNullable(sessionResult == null ? null : sessionResult.triageResult());
    }

    @Override
    public Optional<BookingReceipt> book(String symptomText, PatientRef patientRef, TriageResult triageResult) {
        List<SlotOption> slots = bookingService.recommend(symptomText, patientRef, triageResult);
        if (slots.isEmpty()) {
            return Optional.empty();
        }
        return bookingService.confirm("booking-1", slots.get(0), patientRef);
    }

    @Override
    public HealthFlowResult execute(HealthFlowRequest request) {
        if (request == null) {
            return emptyResult(null, HealthFlowAction.LOAD_PATIENT, List.of("request"));
        }

        Map<String, HealthFlowResult.StageStatus> stageStatuses = new LinkedHashMap<>();
        List<String> degradedStages = new ArrayList<>();

        PatientRef patientRef = null;
        if (request.patientId() != null && !request.patientId().isBlank()) {
            try {
                patientRef = loadPatient(request.patientId()).orElse(null);
                stageStatuses.put("loadPatient", patientRef == null ? HealthFlowResult.StageStatus.BLOCKED : HealthFlowResult.StageStatus.COMPLETED);
                if (patientRef == null) {
                    degradedStages.add("loadPatient");
                }
            } catch (Exception e) {
                stageStatuses.put("loadPatient", HealthFlowResult.StageStatus.DEGRADED);
                degradedStages.add("loadPatient");
            }
        } else {
            stageStatuses.put("loadPatient", HealthFlowResult.StageStatus.SKIPPED);
        }

        TriageResult triageResult = null;
        boolean waitingForUser = false;
        List<String> followUpQuestions = List.of();
        try {
            TriageSessionResult sessionResult = triageService.triage(request.symptomText(), request.followUpRound(), request.previouslyAsked());
            triageResult = sessionResult == null ? null : sessionResult.triageResult();
            waitingForUser = sessionResult != null && sessionResult.needsMoreInfo();
            followUpQuestions = sessionResult == null ? List.of() : sessionResult.followUpQuestions();
            stageStatuses.put("triage", HealthFlowResult.StageStatus.COMPLETED);
        } catch (Exception e) {
            stageStatuses.put("triage", HealthFlowResult.StageStatus.BLOCKED);
            degradedStages.add("triage");
        }

        List<SlotOption> slots = List.of();
        BookingReceipt bookingReceipt = null;
        BookingGuide bookingGuide = null;
        if (!waitingForUser && triageResult != null) {
            try {
                slots = bookingService.recommend(request.symptomText(), patientRef, triageResult);
                bookingGuide = buildBookingGuide(triageResult, slots);
                stageStatuses.put("booking.recommend", slots.isEmpty() ? HealthFlowResult.StageStatus.DEGRADED : HealthFlowResult.StageStatus.COMPLETED);
            } catch (Exception e) {
                slots = List.of();
                bookingGuide = buildBookingGuide(triageResult, List.of());
                stageStatuses.put("booking.recommend", HealthFlowResult.StageStatus.DEGRADED);
                degradedStages.add("booking.recommend");
            }

            if (!slots.isEmpty()) {
                try {
                    bookingReceipt = bookingService.confirm("booking-1", slots.get(0), patientRef, false).orElse(null);
                    stageStatuses.put("booking.confirm", bookingReceipt == null ? HealthFlowResult.StageStatus.DEGRADED : HealthFlowResult.StageStatus.COMPLETED);
                    if (bookingReceipt == null) {
                        degradedStages.add("booking.confirm");
                    }
                } catch (Exception e) {
                    stageStatuses.put("booking.confirm", HealthFlowResult.StageStatus.DEGRADED);
                    degradedStages.add("booking.confirm");
                }
            } else {
                stageStatuses.put("booking.confirm", HealthFlowResult.StageStatus.SKIPPED);
            }
        } else {
            stageStatuses.put("booking.recommend", HealthFlowResult.StageStatus.SKIPPED);
            stageStatuses.put("booking.confirm", HealthFlowResult.StageStatus.SKIPPED);
        }

        HealthReportParseResult reportResult = null;
        if (request.hasReport()) {
            try {
                reportResult = parseReport(Path.of(request.reportPath()), request.reportFilename()).orElse(null);
                stageStatuses.put("report.parse", reportResult == null ? HealthFlowResult.StageStatus.DEGRADED : HealthFlowResult.StageStatus.COMPLETED);
                if (reportResult == null) {
                    degradedStages.add("report.parse");
                }
            } catch (Exception e) {
                stageStatuses.put("report.parse", HealthFlowResult.StageStatus.DEGRADED);
                degradedStages.add("report.parse");
            }
        } else {
            stageStatuses.put("report.parse", HealthFlowResult.StageStatus.SKIPPED);
        }

        List<String> followUpSuggestions = new ArrayList<>();
        if (reportResult != null) {
            try {
                followUpSuggestions.addAll(analyzeReportFollowUp(request.userId(), reportResult));
                stageStatuses.put("report.followUp", HealthFlowResult.StageStatus.COMPLETED);
            } catch (Exception e) {
                stageStatuses.put("report.followUp", HealthFlowResult.StageStatus.DEGRADED);
                degradedStages.add("report.followUp");
            }
        } else {
            stageStatuses.put("report.followUp", HealthFlowResult.StageStatus.SKIPPED);
        }

        List<String> checkupSuggestions = List.of();
        try {
            checkupSuggestions = deriveCheckupSuggestions(patientRef);
            stageStatuses.put("checkup", checkupSuggestions.isEmpty() ? HealthFlowResult.StageStatus.SKIPPED : HealthFlowResult.StageStatus.COMPLETED);
        } catch (Exception e) {
            stageStatuses.put("checkup", HealthFlowResult.StageStatus.DEGRADED);
            degradedStages.add("checkup");
        }

        List<String> medicationSummary = List.of();
        try {
            medicationSummary = deriveMedicationSummary(request.userId());
            stageStatuses.put("medication", medicationSummary.isEmpty() ? HealthFlowResult.StageStatus.SKIPPED : HealthFlowResult.StageStatus.COMPLETED);
        } catch (Exception e) {
            stageStatuses.put("medication", HealthFlowResult.StageStatus.DEGRADED);
            degradedStages.add("medication");
        }

        List<String> reminderSummary = List.of();
        try {
            reminderSummary = deriveReminderSummary(request.userId());
            stageStatuses.put("checkin", reminderSummary.isEmpty() ? HealthFlowResult.StageStatus.SKIPPED : HealthFlowResult.StageStatus.COMPLETED);
        } catch (Exception e) {
            stageStatuses.put("checkin", HealthFlowResult.StageStatus.DEGRADED);
            degradedStages.add("checkin");
        }

        HealthFlowAction currentAction = bookingReceipt != null
                ? HealthFlowAction.BOOKING
                : reportResult != null
                ? HealthFlowAction.REPORT_SUMMARY
                : waitingForUser
                ? HealthFlowAction.TRIAGE
                : HealthFlowAction.LOAD_PATIENT;

        HealthFlowSnapshot snapshot = HealthFlowSnapshot.of(
                request.userId(),
                currentAction,
                request.symptomText(),
                patientRef,
                triageResult,
                slots,
                bookingGuide,
                bookingReceipt,
                reportResult,
                waitingForUser,
                followUpQuestions,
                degradedStages);

        return new HealthFlowResult(
                snapshot,
                patientRef,
                triageResult,
                slots,
                bookingReceipt,
                reportResult,
                followUpSuggestions,
                checkupSuggestions,
                medicationSummary,
                reminderSummary,
                degradedStages,
                stageStatuses);
    }

    @Override
    public Optional<HealthReportParseResult> parseReport(Path pdfPath, String originalFilename) {
        if (pdfPath == null || originalFilename == null || originalFilename.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(reportParseService.parse(new HealthReportParseRequest(pdfPath, originalFilename)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public String summarize() {
        return HealthSkillCatalog.MAIN_FLOW;
    }

    private HealthFlowResult emptyResult(PatientRef patientRef,
                                         HealthFlowAction action,
                                         List<String> degradedStages) {
        HealthFlowSnapshot snapshot = HealthFlowSnapshot.of(null, action, null, patientRef, null, List.of(), null, null, null, false, List.of(), degradedStages == null ? List.of() : degradedStages);
        return new HealthFlowResult(snapshot, patientRef, null, List.of(), null, null, List.of(), List.of(), List.of(), List.of(), degradedStages, Map.of());
    }

    private BookingGuide buildBookingGuide(TriageResult triageResult, List<SlotOption> slots) {
        String department = triageResult == null || triageResult.suggestedDepartments().isEmpty()
                ? "全科"
                : triageResult.suggestedDepartments().get(0);
        List<BookingGuide.HospitalEntry> entries = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            SlotOption slot = slots.get(i);
            entries.add(new BookingGuide.HospitalEntry(
                    i + 1,
                    slot.hospitalName(),
                    slot.doctorName() == null || slot.doctorName().isBlank() ? "可预约号源" : slot.doctorName(),
                    slot.hospitalName(),
                    slot.bookingLink()));
        }
        return new BookingGuide(department, entries, bookingService.miniProgramHint());
    }

    private List<String> analyzeReportFollowUp(String userId, HealthReportParseResult reportResult) {
        if (reportResult == null) {
            return List.of();
        }
        List<String> suggestions = new ArrayList<>();
        if (!reportResult.abnormalIndicators().isEmpty()) {
            suggestions.add(reportResult.reviewPrompt());
            suggestions.add("报告异常项建议结合门诊复核，不要自行调整用药。");
        }
        try {
            if (userId != null && !userId.isBlank()) {
                List<ReportTrendAnalyzer.TrendItem> trends = trendAnalyzer.analyze(reportResult.indicators(), reportRepository.getLastReportIndicators(userId));
                if (!trends.isEmpty()) {
                    trends.stream()
                            .filter(item -> "加重".equals(item.direction()) || "新增异常".equals(item.direction()))
                            .map(item -> item.name() + "：" + item.direction())
                            .forEach(suggestions::add);
                }
            }
        } catch (Exception e) {
            log.warn("报告随访建议生成失败: {}", e.getMessage());
        }
        return suggestions.stream().distinct().toList();
    }

    private List<String> deriveCheckupSuggestions(PatientRef patientRef) {
        if (checkupService == null || patientRef == null) {
            return List.of();
        }
        CheckupPlan plan = checkupService.generatePlan(
                patientRef.age() == null ? 0 : patientRef.age(),
                patientRef.gender(),
                patientRef.chronicConditionSummary(),
                "none");
        return plan.items().stream().limit(5).toList();
    }

    @SuppressWarnings("unused")
    private String buildFallbackMedicationSummary(String userId) {
        return medicationService == null || userId == null || userId.isBlank()
                ? ""
                : String.join("；", medicationService.list(userId).stream().limit(5)
                .map(record -> record.drugName() + "（" + record.frequency() + "）")
                .toList());
    }

    private List<String> deriveMedicationSummary(String userId) {
        if (medicationService == null || userId == null || userId.isBlank()) {
            return List.of();
        }
        return medicationService.list(userId).stream()
                .limit(5)
                .map(record -> record.drugName() + "（" + record.frequency() + "）")
                .toList();
    }

    private List<String> deriveReminderSummary(String userId) {
        if (checkinService == null || userId == null || userId.isBlank()) {
            return List.of();
        }
        try {
            return checkinService.getRecent(userId, 7).stream()
                    .map(item -> item.kind() + " × " + item.count())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }
}
