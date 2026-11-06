package com.github.wechat.cloudbot.modules.voice.api;

/**
 * 语音 ASR / TTS 网关。
 */
public interface SpeechGateway {

    String transcribe(byte[] audioBytes);

    /**
     * @param instructionsOverride 非空时覆盖默认语气指令
     * @param voiceOverride 非空时覆盖默认系统音色（如 Cherry / Ethan）
     */
    SynthesizedSpeech synthesize(String text, String instructionsOverride, String voiceOverride);

    /**
     * @param voiceId 自定义音色 ID
     * @param targetModel VC 模型名，可空
     */
    SynthesizedSpeech synthesizeWithCustomVoice(String text, String voiceId, String targetModel);

    record SynthesizedSpeech(byte[] bytes, int playLengthMs, int sampleRate) {}
}
