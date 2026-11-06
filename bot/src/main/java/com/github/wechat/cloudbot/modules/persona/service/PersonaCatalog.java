package com.github.wechat.cloudbot.modules.persona.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 人格切换：仅替换 system prompt，不增加新能力。
 */
public final class PersonaCatalog {

    public static final String DEFAULT_ID = "默认";

    private static final Map<String, String> PERSONAS = new LinkedHashMap<>();

    /** 各人格对应的 TTS 语气指令（配合 qwen3-tts-instruct-flash）。 */
    private static final Map<String, String> TTS_INSTRUCTIONS = new LinkedHashMap<>();

    static {
        PERSONAS.put(
                "毒舌室友",
                "你是用户的毒舌室友，说话犀利吐槽但不恶意人身攻击。回复简短有梗，适合微信即时消息。");
        PERSONAS.put(
                "古风仙人",
                "你是一位古风仙人，说话带文言韵味，温文尔雅、点到为止。回复简洁，适合微信即时消息。");
        PERSONAS.put(
                "赛博猫",
                "你是一只赛博猫助手，语气俏皮、偶尔卖萌，喜欢用轻量猫科比喻。回复简洁，适合微信即时消息。");
        PERSONAS.put(
                "知心女友",
                "你是用户善良、温柔、知心的女友：体贴倾听、情绪稳定、会安慰也会鼓励。"
                        + "语气软而不矫情，关心对方感受。回复简洁自然，适合微信即时消息。");
        PERSONAS.put(
                "优秀男友",
                "你是用户可靠、优秀的男友：成熟稳重、有担当，会给出清晰建议也懂得陪伴。"
                        + "语气温暖有分寸，不油腻。回复简洁自然，适合微信即时消息。");

        TTS_INSTRUCTIONS.put(
                "毒舌室友",
                "语速稍快，语气犀利吐槽、带损友感，但不刺耳不凶，适合微信短语音。");
        TTS_INSTRUCTIONS.put(
                "古风仙人",
                "语速偏慢，语气温文尔雅、从容淡定，略带文言韵味，适合微信短语音。");
        TTS_INSTRUCTIONS.put(
                "赛博猫",
                "语速轻快，语气俏皮卖萌、活泼灵动，像电子宠物猫在聊天，适合微信短语音。");
        TTS_INSTRUCTIONS.put(
                "知心女友",
                "语速柔和，语气温柔体贴、安抚鼓励，软而不矫情，适合微信短语音。");
        TTS_INSTRUCTIONS.put(
                "优秀男友",
                "语速稳健，语气温暖可靠、有担当感，自然不油腻，适合微信短语音。");
    }

    private PersonaCatalog() {}

    public static Optional<String> promptOf(String personaId) {
        if (personaId == null || personaId.isBlank()) {
            return Optional.empty();
        }
        String key = normalizeId(personaId);
        if (DEFAULT_ID.equals(key) || "取消".equals(key) || "复位".equals(key)) {
            return Optional.empty();
        }
        return Optional.ofNullable(PERSONAS.get(key));
    }

    /** 人格对应的 TTS 指令；默认/未知人格返回 empty。 */
    public static Optional<String> ttsInstructionsOf(String personaId) {
        if (personaId == null || personaId.isBlank()) {
            return Optional.empty();
        }
        String key = normalizeId(personaId);
        if (DEFAULT_ID.equals(key) || "取消".equals(key) || "复位".equals(key)) {
            return Optional.empty();
        }
        return Optional.ofNullable(TTS_INSTRUCTIONS.get(key));
    }

    public static boolean isKnown(String personaId) {
        if (personaId == null) {
            return false;
        }
        String key = normalizeId(personaId);
        return DEFAULT_ID.equals(key)
                || "取消".equals(key)
                || "复位".equals(key)
                || PERSONAS.containsKey(key);
    }

    /** 对外暴露的规范人格名（含别名归一）。 */
    public static String canonicalId(String personaId) {
        return normalizeId(personaId);
    }

    /** 别名归一，方便口语化称呼。 */
    private static String normalizeId(String personaId) {
        String key = personaId.trim();
        return switch (key) {
            case "善良温柔知心的女友", "温柔女友", "女友" -> "知心女友";
            case "男友", "完美男友" -> "优秀男友";
            default -> key;
        };
    }

    public static String listHint() {
        return "可用人格：毒舌室友 / 古风仙人 / 赛博猫 / 知心女友 / 优秀男友；"
                + "发「变身：默认」恢复。示例：变身：知心女友";
    }
}
