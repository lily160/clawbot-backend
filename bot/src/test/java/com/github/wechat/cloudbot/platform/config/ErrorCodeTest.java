package com.github.wechat.cloudbot.platform.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ErrorCodeTest {

    @Test
    void testCode() {
        assertEquals("E001", ErrorCode.PLATFORM_CONFIG.code());
        assertEquals("E101", ErrorCode.CHAT_DISABLED.code());
        assertEquals("E999", ErrorCode.UNEXPECTED.code());
    }

    @Test
    void testPrefix() {
        assertEquals("[E001] ", ErrorCode.PLATFORM_CONFIG.prefix());
        assertEquals("[E999] ", ErrorCode.UNEXPECTED.prefix());
    }

    @Test
    void testFormatMessage() {
        assertEquals("[E102] API call failed",
                ErrorCode.CHAT_API_ERROR.format("API call failed"));
    }

    @Test
    void testFormatWithArgs() {
        assertEquals("[E902] Tool 'search' failed: timeout",
                ErrorCode.AGENT_TOOL_ERROR.format("Tool '%s' failed: %s", "search", "timeout"));
    }

    @ParameterizedTest
    @CsvSource({
            "PLATFORM_CONFIG, E001",
            "PLATFORM_INIT, E002",
            "PLATFORM_AUTH, E003",
            "CHAT_DISABLED, E101",
            "CHAT_API_ERROR, E102",
            "CHAT_PARSE_ERROR, E103",
            "CHAT_EMPTY_CONTENT, E104",
            "SEARCH_DISABLED, E201",
            "SEARCH_API_ERROR, E202",
            "SEARCH_EMPTY_RESULT, E203",
            "WEATHER_DISABLED, E301",
            "WEATHER_API_ERROR, E302",
            "WEATHER_CITY_ERROR, E303",
            "VISION_DISABLED, E401",
            "VISION_API_ERROR, E402",
            "VISION_EMPTY_CONTENT, E403",
            "MEDIA_DISABLED, E501",
            "MEDIA_API_ERROR, E502",
            "MEDIA_DOWNLOAD_ERROR, E503",
            "MEDIA_VIDEO_TIMEOUT, E504",
            "VOICE_CONFIG_ERROR, E601",
            "VOICE_API_ERROR, E602",
            "VOICE_CLONE_ERROR, E603",
            "VOICE_ASR_EMPTY, E604",
            "VOICE_TTS_EMPTY, E605",
            "PERSISTENCE_WRITE_ERROR, E701",
            "PERSISTENCE_READ_ERROR, E702",
            "PERSISTENCE_DIR_ERROR, E703",
            "CHANNEL_SEND_ERROR, E801",
            "CHANNEL_LOGIN_ERROR, E802",
            "CHANNEL_DOWNLOAD_ERROR, E803",
            "AGENT_LOOP_ERROR, E901",
            "AGENT_TOOL_ERROR, E902",
            "AGENT_UNKNOWN_TOOL, E903",
            "UNEXPECTED, E999",
    })
    void testAllErrorCodes(ErrorCode code, String expected) {
        assertEquals(expected, code.code());
        assertEquals("[" + expected + "] ", code.prefix());
    }

    @Test
    void testAllErrorCodesCount() {
        assertEquals(35, ErrorCode.values().length);
    }
}
