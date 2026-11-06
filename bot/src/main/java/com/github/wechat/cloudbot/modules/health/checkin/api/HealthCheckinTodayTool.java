package com.github.wechat.cloudbot.modules.health.checkin.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthCheckin;
import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthReminderKind;
import com.github.wechat.cloudbot.modules.health.checkin.service.CheckinService;
import com.github.wechat.cloudbot.modules.health.checkin.service.HealthReminderService;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.task.service.Reminder;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具层：健康打卡查看（health_checkin_today）。
 *
 * <p>返回今日 / 最近 N 天打卡汇总，并顺带列出已设置的健康提醒（打卡与提醒联动）。</p>
 */
@Component
public class HealthCheckinTodayTool implements AgentTool {

    private final CheckinService checkinService;
    private final HealthReminderService healthReminderService;
    private final ObjectMapper objectMapper;

    public HealthCheckinTodayTool(CheckinService checkinService,
                                  HealthReminderService healthReminderService,
                                  ObjectMapper objectMapper) {
        this.checkinService = checkinService;
        this.healthReminderService = healthReminderService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                CheckinToolNames.CHECKIN_TODAY,
                "当用户询问今日 / 最近健康打卡情况（如「今天打卡了吗」「看一下打卡」）时必须调用本工具。"
                        + "会返回打卡汇总与已设置的健康提醒。",
                """
                {"type":"object","properties":{
                  "days":{"type":"integer","description":"查看最近几天（含今天），缺省为 1（只看今天）"},
                  "user_id":{"type":"string","description":"用户ID（由编排层自动注入）"}
                },"required":[]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            int days = ToolArgs.intValue(args, "days", 1);
            String userId = ToolArgs.resolveUserId(call, args);

            if (userId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "无法识别用户身份，请重试");
            }

            List<HealthCheckin> recent = checkinService.getRecent(userId, days);
            StringBuilder sb = new StringBuilder(checkinService.summaryText(recent, days));

            List<Reminder> reminders = healthReminderService.listReminders(userId);
            if (reminders.isEmpty()) {
                sb.append("\n🔔 还没有设置健康提醒，试试「每天早上 8 点提醒我喝水」。");
            } else {
                sb.append("\n🔔 已设置的健康提醒：\n");
                for (Reminder r : reminders) {
                    HealthReminderKind kind = HealthReminderService.kindOf(r);
                    if (kind != null) {
                        sb.append("• ").append(kind.emoji()).append(" ").append(kind.label())
                                .append("：").append(HealthReminderService.formatSchedule(r)).append("\n");
                    }
                }
            }
            if (days >= 7) {
                long activeDays = recent.stream()
                        .map(HealthCheckin::checkinDate)
                        .distinct()
                        .count();
                sb.append("\n📈 最近 ").append(days).append(" 天有打卡 ").append(activeDays).append(" 天");
                int medStreak = checkinService.medTakeStreak(userId);
                if (medStreak > 0) {
                    sb.append("，服药连续 ").append(medStreak).append(" 天");
                }
                sb.append("\n");
            }
            sb.append(HealthDisclaimers.MEDICAL_DISCLAIMER);
            return ToolResult.ok(call.id(), call.name(), sb.toString());
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "查询打卡情况失败，请稍后再试");
        }
    }
}
