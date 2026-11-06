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
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService;
import org.springframework.stereotype.Component;

/**
 * 工具层：删除用药记录（health_med_delete）。
 *
 * <p>通过 record_id 删除指定的用药记录，删除后不可恢复。
 */
@Component
public class HealthMedDeleteTool implements AgentTool {

    private final MedicationService medicationService;
    private final ObjectMapper objectMapper;

    public HealthMedDeleteTool(MedicationService medicationService, ObjectMapper objectMapper) {
        this.medicationService = medicationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                HealthToolNames.MED_DELETE,
                "删除已登记的用药记录。用户要求删除、取消某个药品的用药记录时必须调用本工具。",
                """
                {"type":"object","properties":{
                  "record_id":{"type":"string","description":"用药记录ID（通过 health_med_list 获取）"},
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

            if (recordId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "请指定要删除的用药记录ID");
            }
            if (userId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "无法识别用户身份，请重试");
            }

            boolean removed = medicationService.delete(userId, recordId);
            if (removed) {
                return ToolResult.ok(call.id(), call.name(),
                        "已删除该用药记录。" + HealthDisclaimers.MEDICATION_DISCLAIMER);
            } else {
                return ToolResult.fail(call.id(), call.name(), "未找到该用药记录，请检查ID是否正确");
            }
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "删除用药记录失败，请稍后再试");
        }
    }
}
