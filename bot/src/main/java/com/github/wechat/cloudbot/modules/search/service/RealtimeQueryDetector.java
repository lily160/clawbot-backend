package com.github.wechat.cloudbot.modules.search.service;

import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联网搜索三层漏斗（DeepSeek 未调 {@code web_search} 时的兜底）。
 *
 * <p>刻意收紧：裸「今天/现在」等口语不做搜索，避免把闲聊抱怨当成实时查询。
 */
public final class RealtimeQueryDetector {

    private RealtimeQueryDetector() {}

    public enum Layer {
        HEURISTIC,
        YEAR_SIGNAL,
        LOCATION
    }

    public record SearchDecision(boolean needsSearch, Layer layer, String reason) {
        public static SearchDecision skip() {
            return new SearchDecision(false, null, null);
        }
    }

    /** 第 2 层：明确的实时/事实类意图。
     * 不含裸「今天/今日/现在/目前/什么时候」，以免误伤闲聊。
     */
    private static final Pattern HEURISTIC =
            Pattern.compile(
                    ".*(天气|气温|下雨|降雪|台风|空气质量|预报|"
                            + "新闻|头条|热点|最新消息|实时|"
                            + "股价|汇率|金价|油价|"
                            + "夺冠|冠军|谁赢了|比分|赛果|决赛|晋级|"
                            + "世界杯|奥运会|欧洲杯|亚洲杯|美加墨).*",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern WEATHER_INTENT =
            Pattern.compile(
                    ".*(天气|气温|下雨|降雨|降雪|下雪|台风|空气质量|湿度|风力|几度).*",
                    Pattern.CASE_INSENSITIVE);

    /** 从「北京天气」「明天杭州下雨吗」等句子里抽城市。 */
    private static final Pattern WEATHER_CITY =
            Pattern.compile(
                    "([\\u4e00-\\u9fa5A-Za-z]{2,20}?(?:省|市|区|县|州|盟)?)(?=的?(?:天气|气温|下雨|降雨|降雪|下雪|台风|空气质量|湿度|风力|预报|几度))");

    /** 「今天/现在」等时间词，必须再搭配事实域才搜。 */
    private static final Pattern TIME_PLUS_FACT =
            Pattern.compile(
                    ".*(今天|今日|现在|目前).*(天气|气温|新闻|头条|热点|股价|汇率|比分|赛果|冠军|夺冠).*|"
                            + ".*(天气|气温|新闻|头条|热点|股价|汇率|比分|赛果|冠军|夺冠).*(今天|今日|现在|目前).*",
                    Pattern.CASE_INSENSITIVE);

    /** 第 3 层：像在问可验证事实（需配合年份信号）。 */
    private static final Pattern FACTUAL_INTENT =
            Pattern.compile(
                    ".*(夺冠|冠军|谁赢|比分|赛果|决赛|晋级|"
                            + "发生了什么|发布了什么|谁当|"
                            + "选举|总统|总理|上映|票房|"
                            + "股价|汇率|金价|去世|上市|"
                            + "世界杯|奥运会|欧洲杯|亚洲杯|美加墨).*",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern YEAR = Pattern.compile("(20\\d{2})");

    private static final Pattern YEAR_MONTH_CN =
            Pattern.compile("(20\\d{2})\\s*年\\s*(\\d{1,2})\\s*月");

    private static final Pattern YEAR_MONTH_ISO =
            Pattern.compile("(20\\d{2})-(\\d{1,2})");

    /** 纯地名：必须带 市/区/县/省/州，避免任意短句误匹配。 */
    private static final Pattern LOCATION_ONLY =
            Pattern.compile(
                    "^[\\u4e00-\\u9fa5A-Za-z]{2,20}(市|区|县|省|州)"
                            + "([，,、\\s]+[\\u4e00-\\u9fa5A-Za-z]{1,20}(区|县|市))?$");

    public static SearchDecision analyze(String userContent, YearMonth knowledgeCutoff) {
        if (userContent == null || userContent.isBlank()) {
            return SearchDecision.skip();
        }
        String text = userContent.trim();
        YearMonth cutoff = knowledgeCutoff != null ? knowledgeCutoff : YearMonth.of(2025, 6);

        if (HEURISTIC.matcher(text).matches()) {
            return new SearchDecision(true, Layer.HEURISTIC, "实时/赛事/新闻关键词");
        }
        if (TIME_PLUS_FACT.matcher(text).matches()) {
            return new SearchDecision(true, Layer.HEURISTIC, "时间词+事实域");
        }
        if (LOCATION_ONLY.matcher(text).matches()) {
            return new SearchDecision(true, Layer.LOCATION, "纯地名补充");
        }
        String yearReason = postCutoffReason(text, cutoff);
        if (yearReason != null) {
            return new SearchDecision(true, Layer.YEAR_SIGNAL, yearReason);
        }
        return SearchDecision.skip();
    }

    public static boolean likelyNeedsWeather(String userContent) {
        if (userContent == null || userContent.isBlank()) {
            return false;
        }
        String text = userContent.trim();
        if (WEATHER_INTENT.matcher(text).matches()) {
            return true;
        }
        return LOCATION_ONLY.matcher(text).matches();
    }

    /**
     * 从用户话术中提取城市名；纯地名直接返回；抽不出则返回 {@code null}。
     */
    public static String extractWeatherCity(String userContent) {
        if (userContent == null || userContent.isBlank()) {
            return null;
        }
        String text = userContent.trim();
        if (LOCATION_ONLY.matcher(text).matches()) {
            return text;
        }
        Matcher m = WEATHER_CITY.matcher(text);
        if (m.find()) {
            String city = m.group(1).trim();
            city = city.replaceAll("^(今天|今日|现在|目前|明天|后天|这周|下周)", "");
            city = city.replaceAll("^(查一下|帮我查|问问|查询)", "");
            if (city.length() >= 2) {
                return city;
            }
        }
        return null;
    }

    public static String guessWeatherExtensions(String userContent) {
        if (userContent == null) {
            return "all";
        }
        String t = userContent;
        if (t.contains("明天")
                || t.contains("后天")
                || t.contains("这周")
                || t.contains("下周")
                || t.contains("未来")
                || t.contains("预报")) {
            return "all";
        }
        if (t.contains("现在") || t.contains("目前") || t.contains("实时")) {
            return "base";
        }
        return "all";
    }

    public static String buildSearchQuery(String userContent) {
        if (userContent == null) {
            return "";
        }
        String q = userContent.trim();
        if (LOCATION_ONLY.matcher(q).matches()) {
            return q + " 今天天气";
        }
        return enhanceResultQuery(q);
    }

    public static String enhanceSearchQuery(String query, String userContent) {
        String q = query == null || query.isBlank() ? buildSearchQuery(userContent) : query.trim();
        return enhanceResultQuery(q);
    }

    private static String enhanceResultQuery(String q) {
        boolean sportsResult =
                q.contains("夺冠")
                        || q.contains("冠军")
                        || q.contains("谁赢")
                        || q.contains("赛果")
                        || q.contains("比分")
                        || q.contains("世界杯");
        if (!sportsResult) {
            return q;
        }
        StringBuilder sb = new StringBuilder(q);
        if (!q.contains("决赛结果") && !q.contains("决赛 结果")) {
            sb.append(" 决赛结果");
        }
        if (!q.contains("夺冠球队") && !q.contains("冠军是谁")) {
            sb.append(" 冠军是谁");
        }
        if (q.contains("世界杯") && !q.toLowerCase().contains("world cup")) {
            sb.append(" 2026 World Cup final winner champion");
        }
        return sb.toString();
    }

    public static String resolveFreshness(String modelFreshness, String userContent) {
        String guessed = guessFreshness(userContent);
        if (guessed != null) {
            if ("oneWeek".equals(guessed)
                    && userContent != null
                    && (userContent.contains("夺冠")
                            || userContent.contains("冠军")
                            || userContent.contains("赛果")
                            || userContent.contains("比分"))) {
                return "oneDay";
            }
            return guessed;
        }
        if (modelFreshness != null
                && !modelFreshness.isBlank()
                && !"noLimit".equalsIgnoreCase(modelFreshness)) {
            return modelFreshness.trim();
        }
        return "oneWeek";
    }

    public static String guessFreshness(String userContent) {
        if (userContent == null) {
            return null;
        }
        String t = userContent;
        if (t.contains("今天")
                || t.contains("今日")
                || t.contains("现在")
                || t.contains("最新")
                || t.contains("天气")
                || t.contains("新闻")
                || t.contains("股价")
                || t.contains("汇率")) {
            return "oneDay";
        }
        if (t.contains("夺冠")
                || t.contains("冠军")
                || t.contains("比分")
                || t.contains("赛果")
                || t.contains("世界杯")
                || t.contains("奥运会")) {
            return "oneWeek";
        }
        return null;
    }

    /** 安全解析年月；月份越界（如 2025年99月）时返回 null，避免抛未捕获的 DateTimeException。 */
    private static YearMonth parseYearMonth(String yearStr, String monthStr) {
        int month;
        try {
            month = Integer.parseInt(monthStr);
        } catch (NumberFormatException e) {
            return null;
        }
        if (month < 1 || month > 12) {
            return null;
        }
        try {
            return YearMonth.of(Integer.parseInt(yearStr), month);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String postCutoffReason(String text, YearMonth cutoff) {
        if (!FACTUAL_INTENT.matcher(text).matches()) {
            return null;
        }

        Matcher ymCn = YEAR_MONTH_CN.matcher(text);
        while (ymCn.find()) {
            YearMonth mentioned = parseYearMonth(ymCn.group(1), ymCn.group(2));
            if (mentioned != null && mentioned.isAfter(cutoff)) {
                return "年月晚于知识截止 " + cutoff + "：" + mentioned;
            }
        }

        Matcher ymIso = YEAR_MONTH_ISO.matcher(text);
        while (ymIso.find()) {
            YearMonth mentioned = parseYearMonth(ymIso.group(1), ymIso.group(2));
            if (mentioned != null && mentioned.isAfter(cutoff)) {
                return "年月晚于知识截止 " + cutoff + "：" + mentioned;
            }
        }

        Matcher years = YEAR.matcher(text);
        while (years.find()) {
            int year = Integer.parseInt(years.group(1));
            if (year > cutoff.getYear()) {
                return "年份晚于知识截止 " + cutoff + "：" + year;
            }
        }
        return null;
    }
}
