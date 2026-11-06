package com.github.wechat.cloudbot.modules.health.profile.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.profile.infra.PatientProfileEntity;
import com.github.wechat.cloudbot.modules.health.profile.service.ProfileService;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 健康档案的写入工具。
 *
 * <p>LLM 先通过该工具整理档案信息，再由用户确认后落库生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HealthProfileUpsertTool implements AgentTool {

    private final ProfileService profileService;
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition definition() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "relation": { "type": "string", "description": "就诊人关系，例如：本人、妈妈、爸爸" },
                    "name": { "type": "string", "description": "患者姓名或称呼" },
                    "gender": { "type": "string", "description": "性别，M/F 或 男/女", "enum": ["男", "女", "M", "F", "UNKNOWN"] },
                    "birthYear": { "type": "integer", "description": "出生年份，例如：1980" },
                    "allergies": { "type": "array", "items": { "type": "string" }, "description": "过敏史列表" },
                    "chronicDiseases": { "type": "array", "items": { "type": "string" }, "description": "慢性病史列表" },
                    "isConfirm": { "type": "boolean", "description": "是否确认保存。首次收集信息时必须传 false；当向用户确认无误后，传 true" }
                  },
                  "required": ["relation", "isConfirm"]
                }
                """;
        return new ToolDefinition(
                "health_profile_upsert",
                "新建或更新用户本人或家人的健康档案。必须先用 isConfirm=false 暂存，等用户确认后再用 isConfirm=true 生效。",
                schema);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            String wechatUserId = call.userId();
            if (wechatUserId == null || wechatUserId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "执行失败：缺少 userId");
            }

            String argsJson = call.argumentsJson() == null ? "{}" : call.argumentsJson();
            JsonNode args = objectMapper.readTree(argsJson);

            PatientProfileEntity input = new PatientProfileEntity();
            input.setRelation(args.path("relation").asText(null));
            input.setName(args.path("name").asText(null));
            input.setGender(args.path("gender").asText(null));

            if (args.hasNonNull("birthYear")) {
                input.setBirthYear(args.get("birthYear").asInt());
            }

            input.setAllergies(extractList(args.path("allergies")));
            input.setChronicDiseases(extractList(args.path("chronicDiseases")));

            boolean isConfirm = args.path("isConfirm").asBoolean(false);
            String replyMessage = profileService.handleUpsert(input, wechatUserId, isConfirm);
            return ToolResult.ok(call.id(), call.name(), replyMessage);
        } catch (Exception e) {
            log.error("解析或执行健康档案工具异常", e);
            return ToolResult.fail(call.id(), call.name(), "执行异常：" + e.getMessage());
        }
    }

    private List<String> extractList(JsonNode arrayNode) {
        if (arrayNode.isMissingNode() || !arrayNode.isArray()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        arrayNode.forEach(node -> list.add(node.asText()));
        return list.isEmpty() ? null : list;
    }
}