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

/** Function-calling tool for enabling or disabling a persisted reminder. */
@Component
public class ManageReminderTool implements AgentTool {

    private final ReminderService reminderService;
    private final ObjectMapper objectMapper;

    public ManageReminderTool(ReminderService reminderService, ObjectMapper objectMapper) {
        this.reminderService = reminderService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "manage_reminder",
                "当用户要求关闭、暂停、启用或恢复某条定时任务时调用。关闭不会删除任务。",
                """
                {"type":"object","properties":{
                  "action":{"type":"string","enum":["enable","disable"],"description":"启用或关闭"},
                  "index":{"type":"integer","description":"任务序号，从1开始"},
                  "keyword":{"type":"string","description":"任务内容关键词，未提供序号时使用"},
                  "user_id":{"type":"string","description":"由编排层自动注入"}
                },"required":["action"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String userId = ToolArgs.text(args, "user_id");
            String action = ToolArgs.text(args, "action");
            int index = ToolArgs.intValue(args, "index", -1);
            String keyword = ToolArgs.text(args, "keyword");
            if (userId.isBlank() || (!"enable".equals(action) && !"disable".equals(action))) {
                return ToolResult.fail(call.id(), call.name(), "用户或操作参数无效");
            }
            // 序号基于启用列表（同 list_reminders / delete_reminder），保证用户看到的序号一致；
            // 关键词回退到全量列表，以便按内容恢复已禁用的提醒。
            List<Reminder> visible = reminderService.list(userId);
            List<Reminder> all = reminderService.listAll(userId);
            Reminder target = null;
            if (index > 0 && index <= visible.size()) {
                target = visible.get(index - 1);
            } else if (!keyword.isBlank()) {
                target = all.stream()
                        .filter(task -> task.content().contains(keyword))
                        .findFirst()
                        .orElse(null);
            }
            if (target == null) {
                return ToolResult.fail(call.id(), call.name(), "没有找到对应的定时任务");
            }
            boolean enabled = "enable".equals(action);
            if (!reminderService.setEnabled(userId, target.id(), enabled)) {
                return ToolResult.fail(call.id(), call.name(), "更新定时任务失败");
            }
            return ToolResult.ok(
                    call.id(),
                    call.name(),
                    (enabled ? "已启用定时任务：" : "已关闭定时任务：") + target.content());
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "管理定时任务失败：" + e.getMessage());
        }
    }
}
