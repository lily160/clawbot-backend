package com.github.wechat.cloudbot.modules.voice.infra;

import java.util.Base64;
import java.util.Locale;

/** 语音字节 → data URL / MIME，供千问 ASR。 */
public final class AudioDataUrls {

    private AudioDataUrls() {}

    public static String toDataUrl(byte[] audioBytes) {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IllegalArgumentException("audio bytes empty");
        }
        String mime = detectMime(audioBytes);
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(audioBytes);
    }

    public static String detectMime(byte[] bytes) {
        if (bytes.length >= 4
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F') {
            return "audio/wav";
        }
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && ((bytes[1] & 0xE0) == 0xE0 || (bytes[1] & 0xF0) == 0xF0)) {
            return "audio/mpeg";
        }
        if (bytes.length >= 3 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return "audio/mpeg";
        }
        // AMR: #!AMR
        if (bytes.length >= 5
                && bytes[0] == '#'
                && bytes[1] == '!'
                && bytes[2] == 'A'
                && bytes[3] == 'M'
                && bytes[4] == 'R') {
            return "audio/amr";
        }
        // SILK: #!SILK_V3 或微信常见的 0x02 + #!SILK_V3
        if (containsSilkMagic(bytes)) {
            return "audio/silk";
        }
        return "audio/mpeg";
    }

    public static boolean looksLikeSilk(byte[] bytes) {
        return containsSilkMagic(bytes);
    }

    private static boolean containsSilkMagic(byte[] bytes) {
        String head =
                new String(
                        bytes,
                        0,
                        Math.min(bytes.length, 16),
                        java.nio.charset.StandardCharsets.US_ASCII);
        String upper = head.toUpperCase(Locale.ROOT);
        return upper.contains("#!SILK") || upper.contains("SILK_V3");
    }
}
