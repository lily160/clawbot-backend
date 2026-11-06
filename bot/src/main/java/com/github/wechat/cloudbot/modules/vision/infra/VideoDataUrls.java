package com.github.wechat.cloudbot.modules.vision.infra;

import java.util.Base64;

/**
 * 视频字节转 data URL，供千问 VL {@code video_url} 多模态接口使用。
 */
public final class VideoDataUrls {

    private VideoDataUrls() {}

    public static String toDataUrl(byte[] videoBytes) {
        if (videoBytes == null || videoBytes.length == 0) {
            throw new IllegalArgumentException("video bytes empty");
        }
        String mime = detectMime(videoBytes);
        String b64 = Base64.getEncoder().encodeToString(videoBytes);
        return "data:" + mime + ";base64," + b64;
    }

    public static String detectMime(byte[] bytes) {
        // ISO BMFF (mp4/mov): ....ftyp
        if (bytes.length >= 8
                && bytes[4] == 'f'
                && bytes[5] == 't'
                && bytes[6] == 'y'
                && bytes[7] == 'p') {
            return "video/mp4";
        }
        // WebM / Matroska: 0x1A45DFA3
        if (bytes.length >= 4
                && (bytes[0] & 0xFF) == 0x1A
                && (bytes[1] & 0xFF) == 0x45
                && (bytes[2] & 0xFF) == 0xDF
                && (bytes[3] & 0xFF) == 0xA3) {
            return "video/webm";
        }
        // 微信短视频多为 mp4
        return "video/mp4";
    }
}
