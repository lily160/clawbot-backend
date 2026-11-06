package com.github.wechat.cloudbot.modules.health.checkin.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthReminderKind;
import com.github.wechat.cloudbot.modules.health.checkin.service.HealthReminderService;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 工具层：取消日常健康提醒（health_reminder_cancel）。
 */
@Component
public class HealthReminderCancelTool implements AgentTool {

    private final HealthReminderService healthReminderService;
    private final ObjectMapper objectMapper;

    public HealthReminderCancelTool(HealthReminderService healthReminderService, ObjectMapper objectMapper) {
        this.healthReminderService = healthReminderService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                CheckinToolNames.REMINDER_CANCEL,
                "当用户要求取消日常健康提醒（如「取消喝水提醒」「不要早睡提醒了」）时必须调用本工具。",
                """
                {"type":"object","properties":{
                  "kind":{"type":"string","description":"要取消的提醒类型：drink_water=喝水、exercise=运动、sleep_early=早睡、followup=复查、custom=自定义","enum":["drink_water","exercise","sleep_early","followup","custom"]},
                  "user_id":{"type":"string","description":"用户ID（由编排层自动注入）"}
                },"required":["kind"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String kindStr = ToolArgs.text(args, "kind");
            String userId = ToolArgs.resolveUserId(call, args);

            if (kindStr.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "提醒类型不能为空");
            }
            if (userId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "无法识别用户身份，请重试");
            }

            HealthReminderKind kind = HealthReminderKind.parse(kindStr);
            boolean removed = healthReminderService.cancelReminder(userId, kind);
            String reply = (removed ? "✅ 已取消" : "当前没有找到")
                    + "「" + kind.label() + "」提醒。\n" + HealthDisclaimers.MEDICAL_DISCLAIMER;
            return ToolResult.ok(call.id(), call.name(), reply);
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage() == null ? "取消失败" : e.getMessage();
            return ToolResult.fail(call.id(), call.name(), msg);
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "取消提醒失败，请稍后再试");
        }
    }
}
