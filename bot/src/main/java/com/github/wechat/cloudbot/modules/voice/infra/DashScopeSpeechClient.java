package com.github.wechat.cloudbot.modules.voice.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.wechat.cloudbot.modules.voice.api.SpeechGateway;
import com.github.wechat.cloudbot.platform.config.ErrorCode;
import com.github.wechat.cloudbot.platform.config.Strings;
import com.github.wechat.cloudbot.modules.vision.infra.QwenProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 千问语音：ASR（compatible-mode chat）+ TTS（原生 multimodal-generation）。
 */
@Component
public class DashScopeSpeechClient implements SpeechGateway {

    private static final Logger log = LoggerFactory.getLogger(DashScopeSpeechClient.class);

    private final RestClient qwenRestClient;
    private final RestClient voiceTtsRestClient;
    private final VoiceProperties voiceProperties;
    private final QwenProperties qwenProperties;
    private final HttpClient downloadClient;

    public DashScopeSpeechClient(
            @Qualifier("qwenRestClient") RestClient qwenRestClient,
            @Qualifier("voiceTtsRestClient") RestClient voiceTtsRestClient,
            VoiceProperties voiceProperties,
            QwenProperties qwenProperties) {
        this.qwenRestClient = qwenRestClient;
        this.voiceTtsRestClient = voiceTtsRestClient;
        this.voiceProperties = voiceProperties;
        this.qwenProperties = qwenProperties;
        this.downloadClient =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(voiceProperties.getConnectTimeoutMs()))
                        .build();
    }

    @Override
    public String transcribe(byte[] audioBytes) {
        if (!qwenProperties.hasApiKey()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY 为空，无法 ASR");
        }
        if (audioBytes == null || audioBytes.length == 0) {
            throw new IllegalArgumentException("audio bytes empty");
        }

        String dataUrl = AudioDataUrls.toDataUrl(audioBytes);
        Map<String, Object> inputAudio = new HashMap<>();
        inputAudio.put("data", dataUrl);

        Map<String, Object> audioPart = new HashMap<>();
        audioPart.put("type", "input_audio");
        audioPart.put("input_audio", inputAudio);

        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", List.of(audioPart));

        Map<String, Object> body = new HashMap<>();
        body.put("model", voiceProperties.getAsrModel());
        body.put("messages", List.of(userMsg));

        try {
            ChatCompletionResponse response =
                    qwenRestClient
                            .post()
                            .uri("/chat/completions")
                            .body(body)
                            .retrieve()
                            .body(ChatCompletionResponse.class);
            String text = extractContent(response);
            if (text == null || text.isBlank()) {
                throw new IllegalStateException("ASR returned empty text");
            }
            return text.trim();
        } catch (RestClientResponseException e) {
            log.error("{}ASR HTTP {} : {}",
                    ErrorCode.VOICE_API_ERROR.prefix(),
                    e.getStatusCode().value(),
                   Strings.truncate(e.getResponseBodyAsString(), 400));
            throw new IllegalStateException("ASR API error: " + e.getStatusCode().value(), e);
        }
    }

    /**
     * @param instructionsOverride 非空时覆盖默认语气指令
     * @param voiceOverride 非空时覆盖默认系统音色（如 Cherry / Ethan）
     */
    @Override
    public SynthesizedSpeech synthesize(
            String text, String instructionsOverride, String voiceOverride) {
        String instructions = resolveInstructions(instructionsOverride);
        String voice =
                voiceOverride == null || voiceOverride.isBlank()
                        ? voiceProperties.getTtsVoice()
                        : voiceOverride.trim();
        return synthesizeInternal(text, voice, resolveModel(instructions), instructions);
    }

    /** 使用声音复刻得到的自定义 voice（VC 模型，不带 instructions）。 */
    @Override
    public SynthesizedSpeech synthesizeWithCustomVoice(
            String text, String voiceId, String targetModel) {
        if (voiceId == null || voiceId.isBlank()) {
            throw new IllegalArgumentException("custom voiceId empty");
        }
        String model =
                targetModel == null || targetModel.isBlank()
                        ? voiceProperties.getVoiceCloneModel()
                        : targetModel.trim();
        return synthesizeInternal(text, voiceId.trim(), model, null);
    }

    private SynthesizedSpeech synthesizeInternal(
            String text, String voice, String model, String instructions) {
        if (!qwenProperties.hasApiKey()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY 为空，无法 TTS");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("tts text empty");
        }

        String clipped = text.length() <= 500 ? text : text.substring(0, 500) + "……";

        Map<String, Object> input = new HashMap<>();
        input.put("text", clipped);
        input.put("voice", voice);
        input.put("language_type", voiceProperties.getTtsLanguageType());
        if (instructions != null && !instructions.isBlank()) {
            input.put("instructions", instructions.trim());
            input.put("optimize_instructions", voiceProperties.isTtsOptimizeInstructions());
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", input);

        try {
            JsonNode root =
                    voiceTtsRestClient
                            .post()
                            .uri("/services/aigc/multimodal-generation/generation")
                            .body(body)
                            .retrieve()
                            .body(JsonNode.class);

            if (root == null) {
                throw new IllegalStateException("TTS empty response");
            }
            JsonNode audio = root.path("output").path("audio");
            String url = audio.path("url").asText(null);
            String b64 = audio.path("data").asText(null);

            byte[] bytes;
            if (url != null && !url.isBlank()) {
                bytes = downloadBytes(url);
            } else if (b64 != null && !b64.isBlank()) {
                bytes = Base64.getDecoder().decode(b64);
            } else {
                throw new IllegalStateException(
                        "TTS missing audio url/data: " + Strings.truncate(root.toString(), 300));
            }

            log.info(
                    "TTS 音频已获取：model={}, voice={}, instruct={}, bytes={}, url={}, magic={}",
                    model,
                    voice,
                    instructions != null && !instructions.isBlank(),
                    bytes.length,
                    url == null ? "(b64)" : Strings.truncate(url, 80),
                    magicHex(bytes));

            int playMs = estimatePlayMs(bytes, clipped.length());
            return new SynthesizedSpeech(bytes, playMs, voiceProperties.getSampleRate());
        } catch (RestClientResponseException e) {
            log.error("{}TTS HTTP {} : {}",
                    ErrorCode.VOICE_API_ERROR.prefix(),
                    e.getStatusCode().value(),
                   Strings.truncate(e.getResponseBodyAsString(), 400));
            throw new IllegalStateException("TTS API error: " + e.getStatusCode().value(), e);
        }
    }

    private String resolveInstructions(String override) {
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return voiceProperties.hasTtsInstructions()
                ? voiceProperties.getTtsInstructions().trim()
                : null;
    }

    private String resolveModel(String instructions) {
        String model = voiceProperties.resolveTtsModel();
        if (instructions != null
                && !instructions.isBlank()
                && !model.toLowerCase().contains("instruct")) {
            return "qwen3-tts-instruct-flash";
        }
        return model;
    }

    private byte[] downloadBytes(String url) {
        try {
            @SuppressWarnings("null")
            java.net.URI speechUri = URI.create(url);
            HttpRequest request =
                    HttpRequest.newBuilder(speechUri)
                            .timeout(Duration.ofMillis(voiceProperties.getReadTimeoutMs()))
                            .GET()
                            .build();
            HttpResponse<byte[]> response =
                    downloadClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("download TTS audio HTTP " + response.statusCode());
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                throw new IllegalStateException("download TTS audio empty");
            }
            return body;
        } catch (Exception e) {
            throw new IllegalStateException("download TTS audio failed: " + e.getMessage(), e);
        }
    }

    private static int estimatePlayMs(byte[] audio, int textLen) {
        Integer fromWav = WavDurationMs.tryParse(audio);
        if (fromWav != null && fromWav > 0 && fromWav <= 60_000) {
            return fromWav;
        }
        // 中文约 4 字/秒，夹在 1s~60s
        int byText = Math.max(1000, (int) (textLen / 4.0 * 1000));
        return Math.min(byText, 60_000);
    }

    private static String extractContent(ChatCompletionResponse response) {
        if (response == null || response.choices == null || response.choices.isEmpty()) {
            return null;
        }
        Choice first = response.choices.get(0);
        if (first == null || first.message == null) {
            return null;
        }
        Object content = first.message.content;
        if (content == null) {
            return null;
        }
        if (content instanceof String s) {
            return s;
        }
        return content.toString();
    }


    private static String magicHex(byte[] data) {
        int n = Math.min(12, data.length);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChatCompletionResponse {
        public List<Choice> choices;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Choice {
        public ApiMessage message;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ApiMessage {
        public String role;
        public Object content;
    }

    /** 正确按 chunk 解析 WAV 时长，避免 fmt 扩展头导致 byteRate 读错。 */
    static final class WavDurationMs {
        private WavDurationMs() {}

        static Integer tryParse(byte[] wav) {
            if (wav == null || wav.length < 44) {
                return null;
            }
            if (wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F') {
                return null;
            }
            try {
                int byteRate = 0;
                int offset = 12;
                while (offset + 8 <= wav.length) {
                    String id =
                            new String(
                                    wav, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
                    int size =
                            (wav[offset + 4] & 0xFF)
                                    | ((wav[offset + 5] & 0xFF) << 8)
                                    | ((wav[offset + 6] & 0xFF) << 16)
                                    | ((wav[offset + 7] & 0xFF) << 24);
                    int dataStart = offset + 8;
                    if (size < 0 || dataStart > wav.length) {
                        break;
                    }
                    if ("fmt ".equals(id) && size >= 16 && dataStart + 12 <= wav.length) {
                        byteRate =
                                (wav[dataStart + 8] & 0xFF)
                                        | ((wav[dataStart + 9] & 0xFF) << 8)
                                        | ((wav[dataStart + 10] & 0xFF) << 16)
                                        | ((wav[dataStart + 11] & 0xFF) << 24);
                    } else if ("data".equals(id) && byteRate > 0) {
                        long ms = size * 1000L / byteRate;
                        if (ms <= 0 || ms > 60_000) {
                            return null;
                        }
                        return (int) ms;
                    }
                    offset = dataStart + Math.max(0, size) + (size & 1);
                }
            } catch (Exception ignored) {
                return null;
            }
            return null;
        }
    }
}
