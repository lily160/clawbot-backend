package com.github.wechat.cloudbot.modules.health.medication.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.common.HealthToolNames;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationRecord;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具层：列出用药记录（health_med_list）。
 *
 * <p>查询当前用户已录入的所有启用中的用药记录。
 */
@Component
public class HealthMedListTool implements AgentTool {

    private final MedicationService medicationService;
    private final ObjectMapper objectMapper;

    public HealthMedListTool(MedicationService medicationService, ObjectMapper objectMapper) {
        this.medicationService = medicationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                HealthToolNames.MED_LIST,
                "查询当前用户已登记的用药清单，返回药名、用量、频次、疗程等信息。",
                """
                {"type":"object","properties":{
                  "user_id":{"type":"string","description":"用户ID（由编排层自动注入）"}
                }}
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

            List<MedicationRecord> records = medicationService.list(userId);
            if (records.isEmpty()) {
                return ToolResult.ok(call.id(), call.name(),
                        "当前没有用药记录。" + HealthDisclaimers.MEDICATION_DISCLAIMER);
            }

            StringBuilder reply = new StringBuilder();
            reply.append("📋 当前用药记录：\n");
            for (int i = 0; i < records.size(); i++) {
                MedicationRecord r = records.get(i);
                reply.append(i + 1).append(". ").append(r.drugName())
                        .append(" | ").append(r.dosage())
                        .append(" | ").append(r.frequency());
                if (r.courseDays() > 0) {
                    reply.append(" | 共 ").append(r.courseDays()).append(" 天");
                } else {
                    reply.append(" | 长期服用");
                }
                if (r.notes() != null && !r.notes().isBlank()) {
                    reply.append(" | 备注：").append(r.notes());
                }
                reply.append("\n");
            }
            reply.append("\n").append(HealthDisclaimers.MEDICATION_DISCLAIMER);

            return ToolResult.ok(call.id(), call.name(), reply.toString());
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "查询用药记录失败，请稍后再试");
        }
    }
}
