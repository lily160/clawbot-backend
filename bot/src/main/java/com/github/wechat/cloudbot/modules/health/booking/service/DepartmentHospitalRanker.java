package com.github.wechat.cloudbot.modules.health.booking.service;

import com.github.wechat.cloudbot.modules.health.booking.infra.HealthBookingProperties;
import com.github.wechat.cloudbot.modules.search.api.SearchGateway;
import com.github.wechat.cloudbot.platform.config.Strings;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 根据科室为三家医院排序的优选器。
 * <p>
 * 现阶段采用“公开知识 + 联网搜索可解释打分”的方式，避免强依赖无法稳定调用的 LLM 工具链。
 */
@Component
public class DepartmentHospitalRanker {

    private static final List<String> TARGET_HOSPITALS = List.of(
            "杭州市余杭区第一人民医院",
            "余杭区二院",
            "杭州市余杭区第三人民医院"
    );

    private final HealthBookingProperties bookingProperties;
    private final SearchGateway searchGateway;

    public DepartmentHospitalRanker(HealthBookingProperties bookingProperties, SearchGateway searchGateway) {
        this.bookingProperties = bookingProperties;
        this.searchGateway = searchGateway;
    }

    public HospitalRankResult rank(String department) {
        String normalizedDepartment = normalize(department);
        List<HospitalScore> scored = new ArrayList<>();
        for (String hospital : TARGET_HOSPITALS) {
            scored.add(scoreHospital(normalizedDepartment, hospital));
        }
        scored.sort(Comparator.comparingDouble(HospitalScore::score).reversed()
                .thenComparing(HospitalScore::hospitalName));

        List<HospitalRankResult.HospitalRankItem> items = new ArrayList<>();
        for (int i = 0; i < scored.size(); i++) {
            HospitalScore score = scored.get(i);
            items.add(new HospitalRankResult.HospitalRankItem(
                    i + 1,
                    score.hospitalName(),
                    score.miniProgramName(),
                    score.miniProgramPath(),
                    score.score(),
                    score.reason()));
        }
        String rationale = buildRationale(normalizedDepartment, items);
        return new HospitalRankResult(normalizedDepartment, items, rationale);
    }

    private HospitalScore scoreHospital(String department, String hospitalName) {
        double score = 0.0;
        List<String> reasons = new ArrayList<>();

        MiniProgramBundle bundle = resolveBundle(hospitalName);
        if (!bundle.miniProgramPath.isBlank()) {
            score += 0.5;
            reasons.add("有独立小程序入口");
        }

        String searchText = searchDepartmentInfo(department, hospitalName);
        if (!searchText.isBlank()) {
            score += scoreFromSearch(searchText, department, hospitalName, reasons);
        } else {
            score += fallbackScore(department, hospitalName, reasons);
        }

        return new HospitalScore(hospitalName, bundle.miniProgramName, bundle.miniProgramPath, round(score), String.join("；", reasons));
    }

    private double scoreFromSearch(String searchText, String department, String hospitalName, List<String> reasons) {
        String lower = searchText.toLowerCase(Locale.ROOT);
        double score = 0.0;
        score += keywordScore(lower, department, hospitalName, reasons);
        score += specialityScore(lower, reasons);
        score += expertScore(lower, reasons);
        score += hospitalLevelScore(lower, reasons);
        score += volumeScore(lower, reasons);
        return score;
    }

    private double keywordScore(String lower, String department, String hospitalName, List<String> reasons) {
        double score = 0.0;
        if (!department.isBlank() && lower.contains(department.toLowerCase(Locale.ROOT))) {
            score += 2.0;
            reasons.add("搜索结果直接命中科室名");
        }
        if (lower.contains(hospitalName.toLowerCase(Locale.ROOT))) {
            score += 0.5;
            reasons.add("搜索结果包含医院名");
        }
        return score;
    }

    private double specialityScore(String lower, List<String> reasons) {
        double score = 0.0;
        Map<String, Double> keywords = new java.util.LinkedHashMap<>();
        keywords.put("专家", 1.0);
        keywords.put("重点专科", 1.5);
        keywords.put("特色专科", 1.0);
        keywords.put("专病", 1.0);
        keywords.put("口腔", 0.8);
        keywords.put("种植", 0.8);
        keywords.put("正畸", 0.8);
        keywords.put("儿科", 0.8);
        keywords.put("妇科", 0.8);
        keywords.put("皮肤", 0.8);
        keywords.put("内分泌", 0.8);
        keywords.put("心内", 0.8);
        for (Map.Entry<String, Double> entry : keywords.entrySet()) {
            if (lower.contains(entry.getKey())) {
                score += entry.getValue();
                reasons.add("搜索结果提到" + entry.getKey());
            }
        }
        return score;
    }

    private double expertScore(String lower, List<String> reasons) {
        double score = 0.0;
        if (lower.contains("副主任医师") || lower.contains("主任医师") || lower.contains("专家门诊")) {
            score += 1.0;
            reasons.add("存在专家/高职称医生");
        }
        if (lower.contains("知名") || lower.contains("省级") || lower.contains("市级")) {
            score += 0.6;
            reasons.add("公开介绍有较高等级专家信息");
        }
        return score;
    }

    private double hospitalLevelScore(String lower, List<String> reasons) {
        double score = 0.0;
        if (lower.contains("三级甲等") || lower.contains("三甲")) {
            score += 1.0;
            reasons.add("医院等级较高");
        } else if (lower.contains("二级甲等") || lower.contains("二甲")) {
            score += 0.4;
            reasons.add("医院为二级甲等");
        }
        if (lower.contains("区域医疗中心") || lower.contains("重点医院") || lower.contains("临床中心")) {
            score += 0.8;
            reasons.add("公开介绍强调区域医疗能力");
        }
        return score;
    }

    private double volumeScore(String lower, List<String> reasons) {
        double score = 0.0;
        if (lower.contains("号源") || lower.contains("排班")) {
            score += 0.4;
            reasons.add("公开信息可见号源/排班能力");
        }
        if (lower.contains("上午") || lower.contains("下午") || lower.contains("门诊")) {
            score += 0.2;
            reasons.add("有明确门诊排班信息");
        }
        return score;
    }

    private double fallbackScore(String department, String hospitalName, List<String> reasons) {
        double score = 0.0;
        if ("杭州市余杭区第一人民医院".equals(hospitalName)) {
            score += 1.2;
            reasons.add("默认优先级：第一人民医院");
        } else if ("杭州市余杭区第三人民医院".equals(hospitalName)) {
            score += 1.0;
            reasons.add("默认优先级：第三人民医院");
        } else {
            score += 0.8;
            reasons.add("默认优先级：二院");
        }
        if (department.contains("口腔")) {
            if ("杭州市余杭区第一人民医院".equals(hospitalName)) {
                score += 1.0;
                reasons.add("口腔科号源和专家信息较丰富");
            }
            if ("杭州市余杭区第三人民医院".equals(hospitalName)) {
                score += 0.8;
                reasons.add("口腔科存在专家门诊信息");
            }
        }
        return score;
    }

    private String searchDepartmentInfo(String department, String hospitalName) {
        if (searchGateway == null || !searchGateway.isAvailable()) {
            return "";
        }
        String query = hospitalName + " " + department + " 科室 专家 重点专科 排班";
        try {
            return Optional.ofNullable(searchGateway.search(query, "oneYear"))
                    .map(String::trim)
                    .orElse("");
        } catch (Exception e) {
            return "";
        }
    }

    private MiniProgramBundle resolveBundle(String hospitalName) {
        HealthBookingProperties.MiniProgramTarget target = bookingProperties.resolveMiniProgram(hospitalName);
        if (target == null) {
            return new MiniProgramBundle(hospitalName, "");
        }
        String name = Strings.firstNonBlank(target.getName(), hospitalName);
        String path = target.getPath() == null ? "" : target.getPath();
        return new MiniProgramBundle(name, path);
    }

    private String buildRationale(String department, List<HospitalRankResult.HospitalRankItem> items) {
        if (items.isEmpty()) {
            return "未找到可排序医院。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("针对").append(department).append("，优先推荐：");
        for (int i = 0; i < items.size(); i++) {
            HospitalRankResult.HospitalRankItem item = items.get(i);
            if (i > 0) {
                sb.append("，其次");
            }
            sb.append(item.rank()).append(". ").append(item.hospitalName());
        }
        sb.append("。优先级综合考虑公开科室信息、专家资源、医院等级与排班可用性。");
        return sb.toString();
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim();
    }

    private record HospitalScore(String hospitalName, String miniProgramName, String miniProgramPath, double score, String reason) {
    }

    private record MiniProgramBundle(String miniProgramName, String miniProgramPath) {
    }
}
