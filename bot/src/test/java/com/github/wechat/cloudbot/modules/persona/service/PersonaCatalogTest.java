package com.github.wechat.cloudbot.modules.persona.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PersonaCatalogTest {

    @ParameterizedTest
    @CsvSource({
            "毒舌室友, 毒舌室友",
            "古风仙人, 古风仙人",
            "赛博猫, 赛博猫",
            "知心女友, 知心女友",
            "优秀男友, 优秀男友",
    })
    void testPromptOfKnown(String input, String expectedId) {
        Optional<String> prompt = PersonaCatalog.promptOf(input);
        assertTrue(prompt.isPresent());
        assertTrue(prompt.get().contains(expectedId) || prompt.get().contains("你"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"默认", "取消", "复位", "unknown", ""})
    void testPromptOfDefaultOrUnknown(String input) {
        assertTrue(PersonaCatalog.promptOf(input).isEmpty());
    }

    @Test
    void testPromptOfNull() {
        assertTrue(PersonaCatalog.promptOf(null).isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "善良温柔知心的女友, 知心女友",
            "温柔女友, 知心女友",
            "女友, 知心女友",
            "男友, 优秀男友",
            "完美男友, 优秀男友",
    })
    void testPromptOfAlias(String alias, String canonicalId) {
        Optional<String> prompt = PersonaCatalog.promptOf(alias);
        assertTrue(prompt.isPresent());
    }

    @ParameterizedTest
    @CsvSource({
            "毒舌室友, true",
            "古风仙人, true",
            "赛博猫, true",
            "知心女友, true",
            "优秀男友, true",
            "默认, true",
            "取消, true",
            "复位, true",
            "善良温柔知心的女友, true",
            "unknown, false",
            "'', false",
    })
    void testIsKnown(String input, boolean expected) {
        assertEquals(expected, PersonaCatalog.isKnown(input));
    }

    @Test
    void testIsKnownNull() {
        assertFalse(PersonaCatalog.isKnown(null));
    }

    @Test
    void testCanonicalId() {
        assertEquals("知心女友", PersonaCatalog.canonicalId("女友"));
        assertEquals("优秀男友", PersonaCatalog.canonicalId("男友"));
        assertEquals("毒舌室友", PersonaCatalog.canonicalId("毒舌室友"));
    }

    @Test
    void testTtsInstructions() {
        assertTrue(PersonaCatalog.ttsInstructionsOf("毒舌室友").isPresent());
        assertTrue(PersonaCatalog.ttsInstructionsOf("默认").isEmpty());
        assertTrue(PersonaCatalog.ttsInstructionsOf(null).isEmpty());
    }

    @Test
    void testListHint() {
        String hint = PersonaCatalog.listHint();
        assertTrue(hint.contains("毒舌室友"));
        assertTrue(hint.contains("知心女友"));
        assertTrue(hint.contains("变身"));
    }
}
