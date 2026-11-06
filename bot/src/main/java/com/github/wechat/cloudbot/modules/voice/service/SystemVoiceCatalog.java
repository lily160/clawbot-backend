package com.github.wechat.cloudbot.modules.voice.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Qwen3-TTS（含 Instruct-Flash）常用系统音色目录，供用户端切换。
 *
 * <p>完整官方列表见：https://help.aliyun.com/zh/model-studio/qwen-tts-voice-list
 */
public final class SystemVoiceCatalog {

    public record VoiceOption(String id, String displayName, String gender, String blurb) {}

    private static final Map<String, VoiceOption> BY_ID = new LinkedHashMap<>();
    private static final Map<String, String> ALIAS_TO_ID = new LinkedHashMap<>();

    static {
        add("Cherry", "芊悦", "女", "阳光亲切");
        add("Serena", "苏瑶", "女", "温柔小姐姐");
        add("Ethan", "晨煦", "男", "阳光温暖");
        add("Chelsie", "千雪", "女", "二次元虚拟女友");
        add("Momo", "茉兔", "女", "撒娇搞怪");
        add("Vivian", "十三", "女", "拽拽小暴躁");
        add("Moon", "月白", "男", "率性帅气");
        add("Maia", "四月", "女", "知性温柔");
        add("Kai", "凯", "男", "磁性舒缓");
        add("Nofish", "不吃鱼", "男", "设计师口音");
        add("Bella", "萌宝", "女", "小萝莉");
        add("Mia", "乖小妹", "女", "温顺乖巧");
        add("Bunny", "萌小姬", "女", "萌系萝莉");
        add("Vincent", "田叔", "男", "沙哑烟嗓");
        add("Neil", "阿闻", "男", "新闻主持");
        add("Stella", "少女阿月", "女", "迷糊少女");
        add("Dylan", "晓东", "男", "北京话");
        add("Sunny", "晴儿", "女", "四川话");
        add("Rocky", "阿强", "男", "粤语");
        add("Kiki", "阿清", "女", "粤语港妹");
    }

    private SystemVoiceCatalog() {}

    private static void add(String id, String displayName, String gender, String blurb) {
        BY_ID.put(id, new VoiceOption(id, displayName, gender, blurb));
        ALIAS_TO_ID.put(id.toLowerCase(Locale.ROOT), id);
        ALIAS_TO_ID.put(displayName, id);
        // 去掉「北京-」这类前缀的常见叫法
        ALIAS_TO_ID.put(displayName.replace("-", ""), id);
    }

    public static Optional<VoiceOption> find(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String key = raw.trim();
        String id = ALIAS_TO_ID.get(key);
        if (id == null) {
            id = ALIAS_TO_ID.get(key.toLowerCase(Locale.ROOT));
        }
        if (id == null) {
            // 兼容「北京-晓东」「粤语-阿强」完整展示名
            for (VoiceOption opt : BY_ID.values()) {
                if (opt.displayName().equalsIgnoreCase(key)
                        || (opt.displayName() + opt.id()).equalsIgnoreCase(key)) {
                    return Optional.of(opt);
                }
            }
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ID.get(id));
    }

    public static boolean isKnown(String raw) {
        return find(raw).isPresent();
    }

    public static String listHint() {
        StringBuilder sb = new StringBuilder();
        sb.append("可切换的系统音色（发「切换音色：名字」）：\n");
        int i = 1;
        for (VoiceOption opt : BY_ID.values()) {
            sb.append(i++)
                    .append(". ")
                    .append(opt.id())
                    .append(" / ")
                    .append(opt.displayName())
                    .append("（")
                    .append(opt.gender())
                    .append("，")
                    .append(opt.blurb())
                    .append("）\n");
        }
        sb.append("\n例：切换音色：Ethan　或　切换音色：晨煦");
        return sb.toString().trim();
    }

    public static String describe(String voiceId) {
        return find(voiceId)
                .map(o -> o.id() + "（" + o.displayName() + "，" + o.gender() + "，" + o.blurb() + "）")
                .orElse(voiceId == null ? "" : voiceId);
    }
}
