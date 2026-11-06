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

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 工具层：列出当前用户的全部有效提醒（list_reminders）。
 */
@Component
public class ListRemindersTool implements AgentTool {

    private final ReminderService reminderService;
    private final ObjectMapper objectMapper;

    public ListRemindersTool(ReminderService reminderService, ObjectMapper objectMapper) {
        this.reminderService = reminderService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "list_reminders",
                "当用户询问「我有什么提醒」「我的待办」「查看提醒列表」等时必须调用本工具。",
                """
                {"type":"object","properties":{
                  "caption":{"type":"string","description":"发给用户的简短确认语"},
                  "user_id":{"type":"string","description":"用户ID（由编排层自动注入）"}
                },"required":[]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String userId = ToolArgs.text(args, "user_id");
            if (userId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "无法识别用户身份");
            }

            List<Reminder> list = reminderService.list(userId);
            if (list.isEmpty()) {
                return ToolResult.ok(call.id(), call.name(), "你当前没有设置任何提醒。需要我帮你设置一个吗？");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("你的提醒列表（共 ").append(list.size()).append(" 条）：\n");
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm");
            for (int i = 0; i < list.size(); i++) {
                Reminder r = list.get(i);
                sb.append(i + 1).append(". ").append(r.content());
                sb.append(" — ").append(r.triggerTime().format(fmt));
                if (r.advanceMinutes() > 0) {
                    sb.append(" (提前").append(r.advanceMinutes()).append("分)");
                }
                if (r.isRecurring()) {
                    sb.append(" [重复]");
                }
                sb.append("\n");
            }
            sb.append("\n想删除某条提醒可以告诉我序号或内容。");
            return ToolResult.ok(call.id(), call.name(), sb.toString().trim());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "查询提醒失败" : e.getMessage();
            return ToolResult.fail(call.id(), call.name(), msg);
        }
    }
}
