package com.github.wechat.cloudbot.modules.task.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.task.service.Reminder;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 工具层：添加提醒（add_reminder）。
 *
 * <p>自然语言时间由 LLM 解析为 ISO-8601 结构化参数，本工具只负责校验与持久化。
 */
@Component
public class AddReminderTool implements AgentTool {

    private final ReminderService reminderService;
    private final ObjectMapper objectMapper;

    public AddReminderTool(ReminderService reminderService, ObjectMapper objectMapper) {
        this.reminderService = reminderService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return new ToolDefinition(
                "add_reminder",
                "当用户要求设置提醒、闹钟、待办事项、记住某事时必须调用本工具，禁止直接回复「好的」而不调用工具。"
                        + "当前时间：" + now + "。"
                        + "支持自然语言时间，如「今晚9点」「周五下午3点」「明天早上8点」。"
                        + "若用户说「每天/每周提醒我做某事」，recurrence 设为 daily/weekly。",
                """
                {"type":"object","properties":{
                  "content":{"type":"string","description":"提醒内容，如「吃药」「开会」"},
                  "trigger_time":{"type":"string","description":"触发时间，ISO-8601格式 yyyy-MM-ddTHH:mm:ss。根据用户自然语言结合当前时间推算。如用户说今晚9点且当前是下午，则设为今天21:00:00"},
                  "advance_minutes":{"type":"integer","description":"提前几分钟提醒，默认0","default":0},
                  "recurrence":{"type":"string","description":"提醒周期：once=一次性（默认），daily=每天，weekly=每周","enum":["once","daily","weekly"],"default":"once"},
                  "caption":{"type":"string","description":"发给用户的简短确认语，例如「好的，已帮你设置提醒…」"},
                  "question":{"type":"string","description":"用户原问题（可选，编排层可注入）"},
                  "user_id":{"type":"string","description":"用户ID（由编排层自动注入，不需要用户填写）"}
                },"required":["content","trigger_time"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String content = ToolArgs.text(args, "content");
            String triggerTimeStr = ToolArgs.text(args, "trigger_time");
            int advanceMinutes = ToolArgs.intValue(args, "advance_minutes", 0);
            String recurrence = ToolArgs.text(args, "recurrence");
            String userId = ToolArgs.text(args, "user_id");
            String caption = ToolArgs.text(args, "caption");

            if (content.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "提醒内容不能为空");
            }
            if (triggerTimeStr.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "触发时间不能为空");
            }
            if (userId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "无法识别用户身份，请重试");
            }

            LocalDateTime triggerTime = parseTriggerTime(triggerTimeStr);
            if (triggerTime.isBefore(LocalDateTime.now().minusMinutes(1))) {
                return ToolResult.fail(call.id(), call.name(), "提醒时间不能是过去的时间，请重新设置");
            }
            if (recurrence.isBlank()) {
                recurrence = "once";
            }

            String cron = ToolArgs.text(args, "cron");
            // If cron is blank but recurrence is daily/weekly, convert it
            if ((cron == null || cron.isBlank()) && !recurrence.isBlank() && !"once".equals(recurrence)) {
                if (!"daily".equals(recurrence) && !"weekly".equals(recurrence)) {
                    return ToolResult.fail(call.id(), call.name(),
                            "不支持的重复类型：" + recurrence + "（仅支持 once / daily / weekly）");
                }
                int hour = triggerTime.getHour();
                int minute = triggerTime.getMinute();
                cron = "daily".equals(recurrence)
                        ? "0 " + minute + " " + hour + " * * ?"
                        : "0 " + minute + " " + hour + " ? * " + triggerTime.getDayOfWeek().getValue();
            }
            Reminder reminder = reminderService.add(userId, content, triggerTime, advanceMinutes, cron);
            String reply = (caption != null && !caption.isBlank()) ? caption : buildAck(reminder);
            return ToolResult.ok(call.id(), call.name(), reply);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "添加提醒失败" : e.getMessage();
            return ToolResult.fail(call.id(), call.name(), msg);
        }
    }

    /** 兼容 LLM 常见的空格分隔写法（"2026-08-06 21:00:00"）与标准 ISO-8601（含 T）。 */
    private static LocalDateTime parseTriggerTime(String text) {
        return LocalDateTime.parse(text.trim().replace(' ', 'T'));
    }

    private static String buildAck(Reminder r) {
        String timeStr = r.triggerTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        StringBuilder sb = new StringBuilder();
        sb.append("已设置提醒：").append(r.content()).append("，时间 ").append(timeStr);
        if (r.advanceMinutes() > 0) {
            sb.append("，提前 ").append(r.advanceMinutes()).append(" 分钟提醒");
        }
        if (r.isRecurring()) {
            sb.append("，重复提醒");
        }
        sb.append("。到点我会主动发消息提醒你。");
        return sb.toString();
    }
}
