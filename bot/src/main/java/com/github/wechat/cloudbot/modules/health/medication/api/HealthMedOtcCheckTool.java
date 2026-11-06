package com.github.wechat.cloudbot.modules.health.medication.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.common.HealthToolNames;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService.OtcInteraction;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具层：OTC 配伍冲突查询（health_med_otc_check）。
 *
 * <p>两种用法：传 drug_a + drug_b 查询两药是否有已知冲突；或只传 drug_a，
 * 自动与用户已记录的用药做比对。结果仅作提示，不构成医嘱。</p>
 */
@Component
public class HealthMedOtcCheckTool implements AgentTool {

    private final MedicationService medicationService;
    private final ObjectMapper objectMapper;

    public HealthMedOtcCheckTool(MedicationService medicationService, ObjectMapper objectMapper) {
        this.medicationService = medicationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                HealthToolNames.MED_OTC_CHECK,
                "当用户询问药品配伍、药物冲突、能否一起吃、和我在吃的药有没有冲突时，必须调用本工具。"
                        + "可传两个药名 drug_a + drug_b 两两查询；也可只传 drug_a，工具会与用户已记录的用药比对。"
                        + "结果仅作非医嘱提示。",
                """
                {"type":"object","properties":{
                  "drug_a":{"type":"string","description":"第一种药品名称，如「阿司匹林」"},
                  "drug_b":{"type":"string","description":"第二种药品名称（可选，与 drug_a 成对查询）"},
                  "user_id":{"type":"string","description":"用户ID（由编排层自动注入）"}
                },"required":[]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String drugA = ToolArgs.text(args, "drug_a");
            String drugB = ToolArgs.text(args, "drug_b");
            String userId = ToolArgs.resolveUserId(call, args);

            if (userId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "无法识别用户身份，请重试");
            }
            if (drugA.isBlank() && drugB.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "请输入至少一种药品名称，例如「阿司匹林」");
            }

            if (!drugA.isBlank() && !drugB.isBlank()) {
                return ToolResult.ok(call.id(), call.name(), checkPair(drugA, drugB));
            }

            String drug = drugA.isBlank() ? drugB : drugA;
            return ToolResult.ok(call.id(), call.name(), checkAgainstExisting(userId, drug));
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "OTC 冲突查询失败，请稍后再试");
        }
    }

    private String checkPair(String drugA, String drugB) {
        return medicationService.checkInteraction(drugA, drugB)
                .map(OtcInteraction::displayText)
                .orElse("「" + drugA + "」与「" + drugB + "」暂未发现已知的配伍冲突。\n\n"
                        + HealthDisclaimers.MEDICATION_DISCLAIMER);
    }

    private String checkAgainstExisting(String userId, String drug) {
        List<OtcInteraction> conflicts = medicationService.checkInteractionsWithExisting(userId, drug);
        if (conflicts.isEmpty()) {
            return "您当前已记录的用药与「" + drug + "」暂未发现已知的配伍冲突。\n\n"
                    + HealthDisclaimers.MEDICATION_DISCLAIMER;
        }
        StringBuilder sb = new StringBuilder("⚠️ OTC 配伍风险提示：\n");
        for (OtcInteraction conflict : conflicts) {
            sb.append("· ").append(conflict.displayText()).append("\n");
        }
        return sb.toString().trim();
    }
}
