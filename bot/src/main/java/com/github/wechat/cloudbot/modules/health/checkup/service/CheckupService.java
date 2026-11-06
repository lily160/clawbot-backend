package com.github.wechat.cloudbot.modules.health.checkup.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.checkup.infra.CheckupProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 体检套餐模板服务：根据年龄、性别、慢性病史生成推荐检查项目清单。
 */
@Component
public class CheckupService {

    private static final Logger log = LoggerFactory.getLogger(CheckupService.class);

    private final ObjectMapper objectMapper;
    private final CheckupProperties checkupProperties;

    /** 所有体检套餐分类模板。 */
    private List<CheckupCategory> categories = List.of();

    public CheckupService(CheckupProperties checkupProperties) {
        this.objectMapper = new ObjectMapper();
        this.checkupProperties = checkupProperties;
    }

    @PostConstruct
    public void init() {
        loadTemplates();
    }

    void loadTemplates() {
        String path = checkupProperties.getTemplatesDataPath();
        try (InputStream is = new ClassPathResource(path).getInputStream()) {
            TemplatesWrapper wrapper = objectMapper.readValue(is, TemplatesWrapper.class);
            if (wrapper != null && wrapper.categories != null && !wrapper.categories.isEmpty()) {
                this.categories = Collections.unmodifiableList(wrapper.categories);
                log.info("已加载体检模板 {} 个分类, path={}", categories.size(), path);
            }
        } catch (IOException e) {
            log.warn("加载体检模板失败: {}", e.getMessage());
        }
    }

    /**
     * 根据年龄、性别、慢性病史生成体检套餐清单（不附加生活方式等扩展条件）。
     */
    public CheckupPlan generatePlan(int age, String gender, List<String> chronicConditions, String pregnancyStage) {
        return generatePlan(age, gender, chronicConditions, pregnancyStage, CheckupContext.EMPTY);
    }

    /**
     * 根据年龄、性别、慢性病史及生活方式上下文生成体检套餐清单。
     *
     * @param age                年龄
     * @param gender             性别（可选：male/female）
     * @param chronicConditions  慢性病史列表（可选，如["高血压","糖尿病"]）
     * @param pregnancyStage     孕期状态：none=否 / prep=备孕 / pregnant=已怀孕
     * @param context            扩展条件（BMI/吸烟/饮酒/家族史/生活方式）
     * @return 体检套餐计划
     */
    public CheckupPlan generatePlan(int age, String gender, List<String> chronicConditions, String pregnancyStage,
                                    CheckupContext context) {
        if (gender == null) gender = "";
        if (chronicConditions == null) chronicConditions = List.of();
        String normalizedGender = gender.trim().toLowerCase();
        String stage = normalizeStage(pregnancyStage);

        // 优先匹配孕期
        CheckupCategory matched = null;
        if ("prep".equals(stage) || "pregnant".equals(stage)
                || (!normalizedGender.isEmpty() && normalizedGender.contains("孕"))
                || "pregnancy".equalsIgnoreCase(normalizedGender)) {
            matched = findCategory("pregnancy");
        }

        // 按年龄段匹配
        if (matched == null) {
            matched = categories.stream()
                    .filter(c -> !"pregnancy".equals(c.key()))
                    .filter(c -> age >= c.minAge() && age <= c.maxAge())
                    .findFirst()
                    .orElse(null);
        }

        // 无匹配则用青年套餐作为通用兜底
        if (matched == null) {
            matched = findCategory("young_adult");
        }

        if (matched == null) {
            return new CheckupPlan("通用体检建议", List.of(
                    "一般检查（身高、体重、BMI、血压）",
                    "血常规",
                    "尿常规",
                    "肝功能",
                    "肾功能",
                    "空腹血糖",
                    "心电图"
            ), chronicConditions);
        }

        // 组装检查项（defaultItems 缺失时兜底为空，避免 NPE）
        List<String> items = new ArrayList<>(matched.defaultItems() == null ? List.of() : matched.defaultItems());
        if (matched.conditionalItems() != null) {
            for (ConditionalItem ci : matched.conditionalItems()) {
                if (matchesCondition(ci.condition(), age, normalizedGender, chronicConditions, stage, context)) {
                    if (ci.items() != null) {
                        items.addAll(ci.items());
                    }
                    if (ci.extraItems() != null && !ci.extraItems().isBlank()) {
                        items.add(ci.extraItems());
                    }
                }
            }
        }
        // 多个条件同时命中可能追加相同检查项（如高血压/糖尿病都含眼底检查），按序去重
        items = new ArrayList<>(new LinkedHashSet<>(items));

        return new CheckupPlan(matched.label(), items, chronicConditions);
    }

    private CheckupCategory findCategory(String key) {
        return categories.stream()
                .filter(c -> key.equals(c.key()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 匹配单一条件表达式。支持格式：
     * <ul>
     *   <li>age&gt;=60、age&gt;=45</li>
     *   <li>gender==male、gender==female、性别为男、性别为女</li>
     *   <li>chronic=高血压、chronic=高血压||糖尿病</li>
     *   <li>bmi&gt;=28、bmi&lt;18.5（bmi&gt;=24&amp;&amp;bmi&lt;28 表示区间）</li>
     *   <li>smoker==true/false、drinker==true/false（含中文 吸烟/饮酒/不吸烟/不饮酒）</li>
     *   <li>family=糖尿病（家族史，支持 ||）、lifestyle=sedentary/outdoor/night_shift</li>
     *   <li>孕期状态：已确认怀孕、备孕期</li>
     *   <li>兼容旧格式：慢性病史包含{X}、年龄≥{N}、性别为男且年龄≥{N}</li>
     * </ul>
     * 多个条件用 &amp;&amp; 连接（AND 语义）。
     */
    private boolean matchesCondition(String condition, int age, String gender, List<String> chronicConditions,
                                     String pregnancyStage, CheckupContext context) {
        if (condition == null || condition.isBlank()) return false;

        // 支持 && 组合条件
        String[] parts = condition.split("&&");
        for (String part : parts) {
            String expr = part.trim();
            if (!evaluateSingleCondition(expr, age, gender, chronicConditions, pregnancyStage, context)) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateSingleCondition(String expr, int age, String gender, List<String> chronicConditions,
                                            String pregnancyStage, CheckupContext context) {
        // 年龄条件：age>=60
        if (expr.matches("age>=\\d+")) {
            int threshold = Integer.parseInt(expr.replaceAll("\\D+", ""));
            return age >= threshold;
        }

        // BMI 条件：bmi>=28 / bmi<18.5
        if (expr.startsWith("bmi>=")) {
            double threshold = Double.parseDouble(expr.substring("bmi>=".length()).trim());
            return context.bmi() != null && context.bmi() >= threshold;
        }
        if (expr.startsWith("bmi<")) {
            double threshold = Double.parseDouble(expr.substring("bmi<".length()).trim());
            return context.bmi() != null && context.bmi() < threshold;
        }

        // 吸烟 / 饮酒
        if ("smoker==true".equals(expr) || "吸烟".equals(expr)) {
            return Boolean.TRUE.equals(context.smoker());
        }
        if ("smoker==false".equals(expr) || "不吸烟".equals(expr)) {
            return !Boolean.TRUE.equals(context.smoker());
        }
        if ("drinker==true".equals(expr) || "饮酒".equals(expr)) {
            return Boolean.TRUE.equals(context.drinker());
        }
        if ("drinker==false".equals(expr) || "不饮酒".equals(expr)) {
            return !Boolean.TRUE.equals(context.drinker());
        }

        // 家族史：family=糖尿病 或 family=糖尿病||高血压
        if (expr.startsWith("family=")) {
            String fam = expr.substring("family=".length()).trim();
            String[] families = fam.split("\\|\\|");
            for (String f : families) {
                String trimmed = f.trim();
                if (!trimmed.isEmpty() && context.familyHistory().stream().anyMatch(h -> h.contains(trimmed))) {
                    return true;
                }
            }
            return false;
        }

        // 生活方式：lifestyle=sedentary / outdoor / night_shift
        if (expr.startsWith("lifestyle=")) {
            String tag = expr.substring("lifestyle=".length()).trim();
            return context.lifestyle().stream().anyMatch(l -> l.equalsIgnoreCase(tag));
        }

        // 性别条件
        if ("gender==male".equals(expr) || "性别为男".equals(expr)) {
            return "male".equals(gender) || "男".equals(gender);
        }
        if ("gender==female".equals(expr) || "性别为女".equals(expr)) {
            return "female".equals(gender) || "女".equals(gender);
        }

        // 慢性病史条件：chronic=高血压 或 chronic=高血压||糖尿病
        if (expr.startsWith("chronic=")) {
            String disease = expr.substring("chronic=".length()).trim();
            String[] diseases = disease.split("\\|\\|");
            for (String d : diseases) {
                String trimmed = d.trim();
                if (!trimmed.isEmpty() && chronicConditions.stream().anyMatch(c -> c.contains(trimmed))) {
                    return true;
                }
            }
            return false;
        }

        // 兼容旧格式：性别为男且年龄≥{N}（必须在年龄≥之前检查）
        if (expr.contains("性别为男且年龄≥")) {
            String num = expr.replaceAll("\\D+", "");
            boolean isMale = "male".equals(gender) || "男".equals(gender);
            return isMale && !num.isEmpty() && age >= Integer.parseInt(num);
        }

        // 兼容旧格式：慢性病史包含{X}（或{Y}）——每侧同时匹配个人慢性史 OR 家族史，
        // 修复原实现用正则吞掉"或"之后的内容导致"家族高血压史"永远不参与匹配的问题
        if (expr.contains("慢性病史包含")) {
            String inside = expr.replaceAll("^.*?包含", "").replaceAll("（.*", "").trim();
            if (inside.isBlank()) {
                return false;
            }
            List<String> family = context == null || context.familyHistory() == null
                    ? List.of()
                    : context.familyHistory();
            for (String p : inside.split("或")) {
                String trimmed = p.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (chronicConditions.stream().anyMatch(c -> c.contains(trimmed))
                        || family.stream().anyMatch(f -> f.contains(trimmed))) {
                    return true;
                }
            }
            return false;
        }

        // 兼容旧格式：年龄≥{N}
        if (expr.contains("年龄≥")) {
            String num = expr.replaceAll("\\D+", "");
            if (!num.isEmpty()) {
                return age >= Integer.parseInt(num);
            }
        }

        // 孕期状态条件
        if (expr.contains("已确认怀孕")) {
            return "pregnant".equals(pregnancyStage);
        }
        if (expr.contains("备孕期")) {
            return "prep".equals(pregnancyStage);
        }

        return false;
    }

    /**
     * 将孕期状态入参归一化：none=否 / prep=备孕 / pregnant=已怀孕。
     */
    private static String normalizeStage(String pregnancyStage) {
        if (pregnancyStage == null || pregnancyStage.isBlank()) {
            return "none";
        }
        String s = pregnancyStage.trim().toLowerCase();
        return switch (s) {
            case "pregnant", "孕", "怀孕", "已怀孕", "孕期" -> "pregnant";
            case "prep", "备孕", "备孕期" -> "prep";
            default -> "none";
        };
    }

    // ==================== 内部类型 ====================

    /**
     * 体检生成的可选扩展条件（生活方式相关）。
     *
     * @param bmi           身体质量指数（如 26.5，支持 bmi&gt;= / bmi&lt; 条件）
     * @param smoker        是否吸烟
     * @param drinker       是否饮酒
     * @param familyHistory 家族病史列表（如 ["糖尿病","高血压"]，支持 family= 条件）
     * @param lifestyle     生活方式标签（sedentary=久坐 / outdoor=户外 / night_shift=夜班）
     */
    public record CheckupContext(
            Double bmi,
            Boolean smoker,
            Boolean drinker,
            List<String> familyHistory,
            List<String> lifestyle) {

        /** 无扩展条件时的默认上下文。 */
        public static final CheckupContext EMPTY = new CheckupContext(null, null, null, List.of(), List.of());

        public CheckupContext {
            familyHistory = familyHistory == null ? List.of() : List.copyOf(familyHistory);
            lifestyle = lifestyle == null ? List.of() : List.copyOf(lifestyle);
        }
    }

    public record CheckupPlan(String planName, List<String> items, List<String> chronicConditions) {
        public String displayText() {
            StringBuilder sb = new StringBuilder();
            sb.append("🏥 ").append(planName).append("\n\n");
            sb.append("推荐检查项目：\n");
            for (int i = 0; i < items.size(); i++) {
                sb.append(i + 1).append(". ").append(items.get(i)).append("\n");
            }
            if (chronicConditions != null && !chronicConditions.isEmpty()) {
                sb.append("\n📋 已考虑您的慢性病史：");
                sb.append(String.join("、", chronicConditions));
                sb.append("\n");
            }
            return sb.toString();
        }
    }

    private record TemplatesWrapper(List<CheckupCategory> categories) {}

    private record CheckupCategory(
            String key, String label, String description,
            int minAge, int maxAge,
            List<String> defaultItems,
            List<ConditionalItem> conditionalItems) {}

    private record ConditionalItem(
            String condition,
            List<String> items,
            String extraItems) {}
}
