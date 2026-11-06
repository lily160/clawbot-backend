package com.github.wechat.cloudbot.modules.health.checkin.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthCheckin;
import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthReminderKind;
import com.github.wechat.cloudbot.modules.health.checkin.service.CheckinService;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 工具层：健康打卡（health_checkin_add）。
 *
 * <p>用户说「喝水打卡」「运动打卡」「早睡打卡」时记录当日健康习惯，同日同类累加次数。</p>
 */
@Component
public class HealthCheckinAddTool implements AgentTool {

    private final CheckinService checkinService;
    private final ObjectMapper objectMapper;

    public HealthCheckinAddTool(CheckinService checkinService, ObjectMapper objectMapper) {
        this.checkinService = checkinService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                CheckinToolNames.CHECKIN_ADD,
                "当用户要求记录健康打卡（如「喝水打卡」「运动打卡」「早睡打卡」）时必须调用本工具，记录当日健康习惯。"
                        + "也可以用于补卡：通过 date 指定补哪一天。",
                """
                {"type":"object","properties":{
                  "kind":{"type":"string","description":"打卡类型：drink_water=喝水、exercise=运动、sleep_early=早睡","enum":["drink_water","exercise","sleep_early"]},
                  "date":{"type":"string","description":"打卡日期 YYYY-MM-DD，缺省为今天（补卡时使用）"},
                  "count":{"type":"integer","description":"本次打卡次数/量（如喝水几杯），缺省为 1"},
                  "remark":{"type":"string","description":"备注"},
                  "user_id":{"type":"string","description":"用户ID（由编排层自动注入）"}
                },"required":["kind"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String kindStr = ToolArgs.text(args, "kind");
            String dateStr = ToolArgs.text(args, "date");
            int count = ToolArgs.intValue(args, "count", 1);
            String remark = ToolArgs.text(args, "remark");
            String userId = ToolArgs.resolveUserId(call, args);

            if (kindStr.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "打卡类型不能为空");
            }
            if (userId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "无法识别用户身份，请重试");
            }

            HealthReminderKind kind = HealthReminderKind.parse(kindStr);
            if (!kind.isCheckinKind()) {
                return ToolResult.fail(call.id(), call.name(), "该类型暂不支持打卡，仅支持喝水 / 运动 / 早睡");
            }
            LocalDate date = parseDate(dateStr);
            HealthCheckin saved = checkinService.checkin(userId, kind, date, count, remark);

            String todaySummary = checkinService.summaryText(checkinService.getToday(userId), 1);
            String backfill = date.isEqual(LocalDate.now()) ? "" : "（补卡 " + date + "）";
            String reply = "✅ 已记录 " + kind.emoji() + " " + kind.label() + " × " + saved.count()
                    + backfill + "\n\n" + todaySummary + "\n" + HealthDisclaimers.MEDICAL_DISCLAIMER;
            return ToolResult.ok(call.id(), call.name(), reply);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null ? "打卡失败" : e.getMessage();
            return ToolResult.fail(call.id(), call.name(), msg);
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "打卡失败，请稍后再试");
        }
    }

    private static LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return LocalDate.now();
        }
        try {
            return LocalDate.parse(dateStr.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式应为 YYYY-MM-DD，例如 2026-08-03");
        }
    }
}
