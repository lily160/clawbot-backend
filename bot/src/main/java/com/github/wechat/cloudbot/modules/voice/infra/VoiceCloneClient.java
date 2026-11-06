package com.github.wechat.cloudbot.modules.voice.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.wechat.cloudbot.platform.config.ErrorCode;
import com.github.wechat.cloudbot.platform.config.Strings;
import com.github.wechat.cloudbot.modules.vision.infra.QwenProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * 阿里云声音复刻（Voice Cloning）enrollment：上传样本 → 返回自定义 voice ID。
 */
@Component
public class VoiceCloneClient {

    private static final Logger log = LoggerFactory.getLogger(VoiceCloneClient.class);

    private final RestClient voiceTtsRestClient;
    private final VoiceProperties voiceProperties;
    private final QwenProperties qwenProperties;

    public VoiceCloneClient(
            @Qualifier("voiceTtsRestClient") RestClient voiceTtsRestClient,
            VoiceProperties voiceProperties,
            QwenProperties qwenProperties) {
        this.voiceTtsRestClient = voiceTtsRestClient;
        this.voiceProperties = voiceProperties;
        this.qwenProperties = qwenProperties;
    }

    /**
     * @param wavBytes  WAV 字节（推荐 10～20 秒清晰人声）
     * @param preferredName 偏好名（平台会再加后缀）
     * @return 平台返回的 voice ID，后续合成时传入
     */
    public String enroll(byte[] wavBytes, String preferredName) {
        if (!qwenProperties.hasApiKey()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY 为空，无法复刻音色");
        }
        if (wavBytes == null || wavBytes.length == 0) {
            throw new IllegalArgumentException("enrollment audio empty");
        }

        String name =
                preferredName == null || preferredName.isBlank()
                        ? "wxvoice"
                        : preferredName.replaceAll("[^a-zA-Z0-9_\\-]", "").toLowerCase();
        if (name.isBlank()) {
            name = "wxvoice";
        }
        // 平台限制：字母/数字/下划线，最长 16
        if (name.length() > 16) {
            name = name.substring(0, 16);
        }

        String targetModel = voiceProperties.getVoiceCloneModel();

        String mime = detectAudioMime(wavBytes);
        String dataUri =
                "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(wavBytes);

        Map<String, Object> audio = new HashMap<>();
        audio.put("data", dataUri);

        Map<String, Object> input = new HashMap<>();
        input.put("action", "create");
        input.put("target_model", targetModel);
        input.put("preferred_name", name);
        input.put("audio", audio);

        Map<String, Object> body = new HashMap<>();
        body.put("model", "qwen-voice-enrollment");
        body.put("input", input);

        try {
            JsonNode root =
                    voiceTtsRestClient
                            .post()
                            .uri("/services/audio/tts/customization")
                            .body(body)
                            .retrieve()
                            .body(JsonNode.class);
            if (root == null) {
                throw new IllegalStateException("enrollment empty response");
            }
            String voiceId = root.path("output").path("voice").asText(null);
            if (voiceId == null || voiceId.isBlank()) {
                voiceId = root.path("output").path("voice_id").asText(null);
            }
            if (voiceId == null || voiceId.isBlank()) {
                throw new IllegalStateException(
                        "enrollment missing voice id: " + Strings.truncate(root.toString(), 400));
            }
            log.info(
                    "声音复刻成功 voiceId={}, targetModel={}, bytes={}",
                    voiceId,
                    targetModel,
                    wavBytes.length);
            return voiceId.trim();
        } catch (RestClientResponseException e) {
            log.error("{}声音复刻 HTTP {} : {}",
                    ErrorCode.VOICE_CLONE_ERROR.prefix(),
                    e.getStatusCode().value(),
                   Strings.truncate(e.getResponseBodyAsString(), 500));
            throw new IllegalStateException(
                    "声音复刻失败 HTTP " + e.getStatusCode().value() + ": "
                            + Strings.truncate(e.getResponseBodyAsString(), 200),
                    e);
        } catch (Exception e) {
            throw new IllegalStateException("声音复刻失败: " + e.getMessage(), e);
        }
    }

    private static String detectAudioMime(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return "audio/wav";
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return "audio/wav";
        }
        if (bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return "audio/mpeg";
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0) {
            return "audio/mpeg";
        }
        // ftyp....M4A / MP4
        if (bytes.length >= 8 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
            return "audio/mp4";
        }
        return "audio/wav";
    }

}
