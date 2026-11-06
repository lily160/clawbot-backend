package com.github.wechat.cloudbot.modules.health.checkup.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.file.api.FileDeliveryService;
import com.github.wechat.cloudbot.modules.health.checkup.service.CheckupService;
import com.github.wechat.cloudbot.modules.health.checkup.service.CheckupService.CheckupContext;
import com.github.wechat.cloudbot.modules.health.checkup.service.CheckupService.CheckupPlan;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.health.common.HealthToolNames;
import com.github.wechat.cloudbot.modules.health.common.PatientRef;
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
 * 工具层：生成体检套餐计划（health_checkup_plan）。
 *
 * <p>根据年龄、性别、慢性病史、孕期状态及生活方式（BMI/吸烟/饮酒/家族史/作息）推荐体检项目清单。
 * age / gender / chronic_conditions 缺省时会自动读取用户健康档案填充（成员A模块，只读）。</p>
 */
@Component
public class HealthCheckupPlanTool implements AgentTool {

    private final CheckupService checkupService;
    private final ObjectMapper objectMapper;
    private final FileDeliveryService fileDeliveryService;
    private final ProfileService profileService;

    public HealthCheckupPlanTool(CheckupService checkupService, ObjectMapper objectMapper,
                                 FileDeliveryService fileDeliveryService, ProfileService profileService) {
        this.checkupService = checkupService;
        this.objectMapper = objectMapper;
        this.fileDeliveryService = fileDeliveryService;
        this.profileService = profileService;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                HealthToolNames.CHECKUP_PLAN,
                "当用户询问体检建议、体检套餐、体检项目、该做什么检查时必须调用本工具。"
                        + "根据年龄（age）、性别（gender）、慢性病史（chronic_conditions）、孕期状态（pregnancy_stage）"
                        + "以及可选的生活方式（bmi/smoker/drinker/family_history/lifestyle）推荐合适的体检项目清单。"
                        + "age/gender/chronic_conditions 可省略：用户已建健康档案时自动读取；未建档则需用户提供年龄。"
                        + "如果用户要求导出或保存为文件，将 export 设为 true。",
                """
                {"type":"object","properties":{
                  "age":{"type":"integer","description":"年龄，如25、45；已建档时可省略，自动从档案读取"},
                  "gender":{"type":"string","description":"性别：male（男）/ female（女）；已建档时可省略"},
                  "chronic_conditions":{"type":"array","items":{"type":"string"},"description":"慢性病史列表，如「高血压」「糖尿病」「高血脂」等，没有则留空"},
                  "pregnancy_stage":{"type":"string","description":"孕期状态：none=否 / prep=备孕 / pregnant=已怀孕","enum":["none","prep","pregnant"],"default":"none"},
                  "bmi":{"type":"number","description":"身体质量指数 BMI，如 26.5"},
                  "smoker":{"type":"boolean","description":"是否吸烟"},
                  "drinker":{"type":"boolean","description":"是否饮酒/嗜酒"},
                  "family_history":{"type":"array","items":{"type":"string"},"description":"家族病史列表，如「糖尿病」「高血压」「冠心病」「肿瘤」"},
                  "lifestyle":{"type":"array","items":{"type":"string","enum":["sedentary","outdoor","night_shift"]},"description":"生活方式：sedentary=久坐、outdoor=户外作业、night_shift=夜班"},
                  "relation":{"type":"string","description":"健康档案中的就诊人关系（如本人/母亲），用于定位档案"},
                  "export":{"type":"boolean","description":"是否生成文件供发送给用户","default":false},
                  "notes":{"type":"string","description":"用户其他需求备注"},
                  "user_id":{"type":"string","description":"用户ID（由编排层自动注入）"}
                },"required":[]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            int age = ToolArgs.intValue(args, "age", -1);
            String gender = ToolArgs.text(args, "gender");
            String pregnancyStage = args.path("pregnancy_stage").asText("none");
            boolean export = args.path("export").asBoolean(false);
            String relation = ToolArgs.text(args, "relation");
            String userId = ToolArgs.resolveUserId(call, args);

            List<String> chronicConditions = parseStringArray(args, "chronic_conditions");
            Double bmi = args.path("bmi").isNumber() ? args.path("bmi").asDouble() : null;
            Boolean smoker = args.path("smoker").isBoolean() ? args.path("smoker").asBoolean() : null;
            Boolean drinker = args.path("drinker").isBoolean() ? args.path("drinker").asBoolean() : null;
            List<String> familyHistory = parseStringArray(args, "family_history");
            List<String> lifestyle = parseStringArray(args, "lifestyle");

            // 档案自动填充：显式入参优先，缺失字段从已生效档案补齐
            if (age < 0 || gender.isBlank() || chronicConditions.isEmpty()) {
                PatientRef profile = findProfile(userId, relation);
                if (profile != null) {
                    if (age < 0 && profile.age() != null) {
                        age = profile.age();
                    }
                    if (gender.isBlank()) {
                        gender = normalizeGender(profile.gender());
                    }
                    if (chronicConditions.isEmpty()) {
                        chronicConditions = new ArrayList<>(profile.chronicConditionSummary());
                    }
                } else if (relation != null && !relation.isBlank() && hasProfiles(userId)) {
                    // 指定了就诊人关系但无匹配档案：明确报错，而不是静默用错误就诊人生成计划
                    return ToolResult.fail(call.id(), call.name(),
                            "未找到「" + relation + "」关系的健康档案，请确认就诊人关系是否正确。");
                }
            }

            if (age < 0 || age > 150) {
                return ToolResult.fail(call.id(), call.name(),
                        "请输入有效年龄；也可以先建立健康档案，我来自动读取年龄信息。");
            }

            CheckupPlan plan = checkupService.generatePlan(age, gender, chronicConditions, pregnancyStage,
                    new CheckupContext(bmi, smoker, drinker, familyHistory, lifestyle));
            String planText = plan.displayText();

            if (export && fileDeliveryService.isEnabled()) {
                // 性别未知时标注"未知"，不默认推断为"女"
                String genderLabel = "male".equals(gender) || "男".equals(gender) ? "男"
                        : "female".equals(gender) || "女".equals(gender) ? "女" : "未知";

                String fileContent = planText
                        + "\n================================\n"
                        + "生成时间：自动生成\n"
                        + "适用年龄：" + age + " 岁\n"
                        + "性别：" + genderLabel
                        + "\n" + HealthDisclaimers.MEDICAL_DISCLAIMER;

                String fileName = "体检建议_" + age + "岁"
                        + ("未知".equals(genderLabel) ? "" : "_" + genderLabel) + ".txt";

                fileDeliveryService.buildSendFile(
                        "{\"file_name\":\"" + escapeJson(fileName) + "\",\"content\":\"" + escapeJson(fileContent) + "\"}");

                String reply = "📄 已为您生成体检计划文件。\n\n" + planText + "\n" + HealthDisclaimers.MEDICAL_DISCLAIMER;
                return ToolResult.ok(call.id(), call.name(), reply);
            }

            String reply = planText + "\n" + HealthDisclaimers.MEDICAL_DISCLAIMER;
            return ToolResult.ok(call.id(), call.name(), reply);
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "生成体检计划失败，请稍后再试");
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

    private PatientRef findProfile(String userId, String relation) {
        if (profileService == null || userId == null || userId.isBlank()) {
            return null;
        }
        List<PatientRef> profiles = profileService.getProfiles(userId);
        if (profiles == null || profiles.isEmpty()) {
            return null;
        }
        if (relation != null && !relation.isBlank()) {
            // 指定了关系但无匹配 → 返回 null（不静默回退到第一条，避免用错误就诊人的年龄/性别/病史生成体检计划）
            return profiles.stream()
                    .filter(p -> relation.equals(p.relation()))
                    .findFirst()
                    .orElse(null);
        }
        return profiles.get(0);
    }

    /** 该用户是否已建过至少一条生效档案。 */
    private boolean hasProfiles(String userId) {
        if (profileService == null || userId == null || userId.isBlank()) {
            return false;
        }
        List<PatientRef> profiles = profileService.getProfiles(userId);
        return profiles != null && !profiles.isEmpty();
    }

    /** 档案性别归一化：M/F/男/女 → male/female。 */
    private static String normalizeGender(String gender) {
        if (gender == null || gender.isBlank()) {
            return "";
        }
        String g = gender.trim();
        if ("M".equalsIgnoreCase(g) || "男".equals(g)) {
            return "male";
        }
        if ("F".equalsIgnoreCase(g) || "女".equals(g)) {
            return "female";
        }
        return g.toLowerCase();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
