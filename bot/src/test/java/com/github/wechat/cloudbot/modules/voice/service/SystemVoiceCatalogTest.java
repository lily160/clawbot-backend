package com.github.wechat.cloudbot.modules.voice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SystemVoiceCatalogTest {

    @Test
    void testFindById() {
        Optional<SystemVoiceCatalog.VoiceOption> opt = SystemVoiceCatalog.find("Cherry");
        assertTrue(opt.isPresent());
        assertEquals("芊悦", opt.get().displayName());
        assertEquals("女", opt.get().gender());
    }

    @Test
    void testFindByDisplayName() {
        Optional<SystemVoiceCatalog.VoiceOption> opt = SystemVoiceCatalog.find("芊悦");
        assertTrue(opt.isPresent());
        assertEquals("Cherry", opt.get().id());
    }

    @ParameterizedTest
    @CsvSource({
            "Ethan, 晨煦",
            "Serena, 苏瑶",
            "Chelsie, 千雪",
            "Momo, 茉兔",
    })
    void testFindKnownVoices(String id, String displayName) {
        Optional<SystemVoiceCatalog.VoiceOption> opt = SystemVoiceCatalog.find(id);
        assertTrue(opt.isPresent());
        assertEquals(displayName, opt.get().displayName());
    }

    @Test
    void testFindCaseInsensitive() {
        assertTrue(SystemVoiceCatalog.find("cherry").isPresent());
        assertTrue(SystemVoiceCatalog.find("CHERRY").isPresent());
    }

    @Test
    void testFindUnknown() {
        assertTrue(SystemVoiceCatalog.find("unknown_voice").isEmpty());
    }

    @Test
    void testFindNull() {
        assertTrue(SystemVoiceCatalog.find(null).isEmpty());
    }

    @Test
    void testFindBlank() {
        assertTrue(SystemVoiceCatalog.find(" ").isEmpty());
    }

    @Test
    void testIsKnown() {
        assertTrue(SystemVoiceCatalog.isKnown("Cherry"));
        assertTrue(SystemVoiceCatalog.isKnown("芊悦"));
        assertFalse(SystemVoiceCatalog.isKnown("unknown"));
        assertFalse(SystemVoiceCatalog.isKnown(null));
    }

    @Test
    void testListHint() {
        String hint = SystemVoiceCatalog.listHint();
        assertTrue(hint.contains("Cherry"));
        assertTrue(hint.contains("芊悦"));
        assertTrue(hint.contains("切换音色"));
        assertTrue(hint.contains("粤语"));
    }

    @Test
    void testDescribeKnown() {
        String desc = SystemVoiceCatalog.describe("Cherry");
        assertTrue(desc.contains("Cherry"));
        assertTrue(desc.contains("芊悦"));
    }

    @Test
    void testDescribeUnknown() {
        assertEquals("", SystemVoiceCatalog.describe(null));
        assertEquals("unknown", SystemVoiceCatalog.describe("unknown"));
    }
}
