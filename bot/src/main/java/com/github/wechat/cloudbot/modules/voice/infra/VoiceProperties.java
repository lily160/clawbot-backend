package com.github.wechat.cloudbot.modules.voice.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语音识别（ASR）与语音合成（TTS），复用 {@code DASHSCOPE_API_KEY}。
 */
@ConfigurationProperties(prefix = "voice")
public class VoiceProperties {

    /** 是否启用：下载语音后走 ASR（平台已有转写时可跳过）。 */
    private boolean asrEnabled = true;

    /** 是否启用 TTS 语音回复（出站）。 */
    private boolean ttsEnabled = true;

    /**
     * 回复形态：
     * <ul>
     *   <li>{@code mirror}：用户发语音则回语音附件，发文字则回文字</li>
     *   <li>{@code always-voice}：尽量总是语音附件</li>
     *   <li>{@code never}：总是文字（仅 ASR）</li>
     * </ul>
     */
    private String replyMode = "mirror";

    /**
     * 出站语音投递方式（Clawbot/iLink 语音气泡常被静默丢弃，默认用文件附件）：
     * <ul>
     *   <li>{@code file}：TTS → {@code sendFile} 音频附件（推荐）</li>
     *   <li>{@code voice}：TTS → SILK → {@code sendVoice} 语音气泡（平台可能不显示）</li>
     * </ul>
     */
    private String outboundMode = "file";

    /**
     * 发音频时是否再单独发一份文字。
     * {@code file} 模式下 caption 已带正文，一般保持 false。
     */
    private boolean alsoSendText = false;

    private String asrModel = "qwen3-asr-flash";

    private String ttsModel = "qwen3-tts-flash";

    /** 音色，如 Cherry / Ethan。 */
    private String ttsVoice = "Cherry";

    private String ttsLanguageType = "Chinese";

    /**
     * 语气/语速等自然语言指令（需配合 {@code qwen3-tts-instruct-flash}）。
     * 为空则不传 instructions。
     */
    private String ttsInstructions = "";

    /** 是否对 instructions 做语义优化（仅 instruct 模型）。 */
    private boolean ttsOptimizeInstructions = true;

    /** TTS 原生 API 根（非 compatible-mode）。 */
    private String ttsApiBaseUrl = "https://dashscope.aliyuncs.com/api/v1";

    /** 是否允许微信发语音复刻自定义音色。 */
    private boolean voiceCloneEnabled = true;

    /** 复刻音色合成模型（须与 enrollment 的 target_model 一致）。 */
    private String voiceCloneModel = "qwen3-tts-vc-2026-01-22";

    /** 发送「自定义音色」后等待语音样本的超时（毫秒）。 */
    private long voiceClonePendingMs = 120000L;

    /** 样本最短时长（毫秒）；过短平台效果差。 */
    private int voiceCloneMinMs = 5000;

    /** sendVoice 的 encode_type；SDK 默认 6=SILK。TTS 输出 WAV 时可改为协议支持的其它值试验。 */
    private int encodeType = 6;

    private int sampleRate = 24000;

    private long connectTimeoutMs = 10000L;

    private long readTimeoutMs = 120000L;

    public boolean isAsrEnabled() {
        return asrEnabled;
    }

    public void setAsrEnabled(boolean asrEnabled) {
        this.asrEnabled = asrEnabled;
    }

    public boolean isTtsEnabled() {
        return ttsEnabled;
    }

    public void setTtsEnabled(boolean ttsEnabled) {
        this.ttsEnabled = ttsEnabled;
    }

    public String getReplyMode() {
        return replyMode;
    }

    public void setReplyMode(String replyMode) {
        this.replyMode = replyMode;
    }

    public String getOutboundMode() {
        return outboundMode;
    }

    public void setOutboundMode(String outboundMode) {
        this.outboundMode = outboundMode;
    }

    public boolean isAlsoSendText() {
        return alsoSendText;
    }

    public void setAlsoSendText(boolean alsoSendText) {
        this.alsoSendText = alsoSendText;
    }

    public String getAsrModel() {
        return asrModel;
    }

    public void setAsrModel(String asrModel) {
        this.asrModel = asrModel;
    }

    public String getTtsModel() {
        return ttsModel;
    }

    public void setTtsModel(String ttsModel) {
        this.ttsModel = ttsModel;
    }

    public String getTtsVoice() {
        return ttsVoice;
    }

    public void setTtsVoice(String ttsVoice) {
        this.ttsVoice = ttsVoice;
    }

    public String getTtsLanguageType() {
        return ttsLanguageType;
    }

    public void setTtsLanguageType(String ttsLanguageType) {
        this.ttsLanguageType = ttsLanguageType;
    }

    public String getTtsInstructions() {
        return ttsInstructions;
    }

    public void setTtsInstructions(String ttsInstructions) {
        this.ttsInstructions = ttsInstructions;
    }

    public boolean isTtsOptimizeInstructions() {
        return ttsOptimizeInstructions;
    }

    public void setTtsOptimizeInstructions(boolean ttsOptimizeInstructions) {
        this.ttsOptimizeInstructions = ttsOptimizeInstructions;
    }

    /** 有指令时解析实际模型：非 instruct 模型自动升为 instruct-flash。 */
    public String resolveTtsModel() {
        String model = ttsModel == null || ttsModel.isBlank() ? "qwen3-tts-flash" : ttsModel.trim();
        if (hasTtsInstructions() && !model.toLowerCase().contains("instruct")) {
            return "qwen3-tts-instruct-flash";
        }
        return model;
    }

    public boolean hasTtsInstructions() {
        return ttsInstructions != null && !ttsInstructions.isBlank();
    }

    public String getTtsApiBaseUrl() {
        return ttsApiBaseUrl;
    }

    public void setTtsApiBaseUrl(String ttsApiBaseUrl) {
        this.ttsApiBaseUrl = ttsApiBaseUrl;
    }

    public boolean isVoiceCloneEnabled() {
        return voiceCloneEnabled;
    }

    public void setVoiceCloneEnabled(boolean voiceCloneEnabled) {
        this.voiceCloneEnabled = voiceCloneEnabled;
    }

    public String getVoiceCloneModel() {
        return voiceCloneModel == null || voiceCloneModel.isBlank() ? "qwen3-tts-vc-2026-01-22" : voiceCloneModel;
    }

    public void setVoiceCloneModel(String voiceCloneModel) {
        this.voiceCloneModel = voiceCloneModel;
    }

    public long getVoiceClonePendingMs() {
        return voiceClonePendingMs;
    }

    public void setVoiceClonePendingMs(long voiceClonePendingMs) {
        this.voiceClonePendingMs = voiceClonePendingMs;
    }

    public int getVoiceCloneMinMs() {
        return voiceCloneMinMs;
    }

    public void setVoiceCloneMinMs(int voiceCloneMinMs) {
        this.voiceCloneMinMs = voiceCloneMinMs;
    }

    public int getEncodeType() {
        return encodeType;
    }

    public void setEncodeType(int encodeType) {
        this.encodeType = encodeType;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(long readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public boolean preferVoiceReply(boolean inboundWasVoice) {
        String mode = replyMode == null ? "mirror" : replyMode.trim().toLowerCase();
        return switch (mode) {
            case "always-voice", "always", "voice" -> true;
            case "never", "text", "always-text" -> false;
            default -> inboundWasVoice;
        };
    }

    /** 是否用 sendFile 发音频附件（默认 true）。 */
    public boolean useFileOutbound() {
        String mode = outboundMode == null ? "file" : outboundMode.trim().toLowerCase();
        return !"voice".equals(mode) && !"bubble".equals(mode) && !"sendvoice".equals(mode);
    }
}
