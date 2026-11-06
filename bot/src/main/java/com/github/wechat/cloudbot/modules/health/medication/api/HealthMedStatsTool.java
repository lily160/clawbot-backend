package com.github.wechat.cloudbot.modules.health.medication.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.common.HealthToolNames;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 工具层：查看用药统计（health_med_stats）。
 *
 * <p>返回当前用户的用药总条数、频次分布、近期待停药记录。
 */
@Component
public class HealthMedStatsTool implements AgentTool {

    private final MedicationService medicationService;
    private final ObjectMapper objectMapper;

    public HealthMedStatsTool(MedicationService medicationService, ObjectMapper objectMapper) {
        this.medicationService = medicationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                HealthToolNames.MED_STATS,
                "查看当前用户的用药统计信息：总条数、频次分布、近期待停药记录。",
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
                return ToolResult.fail(call.id(), call.name(), "无法识别用户身份");
            }

            var stats = medicationService.getStats(userId);
            StringBuilder reply = new StringBuilder("📊 用药统计数据\n");
            reply.append("总记录数：").append(stats.totalCount()).append(" 条\n");
            if (stats.frequencyDistribution() != null && !stats.frequencyDistribution().isEmpty()) {
                reply.append("\n频次分布：\n");
                stats.frequencyDistribution().forEach((freq, count) ->
                        reply.append("· ").append(freq).append("：").append(count).append(" 种\n"));
            }
            if (!stats.nearCompletion().isEmpty()) {
                reply.append("\n⏳ 疗程中：\n");
                stats.nearCompletion().forEach(n -> reply.append("· ").append(n).append("\n"));
            }
            if (!stats.todayExpiring().isEmpty()) {
                reply.append("\n⏳ 今日到期：\n");
                stats.todayExpiring().forEach(n -> reply.append("· ").append(n).append("\n"));
            }
            reply.append("\n").append(HealthDisclaimers.MEDICATION_DISCLAIMER);
            return ToolResult.ok(call.id(), call.name(), reply.toString());
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "查询用药统计失败，请稍后再试");
        }
    }
}
