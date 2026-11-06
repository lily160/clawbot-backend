package com.github.wechat.cloudbot.modules.health.medication.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthCheckin;
import com.github.wechat.cloudbot.modules.health.checkin.service.CheckinService;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.common.HealthToolNames;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationRecord;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 工具层：服药打卡（health_med_take）。
 *
 * <p>用户回复「已吃药 / 阿司匹林吃了 / 今天药吃了」时记录服药打卡，与用药提醒形成闭环：
 * 提醒 → 回复已吃 → 今日服药汇总 + 连续打卡天数。数据落在健康打卡表（kind=med_take）。</p>
 */
@Component
public class HealthMedTakeTool implements AgentTool {

    private final MedicationService medicationService;
    private final CheckinService checkinService;
    private final ObjectMapper objectMapper;

    public HealthMedTakeTool(MedicationService medicationService,
                             CheckinService checkinService,
                             ObjectMapper objectMapper) {
        this.medicationService = medicationService;
        this.checkinService = checkinService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                HealthToolNames.MED_TAKE,
                "当用户回复「已吃药」「吃过了」「阿司匹林吃了」等表达服药完成，或要求记录服药打卡时必须调用本工具。"
                        + "不传 drug_name 时默认把当前进行中的用药全部记为今日已服。",
                """
                {"type":"object","properties":{
                  "drug_name":{"type":"string","description":"本次服用的药品名称（可选）；缺省时记录全部进行中的用药"},
                  "date":{"type":"string","description":"打卡日期 YYYY-MM-DD，缺省为今天（补卡时使用）"},
                  "user_id":{"type":"string","description":"用户ID（由编排层自动注入）"}
                },"required":[]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String drugName = ToolArgs.text(args, "drug_name");
            String dateStr = ToolArgs.text(args, "date");
            String userId = ToolArgs.resolveUserId(call, args);

            if (userId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "无法识别用户身份，请重试");
            }
            LocalDate date = parseDate(dateStr);
            if (date.isAfter(LocalDate.now())) {
                return ToolResult.fail(call.id(), call.name(), "打卡日期不能是未来日期");
            }
            List<MedicationRecord> active = medicationService.list(userId);

            List<HealthCheckin> recorded = new ArrayList<>();
            if (drugName == null || drugName.isBlank()) {
                if (active.isEmpty()) {
                    return ToolResult.ok(call.id(), call.name(),
                            "当前没有进行中的用药记录，直接告诉我药名也可以记录服药打卡。\n"
                                    + HealthDisclaimers.MEDICAL_DISCLAIMER);
                }
                for (MedicationRecord record : active) {
                    recorded.add(checkinService.medTake(userId, record.drugName(), date));
                }
            } else {
                String matched = matchActiveDrug(active, drugName);
                recorded.add(checkinService.medTake(userId, matched != null ? matched : drugName.trim(), date));
            }

            boolean isBackfill = !date.isEqual(LocalDate.now());
            int kindsOnDate = medKindsOn(date, userId);
            int streak = checkinService.medTakeStreak(userId);
            StringBuilder reply = new StringBuilder("✅ 已记录服药打卡");
            if (!date.isEqual(LocalDate.now())) {
                reply.append("（补卡 ").append(date).append("）");
            }
            reply.append("：\n");
            for (HealthCheckin r : recorded) {
                // 补卡时 count 是补卡日的次数，标注为「第 N 次」而非「今日第 N 次」
                reply.append("· ").append(r.remark())
                        .append(isBackfill ? "：第 " : "：今日第 ")
                        .append(r.count()).append(" 次\n");
            }
            reply.append("\n📊 ").append(isBackfill ? date : "今日")
                    .append(" 已服药 ").append(kindsOnDate).append("/").append(active.size()).append(" 种");
            if (streak > 0) {
                reply.append("，连续服药打卡 ").append(streak).append(" 天");
            }
            reply.append("\n").append(HealthDisclaimers.MEDICATION_DISCLAIMER);
            return ToolResult.ok(call.id(), call.name(), reply.toString());
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null ? "服药打卡失败" : e.getMessage();
            return ToolResult.fail(call.id(), call.name(), msg);
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "服药打卡失败，请稍后再试");
        }
    }

    /** 匹配用户给的目标药名与进行中用药（双向包含），命中则用档案中的规范药名。 */
    private static String matchActiveDrug(List<MedicationRecord> active, String drugName) {
        String norm = drugName.trim();
        for (MedicationRecord record : active) {
            if (record.drugName().contains(norm) || norm.contains(record.drugName())) {
                return record.drugName();
            }
        }
        return null;
    }

    /** 指定日期已服药品种类数（从打卡表该日 med_take 的 remark 解析去重；补卡时按补卡日统计，不混用今日）。 */
    private int medKindsOn(LocalDate date, String userId) {
        if (date == null || date.isAfter(LocalDate.now())) {
            return 0;
        }
        long back = ChronoUnit.DAYS.between(date, LocalDate.now());
        int days = (int) Math.min(back + 1, 366);
        return checkinService.getRecent(userId, days).stream()
                .filter(c -> CheckinService.MED_TAKE_KIND.equals(c.kind()))
                .filter(c -> c.checkinDate().isEqual(date))
                .map(HealthCheckin::remark)
                .filter(r -> r != null && !r.isBlank())
                .flatMap(r -> Arrays.stream(r.split("、")))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList()
                .size();
    }

    private static LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(dateStr.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式应为 YYYY-MM-DD，例如 2026-08-05");
        }
    }
}
