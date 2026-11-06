package com.github.wechat.cloudbot.modules.health.booking.service;

import com.github.wechat.cloudbot.modules.health.booking.api.BookingServicePort;
import com.github.wechat.cloudbot.modules.health.booking.infra.BookingQueryClient;
import com.github.wechat.cloudbot.modules.health.booking.infra.HealthBookingProperties;
import com.github.wechat.cloudbot.modules.health.booking.infra.SandboxBookingQueryClient;
import com.github.wechat.cloudbot.modules.health.booking.infra.HealthBookingProperties.MiniProgramTarget;
import com.github.wechat.cloudbot.modules.health.common.BookingReceipt;
import com.github.wechat.cloudbot.modules.health.common.PatientRef;
import com.github.wechat.cloudbot.modules.health.common.SlotOption;
import com.github.wechat.cloudbot.modules.health.common.TriageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 挂号领域服务：统一消费真实医院平台适配器。
 * <p>
 * Spring 运行时没有真实适配器时返回空结果，绝不把沙箱号源冒充真实号源；
 * 无参构造仅保留给领域单测使用，不参与 Spring Bean 装配。
 */
@Service
public class BookingService implements BookingServicePort {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final HealthBookingProperties bookingProperties;
    private final DepartmentHospitalRanker ranker;
    private final BookingQueryClient bookingQueryClient;
    private final boolean runtimeManaged;

    public BookingService() {
        this(new HealthBookingProperties(), null, new SandboxBookingQueryClient(), false);
    }

    public BookingService(HealthBookingProperties bookingProperties) {
        this(bookingProperties, null, new SandboxBookingQueryClient(), false);
    }

    public BookingService(HealthBookingProperties bookingProperties, DepartmentHospitalRanker ranker) {
        this(bookingProperties, ranker, new SandboxBookingQueryClient(), false);
    }

    @Autowired
    public BookingService(HealthBookingProperties bookingProperties,
                          DepartmentHospitalRanker ranker,
                          ObjectProvider<BookingQueryClient> bookingClients) {
        this(bookingProperties, ranker, bookingClients.getIfAvailable(), true);
    }

    private BookingService(HealthBookingProperties bookingProperties,
                           DepartmentHospitalRanker ranker,
                           BookingQueryClient bookingQueryClient,
                           boolean runtimeManaged) {
        this.bookingProperties = bookingProperties == null ? new HealthBookingProperties() : bookingProperties;
        this.ranker = ranker;
        this.bookingQueryClient = bookingQueryClient;
        this.runtimeManaged = runtimeManaged;
    }

    public HospitalRankResult rankHospitals(String department) {
        return ranker == null ? new HospitalRankResult(department, List.of(), "") : ranker.rank(department);
    }

    @Override
    public List<SlotOption> recommend(String symptomText, PatientRef patientRef, TriageResult triageResult) {
        String department = triageResult == null || triageResult.suggestedDepartments().isEmpty()
                ? "全科"
                : triageResult.suggestedDepartments().get(0);
        int limit = Math.max(1, bookingProperties.getDefaultRecommendLimit());
        if (bookingQueryClient == null) {
            // Spring 运行时没有真实适配器时绝不伪造号源，返回空列表交由编排层降级提示
            return runtimeManaged ? List.of()
                    : fallbackSlots(department, limit, symptomText, patientRef, triageResult);
        }
        try {
            List<SlotOption> slots = bookingQueryClient.recommend(patientRef, department, limit);
            if (slots == null || slots.isEmpty()) {
                log.info("挂号客户端未返回号源，返回空列表 department={}", department);
                return List.of();
            }
            return slots.stream().filter(slot -> slot != null).limit(limit).toList();
        } catch (Exception e) {
            log.warn("挂号推荐查询失败，返回空号源 department={}: {}", department, e.getMessage());
            return List.of();
        }
    }

    public HospitalRankResult rankHospitalsForTriage(TriageResult triageResult) {
        String department = triageResult == null || triageResult.suggestedDepartments().isEmpty()
                ? "全科"
                : triageResult.suggestedDepartments().get(0);
        return rankHospitals(department);
    }

    @Override
    public Optional<BookingReceipt> confirm(String chosenSlotId, SlotOption slotOption, PatientRef patientRef) {
        return confirm(chosenSlotId, slotOption, patientRef, true);
    }

    public Optional<BookingReceipt> confirm(String chosenSlotId, SlotOption slotOption, PatientRef patientRef,
                                            boolean returnMiniProgramOnly) {
        if (slotOption == null) {
            return Optional.empty();
        }

        if (bookingQueryClient != null) {
            try {
                BookingQueryClient.BookingSubmitResult submitted = bookingQueryClient.confirm(patientRef, slotOption);
                if (submitted != null && submitted.success()) {
                    String orderNo = firstNonBlank(submitted.orderNo(), chosenSlotId, "未生成");
                    String visitTime = firstNonBlank(submitted.visitTimeText(), formatVisitTime(slotOption));
                    String link = firstNonBlank(submitted.bookingLink(), slotOption.bookingLink());
                    return Optional.of(new BookingReceipt(slotOption.hospitalName(), slotOption.department(),
                            visitTime, orderNo, link));
                }
            } catch (Exception e) {
                // fall through to the non-runtime fallback below when permitted
            }
        }

        // Spring 运行时没有真实客户端或真实提交失败时，不能生成伪造成功回执。
        if (runtimeManaged || !returnMiniProgramOnly) {
            return Optional.empty();
        }

        // 仅供不经过 Spring 的旧领域单测/本地适配器调用；这里仍明确是入口，不是已预约。
        MiniProgramTarget target = bookingProperties.resolveMiniProgram(slotOption.hospitalName());
        String link = buildMiniProgramLink(chosenSlotId, slotOption, target);
        return Optional.of(new BookingReceipt(
                slotOption.hospitalName(),
                slotOption.department(),
                formatVisitTime(slotOption),
                chosenSlotId == null || chosenSlotId.isBlank() ? "待确认" : chosenSlotId,
                link));
    }

    public String buildMiniProgramLink(String chosenSlotId, SlotOption slotOption) {
        if (slotOption == null) {
            return "";
        }
        return buildMiniProgramLink(chosenSlotId, slotOption, bookingProperties.resolveMiniProgram(slotOption.hospitalName()));
    }

    private String buildMiniProgramLink(String chosenSlotId, SlotOption slotOption, MiniProgramTarget target) {
        String path = target == null ? "" : target.getPath();
        StringBuilder sb = new StringBuilder();
        if (path != null && !path.isBlank()) {
            sb.append(path);
        }
        String query = "hospital=" + safe(slotOption.hospitalName())
                + "&dept=" + safe(slotOption.department())
                + "&date=" + safe(slotOption.visitTime() == null ? null : slotOption.visitTime().toLocalDate().toString())
                + "&slot=" + safe(chosenSlotId)
                + "&doctor=" + safe(slotOption.doctorName());
        if (sb.length() > 0) {
            sb.append('?').append(query);
        } else {
            sb.append("pages/booking/index?").append(query);
        }
        return sb.toString();
    }

    public String miniProgramName(String hospitalName) {
        MiniProgramTarget target = bookingProperties.resolveMiniProgram(hospitalName);
        return target == null || target.getName() == null || target.getName().isBlank()
                ? hospitalName
                : target.getName();
    }

    public String miniProgramHint() {
        return bookingProperties.getMiniProgramHint();
    }

    private List<SlotOption> fallbackSlots(String department, int limit, String symptomText, PatientRef patientRef, TriageResult triageResult) {
        if (limit <= 0) {
            return List.of();
        }
        String reason = buildFallbackReason(symptomText, patientRef, triageResult);
        LocalDateTime base = LocalDateTime.now().plusDays(1).withMinute(0).withSecond(0).withNano(0);
        return List.of(
                new SlotOption("杭州市余杭区第一人民医院", department, base, "导诊推荐", reason),
                new SlotOption("余杭区二院", department, base.plusHours(2), "导诊推荐", reason),
                new SlotOption("杭州市余杭区第三人民医院", department, base.plusDays(1), "导诊推荐", reason)
        ).stream().limit(limit).toList();
    }

    private String buildFallbackReason(String symptomText, PatientRef patientRef, TriageResult triageResult) {
        String deptText = triageResult == null || triageResult.suggestedDepartments().isEmpty()
                ? "全科"
                : String.join("/", triageResult.suggestedDepartments());
        String ageText = patientRef == null || patientRef.age() == null ? "年龄未知" : patientRef.age() + "岁";
        String symptom = symptomText == null || symptomText.isBlank() ? "症状信息不足" : symptomText.trim();
        return "根据" + deptText + "和" + ageText + "优先推荐，当前症状：" + symptom;
    }

    private static String formatVisitTime(SlotOption slotOption) {
        return slotOption.visitTime() == null ? "待确认" : TIME_FORMATTER.format(slotOption.visitTime());
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return fallback;
    }

    private static String firstNonBlank(String first, String second) {
        return firstNonBlank(first, second, "");
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace(" ", "");
    }
}
