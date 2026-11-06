package com.github.wechat.cloudbot.modules.health.medication.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.common.HealthToolNames;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationRecord;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具层：修改用药记录（health_med_update）。
 *
 * <p>修改已登记用药的用量/频次/疗程天数/备注，修改后自动重新注册提醒。
 */
@Component
public class HealthMedEditTool implements AgentTool {

    private final MedicationService medicationService;
    private final ObjectMapper objectMapper;

    public HealthMedEditTool(MedicationService medicationService, ObjectMapper objectMapper) {
        this.medicationService = medicationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                HealthToolNames.MED_UPDATE,
                "修改已登记的用药记录信息（用量/频次/疗程天数/备注）。用户要求修改用药信息时必须调用。",
                """
                {"type":"object","properties":{
                  "record_id":{"type":"string","description":"用药记录ID（通过 health_med_list 获取）"},
                  "dosage":{"type":"string","description":"新的每次用量，如「2片」「5ml」"},
                  "frequency":{"type":"string","description":"新的用药频次，可选：每日一次、每日两次、每日三次、隔日一次、每周一次、每晚一次"},
                  "course_days":{"type":"integer","description":"新的疗程天数，0表示长期服用"},
                  "reminder_times":{"type":"array","items":{"type":"string"},"description":"新的自定义每日提醒时间（可选，HH:mm；缺省保持原值，空数组清空）"},
                  "notes":{"type":"string","description":"新的备注"},
                  "user_id":{"type":"string","description":"用户ID（由编排层自动注入）"}
                },"required":["record_id"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String recordId = ToolArgs.text(args, "record_id");
            String userId = ToolArgs.resolveUserId(call, args);
            if (recordId.isBlank() || userId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "参数不完整，请提供记录ID");
            }

            String dosage = ToolArgs.text(args, "dosage");
            String frequency = ToolArgs.text(args, "frequency");
            int courseDays = ToolArgs.intValue(args, "course_days", -1);
            String notes = ToolArgs.text(args, "notes");
            List<String> reminderTimes = parseTimesOrNull(args);

            MedicationRecord updated = medicationService.update(userId, recordId,
                    dosage, frequency, courseDays, notes, reminderTimes);

            StringBuilder reply = new StringBuilder();
            reply.append("已更新用药记录：").append(updated.drugName())
                    .append("，").append(updated.dosage())
                    .append("，").append(updated.frequency());
            if (updated.courseDays() > 0) {
                reply.append("，共 ").append(updated.courseDays()).append(" 天");
            }
            reply.append("。提醒已重新设置。\n").append(HealthDisclaimers.MEDICATION_DISCLAIMER);

            return ToolResult.ok(call.id(), call.name(), reply.toString());
        } catch (IllegalArgumentException e) {
            return ToolResult.fail(call.id(), call.name(), e.getMessage());
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "修改用药记录失败，请稍后再试");
        }
    }

    /** reminder_times 缺省返回 null（保持原值），空数组表示清空。 */
    private static List<String> parseTimesOrNull(JsonNode args) {
        JsonNode node = args.path("reminder_times");
        if (!node.isArray()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                result.add(item.asText().trim());
            }
        }
        return result;
    }
}
