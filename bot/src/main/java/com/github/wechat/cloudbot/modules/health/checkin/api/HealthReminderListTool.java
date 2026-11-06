package com.github.wechat.cloudbot.modules.health.checkin.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthReminderKind;
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
 * 工具层：查看日常健康提醒（health_reminder_list）。
 */
@Component
public class HealthReminderListTool implements AgentTool {

    private final HealthReminderService healthReminderService;
    private final ObjectMapper objectMapper;

    public HealthReminderListTool(HealthReminderService healthReminderService, ObjectMapper objectMapper) {
        this.healthReminderService = healthReminderService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                CheckinToolNames.REMINDER_LIST,
                "当用户询问已设置的日常健康提醒（如「我设置了哪些提醒」）时必须调用本工具。",
                """
                {"type":"object","properties":{
                  "user_id":{"type":"string","description":"用户ID（由编排层自动注入）"}
                },"required":[]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String userId = ToolArgs.resolveUserId(call, args);

            if (userId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "无法识别用户身份，请重试");
            }

            List<Reminder> reminders = healthReminderService.listReminders(userId);
            if (reminders.isEmpty()) {
                String reply = "当前还没有设置日常健康提醒。可以试试「每天早上 8 点提醒我喝水」\n"
                        + HealthDisclaimers.MEDICAL_DISCLAIMER;
                return ToolResult.ok(call.id(), call.name(), reply);
            }
            StringBuilder sb = new StringBuilder("🔔 您的日常健康提醒：\n");
            for (Reminder r : reminders) {
                HealthReminderKind kind = HealthReminderService.kindOf(r);
                if (kind != null) {
                    sb.append("• ").append(kind.emoji()).append(" ").append(kind.label())
                            .append("：").append(HealthReminderService.formatSchedule(r)).append("\n");
                }
            }
            sb.append("\n").append(HealthDisclaimers.MEDICAL_DISCLAIMER);
            return ToolResult.ok(call.id(), call.name(), sb.toString());
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "查询提醒失败，请稍后再试");
        }
    }
}
