package com.github.wechat.cloudbot.modules.chat.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TopicShiftDetectorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "换个话题吧",
            "换话题",
            "我们换个话题",
            "新话题",
            "说点别的",
            "说别的",
            "不说这个了",
            "换件事",
            "另一个问题",
            "换个问题",
    })
    void testExplicitNewTopic(String input) {
        assertTrue(TopicShiftDetector.isExplicitNewTopic(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "今天天气怎么样",
            "讲个笑话",
            "刚才说的那个",
            "这个是什么意思",
            "挺好的",
            "继续",
            "然后呢",
            "为什么",
    })
    void testNotExplicitNewTopic(String input) {
        assertFalse(TopicShiftDetector.isExplicitNewTopic(input));
    }

    @Test
    void testBlankInput() {
        assertFalse(TopicShiftDetector.isExplicitNewTopic(""));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "换个话题，我们聊聊电影",
            "我想换个话题，说说别的",
            "新话题：关于AI",
    })
    void testExplicitNewTopicWithContext(String input) {
        assertTrue(TopicShiftDetector.isExplicitNewTopic(input));
    }
}
