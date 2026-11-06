package com.github.wechat.cloudbot.modules.vision.infra;

import java.util.Base64;

/**
 * 图片字节转 data URL，供千问 VL 多模态接口使用。
 */
public final class ImageDataUrls {

    private ImageDataUrls() {}

    public static String toDataUrl(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException("image bytes empty");
        }
        String mime = detectMime(imageBytes);
        String b64 = Base64.getEncoder().encodeToString(imageBytes);
        return "data:" + mime + ";base64," + b64;
    }

    public static String detectMime(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47) {
            return "image/png";
        }
        if (bytes.length >= 6
                && bytes[0] == 'G'
                && bytes[1] == 'I'
                && bytes[2] == 'F') {
            return "image/gif";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "image/webp";
        }
        // 微信图片常见为 jpeg；无法识别时按 jpeg 提交
        return "image/jpeg";
    }
}
