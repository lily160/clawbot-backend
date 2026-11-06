package com.github.wechat.cloudbot.modules.health.report.service;

import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对比本次与上次报告的指标变化，产出趋势分析和分级复查建议。
 *
 * <p>复查建议按紧急程度分为三级：
 * <ul>
 *   <li>🔴 紧急 — 指标显著偏离或持续恶化，建议尽快就医</li>
 *   <li>🟡 关注 — 指标轻度偏离或新增异常，建议近期复查</li>
 *   <li>🟢 观察 — 指标临界或轻微波动，建议生活方式调整后复查</li>
 * </ul>
 */
@Component
public class ReportTrendAnalyzer {

    public record TrendItem(String name, String currentValue, String previousValue,
                            String unit, String referenceRange, String direction) {}

    /**
     * 分级复查建议。
     *
     * @param urgency      紧急程度：urgent / attention / observe
     * @param items        具体建议条目
     * @param hasNewAbnormal 是否有新增/加重的异常项
     */
    public record FollowUpSuggestions(
            String urgency,
            List<String> items,
            boolean hasNewAbnormal) {}

    /**
     * 异常跟进计划：某项指标从发现到复查的完整跟踪链路。
     */
    public record FollowUpPlan(
            String indicatorName,
            String currentValue,
            String targetValue,
            String suggestedDepartment,
            String suggestedTimeline,
            String urgency,
            List<String> actionItems) {}

    // ── 趋势分析 ──

    /**
     * 对比本次指标与上次，返回趋势列表。
     */
    public List<TrendItem> analyze(List<HealthIndicator> current, Map<String, HealthIndicator> previous) {
        List<TrendItem> trends = new ArrayList<>();
        if (previous == null || previous.isEmpty()) return trends;

        for (HealthIndicator cur : current) {
            HealthIndicator prev = previous.get(cur.name());
            if (prev == null) continue;

            String dir = null;
            if (cur.status() == IndicatorStatus.NORMAL && prev.status() != IndicatorStatus.NORMAL) {
                dir = "好转";
            } else if (cur.status() != IndicatorStatus.NORMAL && prev.status() == IndicatorStatus.NORMAL) {
                dir = "新增异常";
            } else if (isWorse(cur.status(), prev.status())) {
                dir = "加重";
            } else if (isBetter(cur.status(), prev.status())) {
                dir = "改善";
            }

            // 仅当状态实际发生变化（好转/新增异常/加重/改善）时才产出趋势项；
            // 两次都异常但状态未变的不进入「与上次对比」，避免误导用户以为指标变化了。
            if (dir != null) {
                trends.add(new TrendItem(cur.name(), cur.value() + blankUnit(cur.unit()),
                        prev.value() + blankUnit(prev.unit()),
                        cur.unit(), cur.referenceRange(), dir));
            }
        }
        return trends;
    }

    // ── 分级复查建议 ──

    /**
     * 生成分级复查建议，按紧急程度组织。
     */
    public FollowUpSuggestions suggest(List<HealthIndicator> abnormal, List<TrendItem> trends) {
        List<String> urgent = new ArrayList<>();
        List<String> attention = new ArrayList<>();
        List<String> observe = new ArrayList<>();
        boolean hasNew = false;
        boolean hasWorsened = false;

        // 1. 对每个异常指标（跳过UNKNOWN）生成分级建议
        for (HealthIndicator ind : abnormal) {
            if (ind.status() == IndicatorStatus.UNKNOWN) continue;
            SeverityLevel level = assessSeverity(ind, trends);
            String advice = buildAdvice(ind, level);

            switch (level) {
                case URGENT -> urgent.add(advice);
                case ATTENTION -> attention.add(advice);
                case OBSERVE -> observe.add(advice);
            }
        }

        // 2. 趋势中检测新增/加重
        for (TrendItem t : trends) {
            if ("新增异常".equals(t.direction())) {
                hasNew = true;
            }
            if ("加重".equals(t.direction())) {
                hasWorsened = true;
            }
        }

        // 3. 组装最终建议列表（紧急优先）
        List<String> items = new ArrayList<>();

        if (!urgent.isEmpty()) {
            items.add("── 🔴 紧急关注 ──");
            items.addAll(urgent);
        }
        if (!attention.isEmpty()) {
            if (!items.isEmpty()) items.add("");
            items.add("── 🟡 建议关注 ──");
            items.addAll(attention);
        }
        if (!observe.isEmpty()) {
            if (!items.isEmpty()) items.add("");
            items.add("── 🟢 持续观察 ──");
            items.addAll(observe);
        }

        // 4. 通用建议
        if (!abnormal.isEmpty()) {
            items.add("");
            items.add("📋 通用建议");
            items.add("建议携带本报告于 1-2 周内到相应科室复查");
            items.add("回复「设置复查提醒」可预约复查通知，避免遗忘");
            if (hasNew || hasWorsened) {
                items.add("本次有新增或加重的异常项，建议优先处理");
            }
        }

        // 确定总体紧急程度
        String overallUrgency;
        if (!urgent.isEmpty()) {
            overallUrgency = "urgent";
        } else if (!attention.isEmpty() || hasNew || hasWorsened) {
            overallUrgency = "attention";
        } else {
            overallUrgency = "observe";
        }

        return new FollowUpSuggestions(overallUrgency, items, hasNew || hasWorsened);
    }

    // ── 异常跟进计划 ──

    /**
     * 为异常指标生成具体的跟进计划。
     */
    public List<FollowUpPlan> buildFollowUpPlans(List<HealthIndicator> abnormal,
                                                  List<TrendItem> trends) {
        List<FollowUpPlan> plans = new ArrayList<>();
        Map<String, String> trendMap = new HashMap<>();
        if (trends != null) {
            for (TrendItem t : trends) {
                trendMap.put(t.name(), t.direction());
            }
        }

        for (HealthIndicator ind : abnormal) {
            if (ind.status() == IndicatorStatus.UNKNOWN) continue;
            String trend = trendMap.getOrDefault(ind.name(), "首次发现");
            SeverityLevel level = assessSeverity(ind, trends);
            FollowUpPlan plan = buildPlan(ind, trend, level);
            plans.add(plan);
        }
        return plans;
    }

    // ── 严重程度评估 ──

    private enum SeverityLevel { URGENT, ATTENTION, OBSERVE }

    private SeverityLevel assessSeverity(HealthIndicator ind, List<TrendItem> trends) {
        String name = ind.name();

        // 关键指标：肌酐、转氨酶显著升高 → 紧急
        if (name.contains("肌酐") || name.contains("Creatinine")) {
            return isSignificantlyHigh(ind) ? SeverityLevel.URGENT : SeverityLevel.ATTENTION;
        }
        if (name.contains("转氨酶") || name.contains("ALT") || name.contains("AST")) {
            return isSignificantlyHigh(ind) ? SeverityLevel.URGENT : SeverityLevel.ATTENTION;
        }
        // 抗原阳性 → 紧急
        if (name.contains("抗原") || name.contains("HBsAg") || name.contains("阳性")) {
            return SeverityLevel.URGENT;
        }

        // 趋势判断：加重的 → 升级
        if (trends != null) {
            for (TrendItem t : trends) {
                if (t.name().equals(name) && "加重".equals(t.direction())) {
                    return SeverityLevel.ATTENTION;
                }
                if (t.name().equals(name) && "新增异常".equals(t.direction())) {
                    return SeverityLevel.ATTENTION;
                }
            }
        }

        // 血糖、血脂、尿酸 → 关注
        if (name.contains("血糖") || name.contains("GLU") || name.contains("Glucose")
                || name.contains("血脂") || name.contains("胆固醇") || name.contains("甘油三酯")
                || name.contains("尿酸") || name.contains("Uric")) {
            return SeverityLevel.ATTENTION;
        }

        // 血压 → 关注
        if (name.contains("血压") || name.contains("收缩压") || name.contains("舒张压")) {
            return SeverityLevel.ATTENTION;
        }

        // 其余 → 观察
        return SeverityLevel.OBSERVE;
    }

    private boolean isSignificantlyHigh(HealthIndicator ind) {
        if (ind.referenceRange().isBlank()) return false;
        try {
            double val = Double.parseDouble(ind.value());
            double[] bounds = parseRange(ind.referenceRange());
            if (Double.isNaN(bounds[1])) return false;
            // 超出上限 50% 视为显著升高
            return val > bounds[1] * 1.5;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private double[] parseRange(String range) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "([<>]?[+-]?\\d+(?:\\.\\d+)?)\\s*[-~–—至]\\s*([<>]?[+-]?\\d+(?:\\.\\d+)?)"
        ).matcher(range.replaceAll("\\s+", " ").trim());
        if (m.find()) {
            return new double[]{
                    parseDouble(m.group(1).replaceAll("^[<>]", "").trim()),
                    parseDouble(m.group(2).replaceAll("^[<>]", "").trim())
            };
        }
        return new double[]{Double.NaN, Double.NaN};
    }

    private double parseDouble(String value) {
        try { return Double.parseDouble(value); }
        catch (Exception e) { return Double.NaN; }
    }

    // ── 建议生成 ──

    private String buildAdvice(HealthIndicator ind, SeverityLevel level) {
        String name = ind.name();
        String suffix = "（当前 " + ind.value() + blankUnit(ind.unit())
                + "，参考 " + ind.referenceRange() + "）";
        return suggestAction(name, deviationLabel(ind.status())) + suffix;
    }

    private FollowUpPlan buildPlan(HealthIndicator ind, String trend, SeverityLevel level) {
        String name = ind.name();
        String dept = suggestDepartment(name);
        String timeline = suggestTimeline(name, level);
        List<String> actions = new ArrayList<>();
        actions.add("携带本报告到 " + dept + " 就诊");
        actions.add(suggestAction(name, deviationLabel(ind.status())));
        if (!trend.isBlank() && !"首次发现".equals(trend)) {
            actions.add("趋势：" + trend + "，建议跟踪变化");
        }
        actions.add("回复「设置复查提醒 " + name + "」可预约通知");

        return new FollowUpPlan(
                name,
                ind.value() + blankUnit(ind.unit()),
                ind.referenceRange(),
                dept,
                timeline,
                level == SeverityLevel.URGENT ? "urgent"
                        : level == SeverityLevel.ATTENTION ? "attention" : "observe",
                actions);
    }

    // ── 指标→科室映射 ──

    private String suggestDepartment(String name) {
        if (name.contains("血糖") || name.contains("GLU") || name.contains("Glucose")
                || name.contains("甲状腺") || name.contains("TSH") || name.contains("T3") || name.contains("T4"))
            return "内分泌科";
        if (name.contains("血脂") || name.contains("胆固醇") || name.contains("甘油三酯")
                || name.contains("Triglyceride") || name.contains("Cholesterol") || name.contains("LDL")
                || name.contains("血压") || name.contains("收缩压") || name.contains("舒张压"))
            return "心血管内科";
        if (name.contains("肌酐") || name.contains("Creatinine")
                || name.contains("尿素") || name.contains("BUN"))
            return "肾内科";
        if (name.contains("尿酸") || name.contains("UricAcid") || name.contains("Uric"))
            return "风湿免疫科/肾内科";
        if (name.contains("转氨酶") || name.contains("ALT") || name.contains("AST")
                || name.contains("胆红素") || name.contains("Bilirubin")
                || name.contains("蛋白") || name.contains("Protein")
                || name.contains("抗原") || name.contains("HBsAg"))
            return "肝病科/消化内科";
        if (name.contains("白细胞") || name.contains("WBC")
                || name.contains("红细胞") || name.contains("RBC")
                || name.contains("血红蛋白") || name.contains("Hemoglobin")
                || name.contains("血小板") || name.contains("Platelet"))
            return "血液科/内科";
        if (name.contains("肿瘤") || name.contains("AFP") || name.contains("CEA")
                || name.contains("CA") || name.contains("PSA"))
            return "肿瘤科";
        return "内科/全科";
    }

    private String suggestTimeline(String name, SeverityLevel level) {
        if (level == SeverityLevel.URGENT) return "建议 3-7 天内就诊";
        if (level == SeverityLevel.ATTENTION) return "建议 1-2 周内复查";
        return "建议 1-3 个月内复查";
    }

    private String suggestAction(String name, String type) {
        if (name.contains("血糖") || name.contains("GLU") || name.contains("Glucose"))
            return "内分泌科复查，控制精制碳水摄入，监测空腹及餐后血糖，每周至少运动3次";
        if (name.contains("血脂") || name.contains("胆固醇") || name.contains("甘油三酯")
                || name.contains("Triglyceride") || name.contains("Cholesterol") || name.contains("LDL"))
            return "低脂低胆固醇饮食，每周≥150分钟有氧运动，1个月后复查血脂四项";
        if (name.contains("蛋白") || name.contains("Protein"))
            return "加强优质蛋白摄入（鱼、蛋、豆制品），1个月后复查肝功能及蛋白电泳";
        if (name.contains("尿酸") || name.contains("UricAcid") || name.contains("Uric"))
            return "低嘌呤饮食（避免内脏、海鲜、啤酒），每日饮水≥2000ml，1个月后复查";
        if (name.contains("转氨酶") || name.contains("ALT") || name.contains("AST"))
            return "充分休息，戒酒，避免伤肝药物及保健品，2周后复查肝功能";
        if (name.contains("肌酐") || name.contains("Creatinine"))
            return "肾内科就诊，检查尿常规及肾脏B超，监测血压，避免肾毒性药物";
        if (name.contains("血压") || name.contains("收缩压") || name.contains("舒张压"))
            return "心血管内科就诊，每日早晚测血压并记录，低盐饮食（<5g/天），规律作息";
        if (name.contains("抗原") || name.contains("HBsAg") || name.contains("阳性"))
            return "感染科/肝病科进一步检查乙肝五项、HBV-DNA及肝脏B超";
        if (name.contains("血红蛋白") || name.contains("Hemoglobin")
                || name.contains("红细胞") || name.contains("RBC"))
            return "血液科就诊，查铁蛋白、叶酸、维生素B12，明确贫血原因";
        if (name.contains("白细胞") || name.contains("WBC"))
            return "结合临床症状判断，如持续异常建议血液科就诊";
        if (name.contains("血小板") || name.contains("Platelet"))
            return "血液科就诊，检查凝血功能，避免磕碰及使用影响血小板的药物";
        if (name.contains("TSH") || name.contains("甲状腺"))
            return "内分泌科就诊，检查甲状腺功能全套及甲状腺B超";
        if (name.contains("肿瘤") || name.contains("AFP") || name.contains("CEA"))
            return "肿瘤科/消化内科就诊，结合影像学进一步排查";
        return "相应科室就诊，定期复查，跟踪指标变化";
    }

    // ── 趋势判断工具方法 ──

    private boolean isWorse(IndicatorStatus cur, IndicatorStatus prev) {
        if (cur == IndicatorStatus.HIGH && prev == IndicatorStatus.NORMAL) return true;
        if (cur == IndicatorStatus.LOW && prev == IndicatorStatus.NORMAL) return true;
        if (cur == IndicatorStatus.ABNORMAL && prev == IndicatorStatus.NORMAL) return true;
        return false;
    }

    private boolean isBetter(IndicatorStatus cur, IndicatorStatus prev) {
        if (cur == IndicatorStatus.NORMAL
                && (prev == IndicatorStatus.HIGH || prev == IndicatorStatus.LOW || prev == IndicatorStatus.ABNORMAL))
            return true;
        return false;
    }

    private String statusLabel(IndicatorStatus s) {
        return switch (s) {
            case HIGH -> "偏高";
            case LOW -> "偏低";
            case ABNORMAL -> "异常";
            case NORMAL -> "正常";
            case UNKNOWN -> "—";
        };
    }

    private String deviationLabel(IndicatorStatus s) {
        return switch (s) {
            case HIGH -> "偏高";
            case LOW -> "偏低";
            case ABNORMAL -> "异常";
            default -> "偏离";
        };
    }

    private String blankUnit(String unit) {
        return unit == null || unit.isBlank() ? "" : " " + unit;
    }
}
