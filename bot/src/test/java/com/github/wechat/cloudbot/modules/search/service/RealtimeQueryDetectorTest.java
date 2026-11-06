package com.github.wechat.cloudbot.modules.search.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.*;

class RealtimeQueryDetectorTest {

    // ================ analyze() ================

    @Test
    void testAnalyzeNull() {
        assertFalse(RealtimeQueryDetector.analyze(null, YearMonth.of(2025, 6)).needsSearch());
    }

    @Test
    void testAnalyzeBlank() {
        assertFalse(RealtimeQueryDetector.analyze("  ", YearMonth.of(2025, 6)).needsSearch());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "今天天气怎么样",
            "北京气温多少",
            "最新的新闻",
            "苹果股价多少",
            "美元汇率",
            "世界杯谁赢了",
            "昨晚的欧冠赛果",
    })
    void testHeuristicMatch(String input) {
        assertTrue(RealtimeQueryDetector.analyze(input, YearMonth.of(2025, 6)).needsSearch());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "你好",
            "今天过得怎么样",
            "讲个笑话",
            "你会做什么",
            "1+1等于几",
    })
    void testHeuristicNoMatch(String input) {
        assertFalse(RealtimeQueryDetector.analyze(input, YearMonth.of(2025, 6)).needsSearch());
    }

    @Test
    void testYearSignalPostCutoff() {
        // 2026 晚于知识截止 2025-06
        assertTrue(RealtimeQueryDetector.analyze("2026年世界杯冠军是谁", YearMonth.of(2025, 6)).needsSearch());
    }

    @Test
    void testYearSignalBeforeCutoff() {
        // "2024年奥运会" 被 HEURISTIC 层（含"奥运会"）匹配，不测试 YEAR_SIGNAL
        // 用仅匹配 FACTUAL_INTENT 但不匹配 HEURISTIC 的查询
        assertFalse(RealtimeQueryDetector.analyze("2022年总统选举", YearMonth.of(2025, 6)).needsSearch());
    }

    @Test
    void testLocationOnlyMatch() {
        assertTrue(RealtimeQueryDetector.analyze("杭州市", YearMonth.of(2025, 6)).needsSearch());
        assertTrue(RealtimeQueryDetector.analyze("海淀区", YearMonth.of(2025, 6)).needsSearch());
        assertTrue(RealtimeQueryDetector.analyze("广东省", YearMonth.of(2025, 6)).needsSearch());
    }

    @Test
    void testLocationOnlyNoMatch() {
        // 纯城市名（不带市/区/县）不匹配 LOCATION_ONLY 也不匹配 HEURISTIC
        assertFalse(RealtimeQueryDetector.analyze("杭州", YearMonth.of(2025, 6)).needsSearch());
        // 不带区县名的英文地名也不匹配
        assertFalse(RealtimeQueryDetector.analyze("Beijing", YearMonth.of(2025, 6)).needsSearch());
    }

    // ================ likelyNeedsWeather() ================

    @ParameterizedTest
    @ValueSource(strings = {
            "今天天气怎么样",
            "北京气温多少",
            "会下雨吗",
            "台风来了吗",
            "湿度多少",
            "风力几级",
            "杭州市",
            "海淀区",
    })
    void testLikelyNeedsWeatherYes(String input) {
        assertTrue(RealtimeQueryDetector.likelyNeedsWeather(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "你好",
            "讲个笑话",
            "杭州",
            "今天过得怎么样",
    })
    void testLikelyNeedsWeatherNo(String input) {
        assertFalse(RealtimeQueryDetector.likelyNeedsWeather(input));
    }

    // ================ extractWeatherCity() ================

    @Test
    void testExtractWeatherCity() {
        assertEquals("北京", RealtimeQueryDetector.extractWeatherCity("北京天气"));
        assertEquals("杭州", RealtimeQueryDetector.extractWeatherCity("明天杭州下雨吗"));
        assertEquals("广州市", RealtimeQueryDetector.extractWeatherCity("广州市气温"));
    }

    @Test
    void testExtractWeatherCityLocationOnly() {
        assertEquals("杭州市", RealtimeQueryDetector.extractWeatherCity("杭州市"));
        assertEquals("海淀区", RealtimeQueryDetector.extractWeatherCity("海淀区"));
    }

    @Test
    void testExtractWeatherCityNull() {
        assertNull(RealtimeQueryDetector.extractWeatherCity(null));
        assertNull(RealtimeQueryDetector.extractWeatherCity("今天过得怎么样"));
    }

    // ================ guessWeatherExtensions() ================

    @Test
    void testGuessWeatherExtensions() {
        assertEquals("all", RealtimeQueryDetector.guessWeatherExtensions("明天天气"));
        assertEquals("all", RealtimeQueryDetector.guessWeatherExtensions("后天"));
        assertEquals("base", RealtimeQueryDetector.guessWeatherExtensions("现在天气"));
        assertEquals("all", RealtimeQueryDetector.guessWeatherExtensions("你好"));
    }

    // ================ buildSearchQuery() ================

    @Test
    void testBuildSearchQueryLocation() {
        assertTrue(RealtimeQueryDetector.buildSearchQuery("杭州市").contains("今天天气"));
    }

    @Test
    void testBuildSearchQuerySports() {
        String query = RealtimeQueryDetector.buildSearchQuery("世界杯冠军是谁");
        assertTrue(query.contains("决赛结果") || query.contains("冠军是谁") || query.contains("World Cup"));
    }

    // ================ resolveFreshness() ================

    @Test
    void testResolveFreshness() {
        assertEquals("oneDay", RealtimeQueryDetector.resolveFreshness(null, "今天新闻"));
        assertEquals("oneDay", RealtimeQueryDetector.resolveFreshness(null, "最新消息"));
        // 世界杯赛果触发 sports 收紧：oneWeek → oneDay
        assertEquals("oneDay", RealtimeQueryDetector.resolveFreshness(null, "世界杯赛果"));
        // 夺冠也触发 sports 收紧
        assertEquals("oneDay", RealtimeQueryDetector.resolveFreshness(null, "夺冠"));
        assertEquals("custom", RealtimeQueryDetector.resolveFreshness("custom", "你好"));
    }

    @Test
    void testResolveFreshnessDefault() {
        assertEquals("oneWeek", RealtimeQueryDetector.resolveFreshness(null, "你好"));
    }

    // ================ guessFreshness() ================

    @Test
    void testGuessFreshness() {
        assertEquals("oneDay", RealtimeQueryDetector.guessFreshness("今天天气"));
        assertEquals("oneDay", RealtimeQueryDetector.guessFreshness("最新新闻"));
        assertEquals("oneWeek", RealtimeQueryDetector.guessFreshness("世界杯决赛"));
        assertNull(RealtimeQueryDetector.guessFreshness("你好"));
        assertNull(RealtimeQueryDetector.guessFreshness(null));
    }

    // ================ enhanceSearchQuery() ================

    @Test
    void testEnhanceSearchQuery() {
        String result = RealtimeQueryDetector.enhanceSearchQuery("世界杯冠军", "世界杯冠军是谁");
        assertTrue(result.contains("决赛结果") || result.contains("冠军是谁") || result.contains("World Cup"));
    }

    @Test
    void testEnhanceSearchQueryNoSports() {
        assertEquals("今天天气", RealtimeQueryDetector.enhanceSearchQuery("今天天气", "今天天气"));
    }

    @Test
    void testEnhanceSearchQueryNullQuery() {
        String result = RealtimeQueryDetector.enhanceSearchQuery(null, "世界杯冠军是谁");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }
}
