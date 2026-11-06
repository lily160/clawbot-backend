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

import java.util.List;

/**
 * 工具层：删除提醒（delete_reminder）。
 *
 * <p>支持按序号（基于 list_reminders 的排序）或按内容关键词匹配删除。
 */
@Component
public class DeleteReminderTool implements AgentTool {

    private final ReminderService reminderService;
    private final ObjectMapper objectMapper;

    public DeleteReminderTool(ReminderService reminderService, ObjectMapper objectMapper) {
        this.reminderService = reminderService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "delete_reminder",
                "当用户要求删除、取消某条提醒时必须调用本工具。",
                """
                {"type":"object","properties":{
                  "index":{"type":"integer","description":"提醒序号（从1开始），对应 list_reminders 的列表顺序"},
                  "keyword":{"type":"string","description":"提醒内容关键词，用于模糊匹配。若提供了 index 则优先使用序号"},
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
            int index = ToolArgs.intValue(args, "index", -1);
            String keyword = ToolArgs.text(args, "keyword");
            String caption = ToolArgs.text(args, "caption");

            if (userId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "无法识别用户身份");
            }

            List<Reminder> list = reminderService.list(userId);
            if (list.isEmpty()) {
                return ToolResult.ok(call.id(), call.name(), "你当前没有设置提醒，无需删除。");
            }

            Reminder target = null;
            if (index > 0 && index <= list.size()) {
                target = list.get(index - 1);
            } else if (!keyword.isBlank()) {
                for (Reminder r : list) {
                    if (r.content().contains(keyword)) {
                        target = r;
                        break;
                    }
                }
            }

            if (target == null) {
                return ToolResult.fail(call.id(), call.name(),
                        "找不到要删除的提醒。你可以先调用 list_reminders 查看列表，再告诉我序号或内容关键词。");
            }

            boolean removed = reminderService.delete(userId, target.id());
            if (removed) {
                String reply = (caption != null && !caption.isBlank()) ? caption
                        : "已删除提醒：" + target.content() + "。";
                return ToolResult.ok(call.id(), call.name(), reply);
            }
            return ToolResult.fail(call.id(), call.name(), "删除失败，请稍后重试。");
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "删除提醒失败" : e.getMessage();
            return ToolResult.fail(call.id(), call.name(), msg);
        }
    }
}
