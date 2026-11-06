package com.github.wechat.cloudbot.modules.health.report.service;

import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 把结构化体检结果转成可直接发给用户或模型的安全文本。
 *
 * <p>输出包含统计摘要、偏离判断（异常项偏离方向与程度）、原始证据页码、
 * 趋势对比、复查建议、生活方式提示、免责声明。
 *
 * <p>两个入口：
 * <ul>
 *   <li>{@link #format(HealthReportParseResult)} — 基础版，用于 Function Calling 路径</li>
 *   <li>{@link #formatRich(HealthReportParseResult, List, ReportTrendAnalyzer.FollowUpSuggestions, boolean)}
 *       — 增强版，用于文件上传全链路，带趋势+建议+人性化引导</li>
 * </ul>
 */
@Component
public class ReportSafetyFormatter {

    // ── 基础版：Function Calling 路径 ──

    /**
     * 输出摘要、偏离判断、证据页码、免责声明和复查提示。
     */
    public String format(HealthReportParseResult result) {
        Objects.requireNonNull(result, "result");
        StringBuilder sb = new StringBuilder();
        sb.append("📋 体检报告解析\n\n");
        sb.append("📄 来源：").append(result.sourceFilename()).append('\n');
        sb.append("📑 页数：").append(result.pageCount()).append("页\n\n");

        appendStatistics(sb, result.indicators());
        sb.append('\n');
        appendIndicatorSummary(sb, result.indicators());
        sb.append('\n');
        appendAbnormalSection(sb, result.abnormalIndicators());
        sb.append('\n');
        sb.append("⚠️ 免责声明：").append(result.disclaimer()).append('\n');
        sb.append("💡 复查提示：").append(result.reviewPrompt());
        return sb.toString();
    }

    // ── 增强版：文件上传全链路，人性化响应 ──

    /**
     * 生成可直接发送给微信用户的人性化报告回复。
     *
     * @param result     解析结果
     * @param trends     与上次报告的对比趋势（无历史数据时传空列表）
     * @param followUp   复查建议（无异常项时传空列表）
     * @param hasHistory 是否有历史报告可对比
     */
    public String formatRich(HealthReportParseResult result,
                             List<ReportTrendAnalyzer.TrendItem> trends,
                             ReportTrendAnalyzer.FollowUpSuggestions followUp,
                             boolean hasHistory) {
        Objects.requireNonNull(result, "result");

        List<HealthIndicator> all = result.indicators();
        List<HealthIndicator> abnormal = result.abnormalIndicators();

        // 统计
        long normal = countByStatus(all, IndicatorStatus.NORMAL);
        long high = countByStatus(all, IndicatorStatus.HIGH);
        long low = countByStatus(all, IndicatorStatus.LOW);
        long abnormalCount = countByStatus(all, IndicatorStatus.ABNORMAL);
        long unknown = countByStatus(all, IndicatorStatus.UNKNOWN);
        long deviated = high + low + abnormalCount;

        StringBuilder sb = new StringBuilder();

        // ── 头部问候 ──
        appendGreeting(sb, deviated, hasHistory);

        // ── 报告概览 ──
        appendOverviewCard(sb, result, all.size(), normal, high, low, abnormalCount, unknown);

        // ── 偏离重点（异常项优先展示，用户第一时间看到关键信息）──
        if (deviated > 0) {
            appendAbnormalHighlight(sb, abnormal);
        } else {
            sb.append("✅ 本次报告指标均在参考范围内，暂无需要特别关注的偏离项。\n\n");
        }

        // ── 趋势对比（有历史数据时展示）──
        if (hasHistory && trends != null && !trends.isEmpty()) {
            appendTrendSection(sb, trends);
        }

        // ── 复查建议（有异常时展示）──
        if (followUp != null && followUp.items() != null && !followUp.items().isEmpty()) {
            appendFollowUpSection(sb, followUp);
        }

        // ── 生活小贴士 ──
        appendLifestyleTips(sb, abnormal);

        // ── 下一步 ──
        appendNextSteps(sb, deviated > 0, hasHistory);

        // ── 免责声明 ──
        sb.append("───\n");
        sb.append("⚠️ ").append(result.disclaimer()).append('\n');

        return sb.toString().trim();
    }

    // ── 各板块实现 ──

    private static void appendGreeting(StringBuilder sb, long deviated, boolean hasHistory) {
        if (deviated == 0) {
            sb.append("👋 报告解析完成！整体情况良好，为您点赞 👍\n\n");
        } else if (hasHistory) {
            sb.append("👋 报告已解析，我帮您对比了上次的结果，一起来看看吧～\n\n");
        } else {
            sb.append("👋 报告解析完成！我帮您梳理了重点，请查收～\n\n");
        }
    }

    private static void appendOverviewCard(StringBuilder sb, HealthReportParseResult result,
                                           int total, long normal, long high, long low,
                                           long abnormalCount, long unknown) {
        sb.append("📊 报告概览\n");
        sb.append("┌─ 来源：").append(result.sourceFilename()).append('\n');
        sb.append("├─ 页数：").append(result.pageCount()).append(" 页\n");
        sb.append("├─ 共识别 ").append(total).append(" 项指标\n");
        sb.append("│  ✅ 正常：").append(normal).append(" 项\n");

        long deviated = high + low + abnormalCount;
        if (deviated > 0) {
            sb.append("│  🔴 偏离参考范围：").append(deviated).append(" 项");
            if (high > 0) sb.append("（偏高 ").append(high).append("）");
            if (low > 0) sb.append("（偏低 ").append(low).append("）");
            if (abnormalCount > 0) sb.append("（异常 ").append(abnormalCount).append("）");
            sb.append('\n');
        }
        if (unknown > 0) {
            sb.append("│  ❓ 无参考范围：").append(unknown).append(" 项（建议人工核对）\n");
        }
        sb.append("└─ 解析时间：").append(java.time.LocalDate.now()).append('\n');
        sb.append('\n');
    }

    private static void appendAbnormalHighlight(StringBuilder sb, List<HealthIndicator> abnormal) {
        sb.append("🔍 重点关注\n");
        sb.append("以下 ").append(abnormal.size()).append(" 项指标偏离参考范围：\n\n");

        int idx = 1;
        for (HealthIndicator ind : abnormal) {
            String emoji = switch (ind.status()) {
                case HIGH -> "🔺";
                case LOW -> "🔻";
                case ABNORMAL -> "⚠️";
                default -> "•";
            };
            String label = switch (ind.status()) {
                case HIGH -> "偏高";
                case LOW -> "偏低";
                case ABNORMAL -> "异常";
                default -> "";
            };

            sb.append(idx++).append(". ").append(emoji).append(" ")
                    .append(ind.name()).append("：")
                    .append(blankToDash(ind.value()))
                    .append(blankUnit(ind.unit()))
                    .append("（").append(label).append("，")
                    .append("参考 ").append(blankToDash(ind.referenceRange())).append("）");

            // 附带原文关键信息
            if (!ind.evidenceText().isBlank()) {
                sb.append("\n   原文：").append(ind.evidenceText());
            }
            sb.append('\n');
        }
        sb.append('\n');
    }

    private static void appendTrendSection(StringBuilder sb, List<ReportTrendAnalyzer.TrendItem> trends) {
        sb.append("📈 与上次报告对比\n");

        long worse = trends.stream()
                .filter(t -> "加重".equals(t.direction()) || "新增异常".equals(t.direction()))
                .count();
        long better = trends.stream()
                .filter(t -> "好转".equals(t.direction()) || "改善".equals(t.direction()))
                .count();
        long stable = trends.size() - worse - better;

        // 趋势摘要
        StringBuilder trendSummary = new StringBuilder();
        if (worse > 0) trendSummary.append(worse).append(" 项加重/新增");
        if (better > 0) {
            if (!trendSummary.isEmpty()) trendSummary.append("，");
            trendSummary.append(better).append(" 项好转/改善");
        }
        if (stable > 0) {
            if (!trendSummary.isEmpty()) trendSummary.append("，");
            trendSummary.append(stable).append(" 项持平");
        }
        sb.append("趋势摘要：").append(trendSummary).append('\n');

        // 仅展示有关注价值的项（加重、新增异常、好转）
        List<ReportTrendAnalyzer.TrendItem> notable = trends.stream()
                .filter(t -> !t.direction().isBlank() && !"持平".equals(t.direction()))
                .toList();
        if (!notable.isEmpty()) {
            for (ReportTrendAnalyzer.TrendItem t : notable) {
                String dirEmoji = switch (t.direction()) {
                    case "加重" -> "🔴";
                    case "新增异常" -> "🆕";
                    case "好转" -> "🟢";
                    case "改善" -> "🟡";
                    default -> "•";
                };
                sb.append("  ").append(dirEmoji).append(" ")
                        .append(t.name()).append("：")
                        .append(t.previousValue()).append(" → ")
                        .append(t.currentValue())
                        .append("（").append(t.direction()).append("）\n");
            }
        }
        sb.append('\n');
    }

    private static void appendFollowUpSection(StringBuilder sb,
                                               ReportTrendAnalyzer.FollowUpSuggestions followUp) {
        sb.append("🏥 复查建议\n");

        int idx = 1;
        for (String item : followUp.items()) {
            // 根据不同关键词添加图标
            String prefix;
            if (item.contains("紧急") || item.contains("尽快")) {
                prefix = "🔴 ";
            } else if (item.contains("提醒") || item.contains("复查提醒")) {
                prefix = "📅 ";
            } else if (item.contains("携带") || item.contains("门诊")) {
                prefix = "🏥 ";
            } else {
                prefix = "💊 ";
            }
            sb.append(idx++).append(". ").append(prefix).append(item).append('\n');
        }
        sb.append('\n');
    }

    private static void appendLifestyleTips(StringBuilder sb, List<HealthIndicator> abnormal) {
        sb.append("🌿 生活方式小贴士\n");

        if (abnormal == null || abnormal.isEmpty()) {
            sb.append("保持均衡饮食、适度运动、充足睡眠，定期体检，健康常在～\n");
        } else {
            // 根据异常指标类型生成针对性生活建议
            boolean hasSugar = abnormal.stream().anyMatch(i ->
                    i.name().contains("血糖") || i.name().contains("GLU") || i.name().contains("Glucose"));
            boolean hasLipid = abnormal.stream().anyMatch(i ->
                    i.name().contains("血脂") || i.name().contains("胆固醇") || i.name().contains("甘油三酯")
                            || i.name().contains("Triglyceride") || i.name().contains("Cholesterol") || i.name().contains("LDL"));
            boolean hasUric = abnormal.stream().anyMatch(i ->
                    i.name().contains("尿酸") || i.name().contains("UricAcid") || i.name().contains("Uric"));
            boolean hasLiver = abnormal.stream().anyMatch(i ->
                    i.name().contains("转氨酶") || i.name().contains("ALT") || i.name().contains("AST"));
            boolean hasPressure = abnormal.stream().anyMatch(i ->
                    i.name().contains("血压") || i.name().contains("收缩压") || i.name().contains("舒张压"));

            if (hasSugar) {
                sb.append("🥗 控糖：少食多餐，减少精制碳水，多吃全谷物和蔬菜，餐后适度散步\n");
            }
            if (hasLipid) {
                sb.append("🐟 降脂：每周2-3次深海鱼，减少红肉和油炸食品，坚果适量\n");
            }
            if (hasUric) {
                sb.append("💧 降尿酸：每日饮水2000ml以上，避免动物内脏、海鲜、啤酒\n");
            }
            if (hasLiver) {
                sb.append("😴 护肝：避免饮酒，不熬夜，慎用保健品和不明成分药物\n");
            }
            if (hasPressure) {
                sb.append("🧂 控压：每日食盐不超过5g，少吃腌制食品，保持情绪平稳\n");
            }
            if (!hasSugar && !hasLipid && !hasUric && !hasLiver && !hasPressure) {
                sb.append("保持均衡饮食、适度运动、充足睡眠，定期体检，健康常在～\n");
            }
            sb.append("💪 每周至少150分钟中等强度运动，如快走、游泳、骑行\n");
        }
        sb.append('\n');
    }

    private static void appendNextSteps(StringBuilder sb, boolean hasAbnormal, boolean hasHistory) {
        sb.append("📌 您可以…\n");
        sb.append("• 回复「设置复查提醒」预约复查通知，避免遗忘\n");
        if (hasAbnormal) {
            sb.append("• 建议 1-2 周内携带本报告到内科/全科门诊就诊\n");
            sb.append("• 回复「导出报告」获取 Word 版详细分析文档\n");
        }
        if (!hasHistory) {
            sb.append("• 下次上传报告时，我会自动对比变化趋势\n");
        }
        sb.append("• 如有不适，请及时线下就医 🏥\n");
        sb.append('\n');
    }

    // ── 原有的统计和明细方法（基础版）──

    private static void appendStatistics(StringBuilder sb, List<HealthIndicator> indicators) {
        sb.append("统计摘要：\n");
        if (indicators == null || indicators.isEmpty()) {
            sb.append(" - 未提取到可识别的指标项\n");
            return;
        }
        int total = indicators.size();
        long normalCount = countByStatus(indicators, IndicatorStatus.NORMAL);
        long highCount = countByStatus(indicators, IndicatorStatus.HIGH);
        long lowCount = countByStatus(indicators, IndicatorStatus.LOW);
        long abnormalCount = countByStatus(indicators, IndicatorStatus.ABNORMAL);
        long unknownCount = countByStatus(indicators, IndicatorStatus.UNKNOWN);

        sb.append(" - 共识别 ")
                .append(total).append(" 项指标：正常 ")
                .append(normalCount).append(" 项");

        long deviated = highCount + lowCount + abnormalCount;
        if (deviated > 0) {
            sb.append("，偏离参考范围 ")
                    .append(deviated).append(" 项（偏高 ")
                    .append(highCount).append("、偏低 ")
                    .append(lowCount).append("、异常 ")
                    .append(abnormalCount).append("）");
        } else {
            sb.append("，未发现明显偏离项");
        }
        if (unknownCount > 0) {
            sb.append("，无参考范围 ").append(unknownCount).append(" 项");
        }
        sb.append('\n');
    }

    private static void appendIndicatorSummary(StringBuilder sb, List<HealthIndicator> indicators) {
        sb.append("指标明细：\n");
        if (indicators == null || indicators.isEmpty()) {
            sb.append(" - 未提取到可识别的指标项\n");
            return;
        }
        for (HealthIndicator indicator : indicators) {
            sb.append(" - ")
                    .append(indicator.name())
                    .append("：")
                    .append(blankToDash(indicator.value()))
                    .append(blankUnit(indicator.unit()))
                    .append("，参考 ")
                    .append(blankToDash(indicator.referenceRange()))
                    .append("，页码 ")
                    .append(indicator.pageNo());
            String deviation = deviationLabel(indicator.status());
            if (!deviation.isBlank()) {
                sb.append("，").append(deviation);
            }
            sb.append('\n');
        }
    }

    private static void appendAbnormalSection(StringBuilder sb, List<HealthIndicator> abnormalIndicators) {
        sb.append("偏离判断：\n");
        if (abnormalIndicators == null || abnormalIndicators.isEmpty()) {
            sb.append("本次解析未发现报告明确标注的偏离项。\n");
            return;
        }
        sb.append("以下 ").append(abnormalIndicators.size())
                .append(" 项指标偏离参考范围，建议重点关注：\n");
        for (HealthIndicator indicator : abnormalIndicators) {
            sb.append(formatAbnormalIndicator(indicator)).append('\n');
        }
    }

    private static String formatAbnormalIndicator(HealthIndicator indicator) {
        StringBuilder sb = new StringBuilder();
        sb.append(" - 【").append(deviationLabel(indicator.status())).append("】")
                .append(indicator.name())
                .append("：")
                .append(blankToDash(indicator.value()))
                .append(blankUnit(indicator.unit()));
        if (!indicator.referenceRange().isBlank()) {
            sb.append("（参考 ").append(indicator.referenceRange()).append("）");
        }
        sb.append("，页码 ").append(indicator.pageNo());
        if (!indicator.evidenceText().isBlank()) {
            sb.append("，原文：").append(indicator.evidenceText());
        }
        return sb.toString();
    }

    // ── 工具方法 ──

    private static long countByStatus(List<HealthIndicator> indicators, IndicatorStatus status) {
        return indicators.stream().filter(i -> i.status() == status).count();
    }

    private static String deviationLabel(IndicatorStatus status) {
        if (status == null) return "";
        return switch (status) {
            case HIGH -> "偏高";
            case LOW -> "偏低";
            case ABNORMAL -> "异常";
            case NORMAL -> "正常";
            case UNKNOWN -> "";
        };
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value.trim();
    }

    private static String blankUnit(String unit) {
        if (unit == null || unit.isBlank()) return "";
        return " " + unit.trim();
    }
}
