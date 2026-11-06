package com.github.wechat.cloudbot.modules.voice.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import com.github.wechat.cloudbot.platform.persistence.PersistencePaths;
import com.github.wechat.cloudbot.modules.voice.infra.SilkAudioConverter;
import com.github.wechat.cloudbot.modules.voice.infra.VoiceCloneClient;
import com.github.wechat.cloudbot.modules.voice.infra.VoiceProperties;
import jakarta.annotation.PostConstruct;
import com.github.wechat.cloudbot.platform.config.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 音色偏好：系统音色切换（按用户落盘）+ 自定义音色复刻。
 */
@Service
public class VoiceCloneService {

    private static final Logger log = LoggerFactory.getLogger(VoiceCloneService.class);

    private static final Pattern ARM =
            Pattern.compile(
                    "^\\s*(自定义音色|克隆音色|录制音色|设置音色)\\s*[。.!！]?\\s*$");
    private static final Pattern CLEAR =
            Pattern.compile(
                    "^\\s*(恢复默认音色|取消自定义音色|清除自定义音色|用系统音色)\\s*[。.!！]?\\s*$");
    /** 查询本 Bot 可用音色（勿走联网搜索）。 */
    private static final Pattern STATUS =
            Pattern.compile(
                    "^\\s*(当前音色|我的音色|现有的?音色|可用音色|音色列表)\\s*[？?呀啊呢吗。.!！]*\\s*$"
                            + "|^\\s*音色\\s*[？?呀啊呢吗。.!！]*\\s*$"
                            + "|^\\s*.{0,12}(有几种音色|有哪些音色|几种音色|哪些音色).{0,8}\\s*$");
    private static final Pattern SWITCH_LIST =
            Pattern.compile("^\\s*(切换音色|换音色|系统音色)\\s*[？?]?\\s*$");
    private static final Pattern SWITCH =
            Pattern.compile("^\\s*(切换音色|换音色|系统音色)\\s*[:：]\\s*(.+?)\\s*$");

    /**
     * 朗读稿池：正常语速约 3.5 字/秒，每段约 55～75 字 ≈ 15～22 秒，覆盖 5～20 秒要求。
     */
    private static final String[] READING_SCRIPTS = {
        "今天天气不错，我正在用正常语速朗读这段文字，用来录制专属音色。"
                + "请保持环境安静，口齿清晰，一口气读完这一整段就可以了。",
        "你好，这是一段用于声音复刻的朗读材料。我会用平稳自然的语速把句子说清楚，"
                + "中间不刻意停顿太久，也不故意加快，方便系统采集我的声音特征。",
        "请跟我一起完成音色采样：先深吸一口气，再用日常说话的感觉朗读。"
                + "内容不需要表演，只要真实、清楚、连贯，大约十几秒就足够了。",
        "声音是每个人独特的名片。我现在用平日聊天的语气朗读，不大声也不含糊，"
                + "希望这段录音能够顺利生成我的自定义语音回复音色。",
        "测试一、二、三。窗外的风轻轻吹过树叶，远处传来隐约的鸟鸣。"
                + "我会把这段话完整读完，作为本次自定义音色的标准样本。"
    };

    private final ConcurrentHashMap<String, Long> pendingUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> pendingScripts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StoredCustomVoice> voices = new ConcurrentHashMap<>();
    /** 每用户选择的系统音色（覆盖 yml 默认）。 */
    private final ConcurrentHashMap<String, String> systemVoiceByUser = new ConcurrentHashMap<>();

    private final VoiceProperties voiceProperties;
    private final CloudbotProperties cloudbotProperties;
    private final VoiceCloneClient voiceCloneClient;
    private final ObjectMapper objectMapper;

    public VoiceCloneService(
            VoiceProperties voiceProperties,
            CloudbotProperties cloudbotProperties,
            VoiceCloneClient voiceCloneClient,
            ObjectMapper objectMapper) {
        this.voiceProperties = voiceProperties;
        this.cloudbotProperties = cloudbotProperties;
        this.voiceCloneClient = voiceCloneClient;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadFromDisk() {
        if (!cloudbotProperties.getPersistence().isEnabled()) {
            return;
        }
        Path customFile = PersistencePaths.customVoicesFile(cloudbotProperties);
        if (Files.isRegularFile(customFile)) {
            try {
                if (Files.size(customFile) > 0L) {
                    Map<String, StoredCustomVoice> loaded =
                            objectMapper.readValue(customFile.toFile(), new TypeReference<>() {});
                    if (loaded != null && !loaded.isEmpty()) {
                        voices.clear();
                        voices.putAll(loaded);
                        log.info("已加载自定义音色 users={}, file={}", voices.size(), customFile);
                    }
                }
            } catch (Exception e) {
                log.warn("加载自定义音色失败: {}", e.getMessage());
            }
        }
        Path systemFile = PersistencePaths.systemVoicesFile(cloudbotProperties);
        if (Files.isRegularFile(systemFile)) {
            try {
                if (Files.size(systemFile) > 0L) {
                    Map<String, String> loaded =
                            objectMapper.readValue(systemFile.toFile(), new TypeReference<>() {});
                    if (loaded != null && !loaded.isEmpty()) {
                        systemVoiceByUser.clear();
                        systemVoiceByUser.putAll(loaded);
                        log.info(
                                "已加载系统音色偏好 users={}, file={}",
                                systemVoiceByUser.size(),
                                systemFile);
                    }
                }
            } catch (Exception e) {
                log.warn("加载系统音色偏好失败: {}", e.getMessage());
            }
        }
    }

    public boolean isEnabled() {
        return voiceProperties.isVoiceCloneEnabled();
    }

    /** 当前用户生效的系统音色（自定义复刻未启用时用于 TTS）。 */
    public String resolveSystemVoice(String userId) {
        if (userId != null) {
            String override = systemVoiceByUser.get(userId);
            if (override != null && !override.isBlank()) {
                return override.trim();
            }
        }
        String def = voiceProperties.getTtsVoice();
        return def == null || def.isBlank() ? "Cherry" : def.trim();
    }

    public boolean looksLikeCommand(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        if (STATUS.matcher(t).matches()
                || SWITCH_LIST.matcher(t).matches()
                || SWITCH.matcher(t).matches()
                || CLEAR.matcher(t).matches()) {
            return true;
        }
        if (!isEnabled()) {
            return false;
        }
        return ARM.matcher(t).matches();
    }

    /** 处理文本口令；非口令返回 empty。 */
    public Optional<String> tryHandleTextCommand(String userId, String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.trim();
        if (STATUS.matcher(t).matches()) {
            return Optional.of(buildStatusMessage(userId));
        }
        if (SWITCH_LIST.matcher(t).matches()) {
            return Optional.of(
                    SystemVoiceCatalog.listHint()
                            + "\n\n当前系统音色："
                            + SystemVoiceCatalog.describe(resolveSystemVoice(userId)));
        }
        var switchMatcher = SWITCH.matcher(t);
        if (switchMatcher.matches()) {
            return Optional.of(switchSystemVoice(userId, switchMatcher.group(2)));
        }
        if (CLEAR.matcher(t).matches()) {
            pendingUntil.remove(userId);
            pendingScripts.remove(userId);
            voices.remove(userId);
            systemVoiceByUser.remove(userId);
            persistCustom();
            persistSystem();
            return Optional.of(
                    "已恢复默认系统音色（"
                            + resolveSystemVoice(userId)
                            + "）。自定义音色与个人系统音色偏好已清除。");
        }
        if (!isEnabled()) {
            return Optional.empty();
        }
        if (ARM.matcher(t).matches()) {
            long until =
                    System.currentTimeMillis()
                            + Math.max(10_000L, voiceProperties.getVoiceClonePendingMs());
            pendingUntil.put(userId, until);
            String script = pickReadingScript(userId);
            pendingScripts.put(userId, script);
            int waitSec = (int) (voiceProperties.getVoiceClonePendingMs() / 1000);
            return Optional.of(
                    "好的，已进入自定义音色模式（"
                            + waitSec
                            + " 秒内有效）。\n\n"
                            + "请用【正常语速】一口气朗读下面整段文字，然后直接发这条语音给我"
                            + "（安静环境、口齿清晰；读完整段大约十几秒，避免太短被拒）：\n\n"
                            + "「"
                            + script
                            + "」\n\n"
                            + "录好后直接发【语音】即可。若语音解码失败，也可发 wav/mp3【文件】。"
                            + "发「恢复默认音色」可切回系统音色。");
        }
        return Optional.empty();
    }

    private String switchSystemVoice(String userId, String rawName) {
        var opt = SystemVoiceCatalog.find(rawName);
        if (opt.isEmpty()) {
            return "未识别音色「"
                    + rawName.trim()
                    + "」。\n\n"
                    + SystemVoiceCatalog.listHint();
        }
        SystemVoiceCatalog.VoiceOption voice = opt.get();
        systemVoiceByUser.put(userId, voice.id());
        // 系统音色要立刻生效：清掉自定义复刻
        boolean clearedCustom = voices.remove(userId) != null;
        persistSystem();
        if (clearedCustom) {
            persistCustom();
        }
        String msg =
                "已切换系统音色为 "
                        + SystemVoiceCatalog.describe(voice.id())
                        + "。之后语音回复会用这个声音。";
        if (clearedCustom) {
            msg += "（已取消你的自定义音色）";
        }
        return msg;
    }

    private String buildStatusMessage(String userId) {
        String systemVoice = resolveSystemVoice(userId);
        StringBuilder sb = new StringBuilder();
        sb.append("本 Bot 音色状态：\n");
        sb.append("1. 系统音色：").append(SystemVoiceCatalog.describe(systemVoice));
        if (voiceProperties.getTtsModel() != null && !voiceProperties.getTtsModel().isBlank()) {
            sb.append("\n   模型：").append(voiceProperties.getTtsModel().trim());
        }
        sb.append('\n');

        StoredCustomVoice custom = userId == null ? null : voices.get(userId);
        if (custom != null && custom.voiceId() != null && !custom.voiceId().isBlank()) {
            sb.append("2. 你的自定义音色：")
                    .append(custom.voiceId())
                    .append("（模型 ")
                    .append(custom.targetModel())
                    .append('）');
            if (custom.createdAt() != null && !custom.createdAt().isBlank()) {
                sb.append("，创建于 ").append(custom.createdAt());
            }
            sb.append('\n');
            sb.append("\n当前使用：自定义音色（优先于系统音色）");
        } else {
            sb.append("2. 自定义音色：尚未设置");
            if (isEnabled()) {
                sb.append("（发「自定义音色」可录制）");
            }
            sb.append('\n');
            sb.append("\n当前使用：系统音色 ").append(systemVoice);
        }

        sb.append("\n\n口令：切换音色 / 切换音色：Ethan / 自定义音色 / 恢复默认音色 / 当前音色");
        return sb.toString();
    }

    public boolean isPending(String userId) {
        if (userId == null) {
            return false;
        }
        Long until = pendingUntil.get(userId);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() > until) {
            pendingUntil.remove(userId);
            pendingScripts.remove(userId);
            return false;
        }
        return true;
    }

    public Optional<StoredCustomVoice> getCustomVoice(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        StoredCustomVoice v = voices.get(userId);
        if (v == null || v.voiceId() == null || v.voiceId().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(v);
    }

    /**
     * 用语音样本完成复刻；成功后取消 pending。
     *
     * @return 给用户的确认文案
     */
    public String enrollFromWeChatVoice(String userId, byte[] rawAudio) {
        return enrollFromWeChatVoice(userId, rawAudio, -1, -1);
    }

    /**
     * @param playtimeHintMs 微信 VoiceItem.playtime 换算后的毫秒；未知传 -1
     * @param sampleRateHint 微信 VoiceItem.sample_rate；未知传 -1
     */
    public String enrollFromWeChatVoice(
            String userId, byte[] rawAudio, int playtimeHintMs, int sampleRateHint) {
        if (!isEnabled()) {
            return "自定义音色功能未开启。";
        }
        if (rawAudio == null || rawAudio.length == 0) {
            return "没收到有效语音，请再发一段语音或 wav/mp3 文件（口令仍有效）。";
        }

        try {
            int minMs = Math.max(3000, voiceProperties.getVoiceCloneMinMs());
            // SILK 字节数无法可靠估时长；仅在有平台 playtime 时做预检
            if (playtimeHintMs > 0 && playtimeHintMs < minMs) {
                return "这段语音大约只有 "
                        + (playtimeHintMs / 1000.0)
                        + " 秒，有点短。请再录一段更长、更清晰的（建议 "
                        + (minMs / 1000)
                        + "～20 秒）直接发过来即可，无需重新发口令。";
            }

            int rate =
                    sampleRateHint > 0
                            ? sampleRateHint
                            : voiceProperties.getSampleRate();
            byte[] wav = SilkAudioConverter.toWavForEnrollment(rawAudio, rate);
            int wavMs = SilkAudioConverter.estimateDurationMs(wav, rate);
            if (wavMs > 0 && wavMs < minMs) {
                return "解码后时长约 "
                        + (wavMs / 1000.0)
                        + " 秒，仍偏短。请再录更长一段直接发过来即可。";
            }

            String preferred = preferredNameFor(userId);
            String voiceId = voiceCloneClient.enroll(wav, preferred);
            String model = voiceProperties.getVoiceCloneModel();

            voices.put(
                    userId,
                    new StoredCustomVoice(voiceId, model, Instant.now().toString()));
            pendingUntil.remove(userId);
            pendingScripts.remove(userId);
            persistCustom();
            return "自定义音色已就绪！之后语音回复会用你的声音。"
                    + "发「恢复默认音色」可切回系统音色；发「切换音色：Ethan」可换系统音色。";
        } catch (Exception e) {
            log.error("{}复刻失败 userId={}: {}", ErrorCode.VOICE_CLONE_ERROR.prefix(), userId, e.getMessage(), e);
            // 保留 pending，方便用户立刻再试（发语音或 wav/mp3 文件）
            return "音色复刻失败："
                    + e.getMessage()
                    + "。可再发一段更长语音，或发 wav/mp3 文件；口令仍有效。";
        }
    }

    /** preferred_name：字母数字下划线，最长 16。 */
    private static String preferredNameFor(String userId) {
        String hex = Integer.toHexString(Math.abs(userId == null ? 0 : userId.hashCode()));
        String time = Long.toHexString(System.currentTimeMillis() & 0xFFFFFL);
        String name = ("u" + hex + time).replaceAll("[^a-zA-Z0-9_]", "").toLowerCase();
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }
        return name.isBlank() ? "wxvoice" : name;
    }

    private static String pickReadingScript(String userId) {
        int idx =
                Math.floorMod(
                        (userId == null ? 0 : userId.hashCode())
                                ^ (int) (System.currentTimeMillis() / 1000),
                        READING_SCRIPTS.length);
        return READING_SCRIPTS[idx];
    }

    private void persistCustom() {
        if (!cloudbotProperties.getPersistence().isEnabled()) {
            return;
        }
        Path file = PersistencePaths.customVoicesFile(cloudbotProperties);
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), Map.copyOf(voices));
        } catch (Exception e) {
            log.warn("写入自定义音色失败: {}", e.getMessage());
        }
    }

    private void persistSystem() {
        if (!cloudbotProperties.getPersistence().isEnabled()) {
            return;
        }
        Path file = PersistencePaths.systemVoicesFile(cloudbotProperties);
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), Map.copyOf(systemVoiceByUser));
        } catch (Exception e) {
            log.warn("写入系统音色偏好失败: {}", e.getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StoredCustomVoice(String voiceId, String targetModel, String createdAt) {}
}
