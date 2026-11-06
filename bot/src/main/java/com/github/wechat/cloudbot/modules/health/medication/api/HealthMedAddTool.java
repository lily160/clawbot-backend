package com.github.wechat.cloudbot.modules.health.medication.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.common.HealthToolNames;
import com.github.wechat.cloudbot.modules.health.common.PatientRef;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService.AllergyWarning;
import com.github.wechat.cloudbot.modules.health.medication.service.MedicationService.OtcInteraction;
import com.github.wechat.cloudbot.modules.health.profile.service.ProfileService;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具层：添加用药记录（health_med_add）。
 *
 * <p>记录药名/用量/频次/疗程，自动注册定时提醒；检测 OTC 配伍冲突，
 * 并读取成员A 健康档案中的过敏史给出过敏风险提示（档案缺失/读取失败时优雅降级）。
 */
@Component
public class HealthMedAddTool implements AgentTool {

    private final MedicationService medicationService;
    private final ProfileService profileService;
    private final ObjectMapper objectMapper;

    public HealthMedAddTool(MedicationService medicationService,
                            ProfileService profileService,
                            ObjectMapper objectMapper) {
        this.medicationService = medicationService;
        this.profileService = profileService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                HealthToolNames.MED_ADD,
                "当用户要求记录、添加用药、登记药品时必须调用本工具。"
                        + "记录药名（drug_name）、每次用量（dosage）、频次（frequency）、疗程天数（course_days）。"
                        + "会自动注册每日提醒并检测与已有药品的 OTC 配伍冲突。",
                """
                {"type":"object","properties":{
                  "drug_name":{"type":"string","description":"药品名称，如「阿司匹林肠溶片」"},
                  "dosage":{"type":"string","description":"每次用量，如「1片」「10ml」"},
                  "frequency":{"type":"string","description":"用药频次，可选：每日一次、每日两次、每日三次、隔日一次、每周一次、每晚一次"},
                  "course_days":{"type":"integer","description":"疗程天数，0表示长期服用","default":0},
                  "reminder_times":{"type":"array","items":{"type":"string"},"description":"自定义每日提醒时间（可选，24小时制 HH:mm，数量需与频次对应，如每日两次传 [\\\"08:00\\\",\\\"20:00\\\"]）"},
                  "notes":{"type":"string","description":"备注（可选）"},
                  "user_id":{"type":"string","description":"用户ID（由编排层自动注入）"}
                },"required":["drug_name","dosage","frequency"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String drugName = ToolArgs.text(args, "drug_name");
            String dosage = ToolArgs.text(args, "dosage");
            String frequency = ToolArgs.text(args, "frequency");
            int courseDays = ToolArgs.intValue(args, "course_days", 0);
            String notes = ToolArgs.text(args, "notes");
            List<String> reminderTimes = parseStringArray(args, "reminder_times");
            String userId = ToolArgs.resolveUserId(call, args);

            if (drugName.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "药品名称不能为空");
            }
            if (dosage.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "用量不能为空");
            }
            if (frequency.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "用药频次不能为空");
            }
            if (userId.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "无法识别用户身份，请重试");
            }

            // 检测与已有药品的冲突
            List<OtcInteraction> conflicts = medicationService.checkInteractionsWithExisting(userId, drugName);

            // 检测档案过敏史
            List<AllergyWarning> allergyWarnings = checkAllergies(userId, drugName);

            // 添加用药记录
            medicationService.add(userId, drugName, dosage, frequency, courseDays, notes, reminderTimes);

            // 拼装回复
            StringBuilder reply = new StringBuilder();
            reply.append("已记录用药：").append(drugName)
                    .append("，").append(dosage)
                    .append("，").append(frequency);
            if (courseDays > 0) {
                reply.append("，共 ").append(courseDays).append(" 天");
            }
            reply.append("。已设置每日提醒。\n");

            // 过敏风险优先提示
            if (!allergyWarnings.isEmpty()) {
                reply.append("\n⚠️ 过敏风险提示：\n");
                for (AllergyWarning warning : allergyWarnings) {
                    reply.append("· ").append(warning.displayText()).append("\n");
                }
            }

            // 如果有冲突，添加警告
            if (!conflicts.isEmpty()) {
                reply.append("\n⚠️ OTC 配伍风险提示：\n");
                for (OtcInteraction conflict : conflicts) {
                    reply.append("· ").append(conflict.displayText()).append("\n");
                }
            }

            reply.append("\n").append(HealthDisclaimers.MEDICATION_DISCLAIMER);

            return ToolResult.ok(call.id(), call.name(), reply.toString());
        } catch (IllegalArgumentException | IllegalStateException e) {
            String msg = e.getMessage() == null ? "添加用药记录失败" : e.getMessage();
            return ToolResult.fail(call.id(), call.name(), msg);
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "添加用药记录失败，请稍后再试");
        }
    }

    /**
     * 汇总该用户全部档案中的过敏史并检测新药是否命中。
     * 档案未建或读取失败时不阻断录药。
     */
    private List<AllergyWarning> checkAllergies(String userId, String drugName) {
        if (profileService == null || userId == null || userId.isBlank()) {
            return List.of();
        }
        try {
            List<PatientRef> profiles = profileService.getProfiles(userId);
            if (profiles == null || profiles.isEmpty()) {
                return List.of();
            }
            List<String> allergies = new ArrayList<>();
            for (PatientRef profile : profiles) {
                if (profile.allergySummary() != null) {
                    allergies.addAll(profile.allergySummary());
                }
            }
            return allergies.isEmpty() ? List.of() : medicationService.checkAllergies(allergies, drugName);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> parseStringArray(JsonNode args, String field) {
        List<String> result = new ArrayList<>();
        JsonNode node = args.path(field);
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    result.add(item.asText().trim());
                }
            }
        }
        return result;
    }
}
