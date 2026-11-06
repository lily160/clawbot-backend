package com.github.wechat.cloudbot.modules.health.medication.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthReminderKind;
import com.github.wechat.cloudbot.modules.health.checkin.service.HealthReminderService;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.common.HealthToolNames;
import com.github.wechat.cloudbot.modules.task.service.Reminder;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;

/**
 * 工具层：日常健康提醒（health_daily_reminder）。
 *
 * <p>每天定时提醒喝水/运动/早睡/复查，已接入打卡模块的统一提醒接口
 * {@link HealthReminderService}，工具名与参数保持向后兼容。</p>
 */
@Component
public class HealthDailyReminderTool implements AgentTool {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final HealthReminderService healthReminderService;
    private final ObjectMapper objectMapper;

    public HealthDailyReminderTool(HealthReminderService healthReminderService, ObjectMapper objectMapper) {
        this.healthReminderService = healthReminderService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                HealthToolNames.DAILY_REMINDER,
                "当用户要求设置日常健康提醒（每天定时：喝水/运动/早睡/复查）时必须调用本工具。"
                        + "例如「每天早上8点提醒我喝水」「每天21点提醒我早睡」。"
                        + "time 用 24 小时制 HH:mm（如 08:00、21:30）。",
                """
                {"type":"object","properties":{
                  "kind":{"type":"string","description":"提醒类型：drink_water=喝水、exercise=运动、sleep_early=早睡、followup=复查、custom=自定义","enum":["drink_water","exercise","sleep_early","followup","custom"]},
                  "time":{"type":"string","description":"每日提醒时间，24小时制 HH:mm，如 08:00、21:30"},
                  "note":{"type":"string","description":"kind=custom 时的自定义提醒内容"},
                  "user_id":{"type":"string","description":"用户ID（由编排层自动注入）"}
                },"required":["kind","time"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String kind = ToolArgs.text(args, "kind");
            String time = ToolArgs.text(args, "time");
            String note = ToolArgs.text(args, "note");
            String userId = ToolArgs.resolveUserId(call, args);

            if (kind.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "提醒类型不能为空");
            }
            if (time.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "提醒时间不能为空");
            }
            if (userId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "无法识别用户身份，请重试");
            }

            HealthReminderKind reminderKind = HealthReminderKind.parse(kind);
            Reminder reminder = healthReminderService.setReminder(userId, reminderKind, time, note);

            String label = reminderKind == HealthReminderKind.CUSTOM
                    ? (note.isBlank() ? "自定义" : note)
                    : reminderKind.label();
            String reply = "已设置日常提醒：每天 "
                    + reminder.triggerTime().format(TIME_FORMATTER) + " "
                    + label + "。到点我会主动发消息提醒你。\n\n"
                    + HealthDisclaimers.MEDICAL_DISCLAIMER;
            return ToolResult.ok(call.id(), call.name(), reply);
        } catch (IllegalArgumentException | IllegalStateException e) {
            String msg = e.getMessage() == null ? "设置日常提醒失败" : e.getMessage();
            return ToolResult.fail(call.id(), call.name(), msg);
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "设置日常提醒失败，请稍后再试");
        }
    }
}
