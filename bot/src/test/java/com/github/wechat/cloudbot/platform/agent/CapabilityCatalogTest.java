package com.github.wechat.cloudbot.platform.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityCatalogTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "你现在都有些什么功能",
                "有什么功能",
                "你会什么",
                "能做什么",
                "帮助",
                "菜单",
                "功能列表"
            })
    void detectsCapabilityQuestions(String q) {
        assertTrue(CapabilityCatalog.looksLikeCapabilityQuery(q));
    }

    @Test
    void rejectsOrdinaryChat() {
        assertFalse(CapabilityCatalog.looksLikeCapabilityQuery("杭州明天天气怎么样"));
        assertFalse(CapabilityCatalog.looksLikeCapabilityQuery("帮我算一下 1+1"));
    }

    @Test
    void summaryMentionsCoreFeatures() {
        String s = CapabilityCatalog.userFacingSummary();
        assertTrue(s.contains("天气"));
        assertTrue(s.contains("变身"));
        assertTrue(s.contains("文生图"));
        assertTrue(s.contains("提醒"));
    }

    @Test
    void summaryWithEnabledToolsReturnsSameContent() {
        // 动态工具列表已移除，传 null 和传具体列表应返回相同内容
        String withoutTools = CapabilityCatalog.userFacingSummary(null);
        String withTools = CapabilityCatalog.userFacingSummary(List.of("web_search", "fun_joke"));
        assertTrue(withoutTools.contains("天气"));
        assertTrue(withoutTools.contains("健康管家"));
        assertEquals(withoutTools, withTools);
    }

    @Test
    void summaryMentionsHealthCapabilities() {
        String s = CapabilityCatalog.userFacingSummary();
        assertTrue(s.contains("建档"));
        assertTrue(s.contains("导诊"));
        assertTrue(s.contains("报告解析"));
        assertTrue(s.contains("用药提醒"));
        assertTrue(s.contains("体检计划"));
        assertTrue(s.contains("每日打卡"));
    }
}
