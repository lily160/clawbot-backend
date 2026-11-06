package com.github.wechat.cloudbot.modules.health.common;

import com.github.wechat.cloudbot.modules.health.booking.service.BookingService;
import com.github.wechat.cloudbot.modules.health.checkin.service.CheckinService;
import com.github.wechat.cloudbot.modules.health.checkup.service.CheckupService;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService;
import com.github.wechat.cloudbot.modules.health.profile.service.ProfileService;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import com.github.wechat.cloudbot.modules.health.report.service.HealthReportParseService;
import com.github.wechat.cloudbot.modules.health.report.service.HealthReportRepository;
import com.github.wechat.cloudbot.modules.health.report.service.ReportTrendAnalyzer;
import com.github.wechat.cloudbot.modules.health.triage.service.TriageService;
import com.github.wechat.cloudbot.modules.health.triage.service.TriageSessionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultHealthFlowOrchestratorTest {

    @Mock
    private ProfileService profileService;
    @Mock
    private TriageService triageService;
    @Mock
    private BookingService bookingService;
    @Mock
    private HealthReportParseService reportParseService;
    @Mock
    private HealthReportRepository reportRepository;
    @Mock
    private ReportTrendAnalyzer trendAnalyzer;
    @Mock
    private CheckupService checkupService;
    @Mock
    private MedicationService medicationService;
    @Mock
    private CheckinService checkinService;

    @Test
    @DisplayName("E2E：建档-导诊-挂号-并行侧枝-降级应全部串起来")
    void e2e_patientJourney_shouldSurviveParallelDegradation() {
        DefaultHealthFlowOrchestrator orchestrator = new DefaultHealthFlowOrchestrator(
                profileService, triageService, bookingService, reportParseService,
                reportRepository, trendAnalyzer, checkupService, medicationService, checkinService);

        PatientRef patient = new PatientRef("1", "张三", "本人", "男", 30, List.of(), List.of("高血压"), null);

        // 1) 用户建档后的主体对象，编排器只消费已生效的 PatientRef
        when(profileService.getProfiles("u1")).thenReturn(List.of(patient));

        // 2) 用户输入“头痛发烧”，触发导诊并进入追问阶段
        TriageResult triageResult = new TriageResult(List.of("神经内科", "全科"), List.of("血常规", "体温监测"), HealthDisclaimers.TRIAGE_DISCLAIMER);
        when(triageService.triage("头痛发烧", 0, List.of())).thenReturn(new TriageSessionResult(
                triageResult,
                true,
                List.of("头痛是持续还是间歇？"),
                0));

        // 3) 用户补充后选择推荐科室，导诊结束，进入挂号推荐
        when(triageService.triage("头痛发烧 已选择神经内科", 1, List.of("头痛是持续还是间歇？"))).thenReturn(new TriageSessionResult(
                triageResult,
                false,
                List.of(),
                1));

        List<SlotOption> slots = List.of(
                new SlotOption("市第一医院", "神经内科", LocalDateTime.of(2026, 8, 6, 9, 0), "李医生", "https://example.com/booking/1"),
                new SlotOption("市第二医院", "神经内科", LocalDateTime.of(2026, 8, 6, 10, 0), "王医生", "https://example.com/booking/2")
        );
        when(bookingService.recommend(any(), eq(patient), eq(triageResult))).thenReturn(slots);
        when(bookingService.confirm(eq("booking-1"), eq(slots.get(0)), eq(patient), eq(false)))
                .thenReturn(Optional.of(new BookingReceipt("市第一医院", "神经内科", "2026-08-06 09:00", "booking-1", "https://example.com/booking/1")));

        // 4) 并行侧枝：体检模板正常返回
        when(checkupService.generatePlan(30, "男", List.of("高血压"), "none"))
                .thenReturn(new CheckupService.CheckupPlan("青年套餐", List.of("血常规", "尿常规", "心电图"), List.of("高血压")));

        // 4a) 用药历史摘要故意故障：抛异常，验证降级
        when(medicationService.list("u1")).thenThrow(new RuntimeException("medication summary failed"));

        // 其他并行侧枝保持正常/空结果
        when(checkinService.getRecent("u1", 7)).thenReturn(List.of());
        when(reportParseService.parse(new com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseRequest(Path.of("/tmp/report.pdf"), "report.pdf")))
                .thenReturn(new HealthReportParseResult("pdf", "report.pdf", 1, List.of(), List.of(), HealthDisclaimers.REPORT_DISCLAIMER, "建议复查"));
        when(trendAnalyzer.analyze(List.of(), Map.of())).thenReturn(List.of());

        // A/B 两段 E2E：先追问，再进入挂号推荐并验证并行/降级结果不影响主链路
        HealthFlowResult round1 = orchestrator.execute(new HealthFlowRequest("u1", "u1", "头痛发烧", null, null, 0, List.of()));
        assertTrue(round1.snapshot().waitingForUser(), "第一次导诊应进入追问阶段");
        assertTrue(round1.slots().isEmpty(), "追问阶段不应提前产出挂号候选");
        assertEquals(HealthFlowAction.TRIAGE, round1.snapshot().currentAction());

        HealthFlowResult result = orchestrator.execute(new HealthFlowRequest(
                "u1",
                "u1",
                "头痛发烧 已选择神经内科",
                "/tmp/report.pdf",
                "report.pdf",
                1,
                List.of("头痛是持续还是间歇？")));

        // 5) 断言：主链路仍然走到挂号推荐
        assertNotNull(result);
        assertNotNull(result.snapshot());
        assertEquals("u1", result.snapshot().userId());
        assertEquals(HealthFlowAction.BOOKING, result.snapshot().currentAction(), "应进入挂号阶段而不是被侧枝异常打断");
        assertFalse(result.slots().isEmpty(), "挂号推荐必须返回 SlotOption 候选");
        assertNotNull(result.bookingReceipt(), "主链路应生成挂号回执");

        // 6) 断言：并行侧枝降级被记录
        assertTrue(result.degradedStages().contains("medication"), "用药摘要失败必须写入降级阶段");
        assertTrue(result.snapshot().degradedStages().contains("medication"), "快照应同步记录用药摘要降级");

        // 7) 断言：侧枝任务不影响主链路
        assertFalse(result.checkupSuggestions().isEmpty(), "体检模板侧枝应正常返回");
        assertTrue(result.stageStatuses().get("booking.recommend") == HealthFlowResult.StageStatus.COMPLETED,
                "挂号推荐阶段不能被用药摘要异常拖垮");
        assertTrue(result.stageStatuses().get("medication") == HealthFlowResult.StageStatus.DEGRADED,
                "用药摘要分支应单独标记为降级");
        assertTrue(result.stageStatuses().get("checkup") == HealthFlowResult.StageStatus.COMPLETED
                        || result.stageStatuses().get("checkup") == HealthFlowResult.StageStatus.SKIPPED,
                "体检侧枝不应影响主链路，只需保持正常或可跳过状态");
    }

    @Test
    @DisplayName("追问阶段应跳过挂号与报告")
    void execute_shouldSkipBookingWhenWaitingForUser() {
        DefaultHealthFlowOrchestrator orchestrator = new DefaultHealthFlowOrchestrator(
                profileService, triageService, bookingService, reportParseService,
                reportRepository, trendAnalyzer, checkupService, medicationService, checkinService);

        when(triageService.triage("咳嗽", 0, List.of())).thenReturn(new TriageSessionResult(
                new TriageResult(List.of("呼吸内科"), List.of(), HealthDisclaimers.TRIAGE_DISCLAIMER),
                true,
                List.of("咳嗽多久了？"),
                0));

        HealthFlowResult result = orchestrator.execute(new HealthFlowRequest(null, "咳嗽"));

        assertTrue(result.snapshot().waitingForUser());
        assertTrue(result.slots().isEmpty());
        assertTrue(result.stageStatuses().get("booking.recommend") == HealthFlowResult.StageStatus.SKIPPED);
    }
}
