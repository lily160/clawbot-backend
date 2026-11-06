package com.github.wechat.cloudbot.modules.health.report.service;

import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus;
import com.github.wechat.cloudbot.modules.health.report.domain.ReportPageText;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class HealthIndicatorParser {

    private static final Pattern NUMERIC_PATTERN = Pattern.compile(
            "(?<name>[\\p{IsHan}A-Za-z0-9（）()·\\-\\s]{2,40}?)\\s+"
                    + "(?<value>[+-]?\\d+(?:\\.\\d+)?)\\s*"
                    + "(?<unit>[A-Za-zμµ/%^0-9·\\-×]+)?"
                    + "(?:\\s*(?<marker>[↑↓▲▼↗↘]))?"
                    + "(?:\\s*(?:参考(?:值|范围)?[：:]\\s*)?(?<range>[<>]?[+-]?\\d+(?:\\.\\d+)?\\s*[-~–—至]\\s*[<>]?[+-]?\\d+(?:\\.\\d+)?))?",
            Pattern.UNICODE_CASE);

    private static final Pattern SINGLE_SIDED_RANGE = Pattern.compile(
            "(?<name>[\\p{IsHan}A-Za-z0-9（）()·\\-\\s]{2,40}?)\\s+"
                    + "(?<value>[+-]?\\d+(?:\\.\\d+)?)\\s*"
                    + "(?<unit>[A-Za-zμµ/%^0-9·\\-×]+)?"
                    + "(?:\\s*(?<marker>[↑↓▲▼↗↘]))?"
                    + "(?:\\s*(?:参考(?:值|范围)?[：:]\\s*)?(?<range>[<>]\\d+(?:\\.\\d+)?))",
            Pattern.UNICODE_CASE);

    private static final Pattern STATUS_PATTERN = Pattern.compile(
            "(?<name>[\\p{IsHan}A-Za-z0-9（）()·\\-\\s]{2,40}?)\\s+"
                    + "(?<value>阳性|阴性|弱阳性|强阳性|\\+{1,3}|-{1,3})"
                    + "(?:\\s*(?<reference>阴性|阳性|\\+{1,3}|-{1,3}))?",
            Pattern.UNICODE_CASE);

    public List<HealthIndicator> parse(List<ReportPageText> pages) {
        Objects.requireNonNull(pages, "pages");
        Map<String, HealthIndicator> indicators = new LinkedHashMap<>();
        for (ReportPageText page : pages) {
            if (page == null || page.text() == null || page.text().isBlank()) {
                continue;
            }
            extractNumericIndicators(page, indicators);
            extractStatusIndicators(page, indicators);
        }
        return List.copyOf(indicators.values());
    }

    private void extractNumericIndicators(ReportPageText page, Map<String, HealthIndicator> indicators) {
        for (String line : splitLines(page.text())) {
            Matcher matcher = NUMERIC_PATTERN.matcher(line);
            boolean foundRange = false;
            while (matcher.find()) {
                String raw = matcher.group(0);
                String name = normalizeName(matcher.group("name"));
                String value = matcher.group("value");
                String unit = defaultString(matcher.group("unit"));
                String range = defaultString(matcher.group("range"));
                String marker = defaultString(matcher.group("marker"));
                if (range.isBlank() && looksLikeRange(unit)) {
                    range = unit;
                    unit = "";
                }
                RangeWithUnit cleaned = stripUnitFromRange(range, unit);
                IndicatorStatus status = resolveNumericStatus(value, cleaned.range(), marker);
                addIndicator(indicators, new HealthIndicator(
                        name, value,
                        cleaned.unit().isBlank() ? unit : cleaned.unit(),
                        cleaned.range(), status, page.pageNo(), clipEvidence(raw), null));
                if (!cleaned.range().isBlank()) {
                    foundRange = true;
                }
            }
            if (!foundRange) {
                Matcher singleMatcher = SINGLE_SIDED_RANGE.matcher(line);
                while (singleMatcher.find()) {
                    String raw = singleMatcher.group(0);
                    String name = normalizeName(singleMatcher.group("name"));
                    String value = singleMatcher.group("value");
                    String unit = defaultString(singleMatcher.group("unit"));
                    String range = defaultString(singleMatcher.group("range"));
                    String marker = defaultString(singleMatcher.group("marker"));
                    IndicatorStatus status = resolveSingleSidedStatus(value, range, marker);
                    addIndicator(indicators, new HealthIndicator(
                            name, value, unit, range, status,
                            page.pageNo(), clipEvidence(raw), null));
                }
            }
        }
    }

    private void extractStatusIndicators(ReportPageText page, Map<String, HealthIndicator> indicators) {
        for (String line : splitLines(page.text())) {
            Matcher matcher = STATUS_PATTERN.matcher(line);
            while (matcher.find()) {
                String raw = matcher.group(0);
                String name = normalizeName(matcher.group("name"));
                String value = matcher.group("value");
                String reference = defaultString(matcher.group("reference"));
                IndicatorStatus status = resolveStatus(value, reference);
                addIndicator(indicators, new HealthIndicator(
                        name, value, "", reference, status,
                        page.pageNo(), clipEvidence(raw), null));
            }
        }
    }

    private static void addIndicator(Map<String, HealthIndicator> indicators, HealthIndicator indicator) {
        String fullKey = indicator.name() + "|" + indicator.value() + "|" + indicator.referenceRange() + "|" + indicator.pageNo();
        if (indicator.referenceRange().isBlank()) {
            String prefix = indicator.name() + "|" + indicator.value() + "|";
            String suffix = "|" + indicator.pageNo();
            boolean hasBetter = indicators.keySet().stream()
                    .anyMatch(k -> k.startsWith(prefix) && k.endsWith(suffix)
                            && k.length() > (prefix.length() + suffix.length()));
            if (!hasBetter) {
                indicators.putIfAbsent(fullKey, indicator);
            }
        } else {
            String prefix = indicator.name() + "|" + indicator.value() + "|";
            String suffix = "|" + indicator.pageNo();
            String blankRangeKey = prefix + suffix;
            if (indicators.containsKey(blankRangeKey)) {
                indicators.remove(blankRangeKey);
            }
            indicators.putIfAbsent(fullKey, indicator);
        }
    }

    private static IndicatorStatus resolveNumericStatus(String value, String range, String marker) {
        IndicatorStatus markerStatus = markerStatus(marker);
        if (markerStatus != IndicatorStatus.UNKNOWN) {
            return markerStatus;
        }
        if (range.isBlank()) {
            return IndicatorStatus.UNKNOWN;
        }
        double numericValue = parseDouble(value);
        double[] bounds = parseRange(range);
        if (Double.isNaN(bounds[0]) || Double.isNaN(bounds[1])) {
            return IndicatorStatus.UNKNOWN;
        }
        if (numericValue < bounds[0]) return IndicatorStatus.LOW;
        if (numericValue > bounds[1]) return IndicatorStatus.HIGH;
        return IndicatorStatus.NORMAL;
    }

    private static IndicatorStatus resolveStatus(String value, String reference) {
        IndicatorStatus valueStatus = markerStatus(value);
        if (valueStatus != IndicatorStatus.UNKNOWN) return valueStatus;
        IndicatorStatus referenceStatus = markerStatus(reference);
        if (referenceStatus != IndicatorStatus.UNKNOWN) {
            return referenceStatus == IndicatorStatus.NORMAL ? IndicatorStatus.NORMAL : IndicatorStatus.ABNORMAL;
        }
        return IndicatorStatus.UNKNOWN;
    }

    private static IndicatorStatus markerStatus(String marker) {
        if (marker == null || marker.isBlank()) return IndicatorStatus.UNKNOWN;
        String normalized = marker.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("阳") || normalized.contains("+") || normalized.contains("弱阳") || normalized.contains("强阳"))
            return IndicatorStatus.ABNORMAL;
        if (normalized.contains("阴") || normalized.contains("-")) return IndicatorStatus.NORMAL;
        if (normalized.contains("↑") || normalized.contains("▲") || normalized.contains("↗") || normalized.contains("高"))
            return IndicatorStatus.HIGH;
        if (normalized.contains("↓") || normalized.contains("▼") || normalized.contains("↘") || normalized.contains("低"))
            return IndicatorStatus.LOW;
        return IndicatorStatus.UNKNOWN;
    }

    private static IndicatorStatus resolveSingleSidedStatus(String value, String range, String marker) {
        IndicatorStatus markerStatus = markerStatus(marker);
        if (markerStatus != IndicatorStatus.UNKNOWN) return markerStatus;
        if (range.isBlank()) return IndicatorStatus.UNKNOWN;
        double numericValue = parseDouble(value);
        if (Double.isNaN(numericValue)) return IndicatorStatus.UNKNOWN;
        double bound = parseDouble(range.replaceAll("^[<>]", "").trim());
        if (Double.isNaN(bound)) return IndicatorStatus.UNKNOWN;
        if (range.startsWith("<")) return numericValue >= bound ? IndicatorStatus.HIGH : IndicatorStatus.NORMAL;
        if (range.startsWith(">")) return numericValue <= bound ? IndicatorStatus.LOW : IndicatorStatus.NORMAL;
        return IndicatorStatus.UNKNOWN;
    }

    private static double parseDouble(String value) {
        try { return Double.parseDouble(value); }
        catch (Exception e) { return Double.NaN; }
    }

    private static double[] parseRange(String range) {
        String normalized = normalizeLine(range);
        Matcher m = Pattern.compile(
                "([<>]?[+-]?\\d+(?:\\.\\d+)?)\\s*[-~–—至]\\s*([<>]?[+-]?\\d+(?:\\.\\d+)?)"
        ).matcher(normalized);
        if (m.find()) {
            return new double[]{
                    parseDouble(m.group(1).replaceAll("^[<>]", "").trim()),
                    parseDouble(m.group(2).replaceAll("^[<>]", "").trim())
            };
        }
        return new double[]{Double.NaN, Double.NaN};
    }

    private static List<String> splitLines(String text) {
        String normalized = normalizePageText(text);
        String[] lines = normalized.split("\\R+");
        List<String> result = new ArrayList<>(lines.length);
        for (String line : lines) {
            String normalizedLine = normalizeLine(line);
            if (!normalizedLine.isBlank()) result.add(normalizedLine);
        }
        return result;
    }

    private static String normalizePageText(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 12288) sb.append(' ');
            else if (c >= 65281 && c <= 65374) sb.append((char) (c - 65248));
            else if (c == '\r') sb.append('\n');
            else if (c == '\n') sb.append('\n');
            else if (c == '\t' || c == '\f' || c == '') sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String normalizeLine(String line) {
        return line == null ? "" : line.replaceAll("\\s+", " ").trim();
    }

    private static String normalizeName(String name) {
        return normalizeLine(normalizePageText(name)).replaceAll("\\s+", "");
    }

    private static String defaultString(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean looksLikeRange(String value) {
        if (value == null || value.isBlank()) return false;
        if (value.matches(".*\\d+\\s*[-~–—至]\\s*\\d+.*")) return true;
        if (value.matches("^\\d+\\s+\\d.*")) return true;
        return false;
    }

    private static String clipEvidence(String text) {
        String value = text == null ? "" : text.trim();
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    private static RangeWithUnit stripUnitFromRange(String range, String currentUnit) {
        if (range.isBlank()) return new RangeWithUnit(range, currentUnit);
        String cleaned = range.replaceAll("\\s+", " ").trim();
        Matcher m = Pattern.compile("^(.*?\\d)\\s+([A-Za-zμµ%/][A-Za-zμµ%/0-9·\\-]*)$")
                .matcher(cleaned);
        if (m.matches()) {
            String rangePart = m.group(1).trim();
            String unitPart = m.group(2).trim();
            if (currentUnit.isBlank() || unitPart.length() >= currentUnit.length()) {
                return new RangeWithUnit(rangePart, unitPart);
            }
        }
        return new RangeWithUnit(range, currentUnit);
    }

    private record RangeWithUnit(String range, String unit) {}
}
