package com.github.wechat.cloudbot.modules.health.checkin.infra;

import java.util.Locale;

/**
 * 统一健康提醒注册表：喝水 / 运动 / 早睡 / 复查 / 自定义。
 *
 * <p>预留扩展点：新增提醒类型只需新增一个枚举常量（key / label / 默认时间 / 文案 / emoji）。
 * 前三类（drink_water / exercise / sleep_early）同时是健康打卡类型，提醒文案带打卡引导。</p>
 */
public enum HealthReminderKind {

    DRINK_WATER("drink_water", "喝水", "09:00", "🚰",
            "🚰 日常提醒：该喝水啦，记得补充水分～ 喝完回复「喝水打卡」让我记录一下。"),
    EXERCISE("exercise", "运动", "19:00", "🏃",
            "🏃 日常提醒：该活动活动啦，规律运动更健康～ 运动完回复「运动打卡」让我记录一下。"),
    SLEEP_EARLY("sleep_early", "早睡", "21:30", "🌙",
            "🌙 日常提醒：该休息啦，尽量早点睡～ 睡前来个「早睡打卡」让我记录一下。"),
    FOLLOWUP("followup", "复查", "09:00", "📅",
            "📅 复查提醒：记得按时复查，如有不适及时就医～"),
    CUSTOM("custom", "自定义", null, "📌", null);

    private final String key;
    private final String label;
    /** 缺省提醒时间（HH:mm），用户未指定时间时的预留默认值。 */
    private final String defaultTime;
    private final String emoji;
    /** 预设文案；custom 为 null，由调用方提供自定义内容。 */
    private final String contentTemplate;

    HealthReminderKind(String key, String label, String defaultTime, String emoji, String contentTemplate) {
        this.key = key;
        this.label = label;
        this.defaultTime = defaultTime;
        this.emoji = emoji;
        this.contentTemplate = contentTemplate;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String defaultTime() {
        return defaultTime;
    }

    public String emoji() {
        return emoji;
    }

    public String contentTemplate() {
        return contentTemplate;
    }

    /** 是否为健康打卡类型（喝水 / 运动 / 早睡）。 */
    public boolean isCheckinKind() {
        return this == DRINK_WATER || this == EXERCISE || this == SLEEP_EARLY;
    }

    /**
     * 按 key 解析，容错：大小写不敏感，兼容中文 label。
     *
     * @throws IllegalArgumentException 类型不支持时
     */
    public static HealthReminderKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("提醒类型不能为空");
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        for (HealthReminderKind k : values()) {
            if (k.key.equals(s) || k.label.equals(s)) {
                return k;
            }
        }
        throw new IllegalArgumentException("不支持的提醒类型：" + raw
                + "（可选：" + supportedKeys() + "）");
    }

    /** 支持的全部 key，供话术提示使用。 */
    public static String supportedKeys() {
        return "drink_water/exercise/sleep_early/followup/custom";
    }
}
