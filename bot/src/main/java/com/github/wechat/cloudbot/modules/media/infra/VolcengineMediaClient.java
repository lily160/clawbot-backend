package com.github.wechat.cloudbot.modules.media.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.media.api.MediaGateway;
import com.github.wechat.cloudbot.platform.config.ErrorCode;
import com.github.wechat.cloudbot.platform.config.Strings;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 火山方舟：文生图（同步）+ 文生视频（异步任务轮询）。
 */
@Component
public class VolcengineMediaClient implements MediaGateway {

    private static final Logger log = LoggerFactory.getLogger(VolcengineMediaClient.class);

    private final RestClient restClient;
    private final VolcengineProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient downloadClient;

    public VolcengineMediaClient(
            @Qualifier("volcengineRestClient") RestClient restClient,
            VolcengineProperties properties,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.downloadClient =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(30))
                        .build();
    }

    @Override
    public boolean canGenerateImage() {
        return properties.canGenerateImage();
    }

    @Override
    public boolean canGenerateVideo() {
        return properties.canGenerateVideo();
    }

    /** 文生图，返回图片字节。 */
    @Override
    public byte[] generateImage(String prompt) {
        if (!properties.canGenerateImage()) {
            throw new IllegalStateException("Volcengine image generation is not configured");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", properties.getImageModel());
        body.put("prompt", prompt);
        body.put("size", properties.getImageSize());
        body.put("response_format", "b64_json");
        body.put("watermark", properties.isImageWatermark());

        try {
            String raw =
                    restClient
                            .post()
                            .uri("/images/generations")
                            .body(body)
                            .retrieve()
                            .body(String.class);

            JsonNode root = objectMapper.readTree(raw);
            JsonNode data0 = root.path("data").path(0);
            if (data0.hasNonNull("b64_json")) {
                return Base64.getDecoder().decode(data0.get("b64_json").asText());
            }
            if (data0.hasNonNull("url")) {
                return downloadBytes(data0.get("url").asText());
            }
            throw new IllegalStateException("image response missing b64_json/url");
        } catch (RestClientResponseException e) {
            log.error("{}Volcengine image HTTP {}: {}",
                    ErrorCode.MEDIA_API_ERROR.prefix(),
                    e.getStatusCode().value(),
                   Strings.truncate(e.getResponseBodyAsString(), 400));
            throw new IllegalStateException("Volcengine image API error: " + e.getStatusCode().value(), e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Volcengine image failed: " + e.getMessage(), e);
        }
    }

    /**
     * 文生视频：创建任务并轮询，返回 MP4 字节。
     *
     * @return videoBytes + 估算时长毫秒
     */
    @Override
    public GeneratedVideo generateVideo(String prompt) {
        if (!properties.canGenerateVideo()) {
            throw new IllegalStateException("Volcengine video generation is not configured");
        }

        String taskId = createVideoTask(prompt);
        log.info("Volcengine video task created id={}", taskId);

        long deadline = System.currentTimeMillis() + properties.getVideoPollTimeoutMs();
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(properties.getVideoPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("video poll interrupted", e);
            }

            VideoTaskStatus status = queryVideoTask(taskId);
            log.info("Volcengine video task {} status={}", taskId, status.status());
            if ("succeeded".equalsIgnoreCase(status.status())) {
                if (status.videoUrl() == null || status.videoUrl().isBlank()) {
                    throw new IllegalStateException("video succeeded but video_url empty");
                }
                byte[] bytes = downloadBytes(status.videoUrl());
                int durationMs = properties.getVideoDurationSeconds() * 1000;
                return new GeneratedVideo(bytes, durationMs);
            }
            if ("failed".equalsIgnoreCase(status.status())
                    || "cancelled".equalsIgnoreCase(status.status())) {
                throw new IllegalStateException(
                        "video task " + status.status() + ": " + status.error());
            }
        }
        throw new IllegalStateException("video task timeout: " + taskId);
    }

    private String createVideoTask(String prompt) {
        Map<String, Object> textPart = new HashMap<>();
        textPart.put("type", "text");
        textPart.put(
                "text",
                prompt
                        + " --duration "
                        + properties.getVideoDurationSeconds()
                        + " --ratio "
                        + properties.getVideoRatio()
                        + " --watermark false");

        List<Map<String, Object>> content = new ArrayList<>();
        content.add(textPart);

        Map<String, Object> body = new HashMap<>();
        body.put("model", properties.getVideoModel());
        body.put("content", content);

        try {
            String raw =
                    restClient
                            .post()
                            .uri("/contents/generations/tasks")
                            .body(body)
                            .retrieve()
                            .body(String.class);
            JsonNode root = objectMapper.readTree(raw);
            String id = root.path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw new IllegalStateException("create video task missing id: " + Strings.truncate(raw, 200));
            }
            return id;
        } catch (RestClientResponseException e) {
            log.error("{}Volcengine create video HTTP {}: {}",
                    ErrorCode.MEDIA_API_ERROR.prefix(),
                    e.getStatusCode().value(),
                   Strings.truncate(e.getResponseBodyAsString(), 400));
            throw new IllegalStateException(
                    "Volcengine create video error: " + e.getStatusCode().value(), e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("create video task failed: " + e.getMessage(), e);
        }
    }

    private VideoTaskStatus queryVideoTask(String taskId) {
        try {
            String raw =
                    restClient
                            .get()
                            .uri("/contents/generations/tasks/{id}", taskId)
                            .retrieve()
                            .body(String.class);
            JsonNode root = objectMapper.readTree(raw);
            String status = root.path("status").asText("unknown");
            String videoUrl = null;
            JsonNode content = root.path("content");
            if (content.isObject() && content.has("video_url")) {
                videoUrl = content.get("video_url").asText(null);
            }
            String error = root.path("error").path("message").asText(null);
            if (error == null || error.isBlank()) {
                error = root.path("message").asText("");
            }
            return new VideoTaskStatus(status, videoUrl, error);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(
                    "query video task HTTP " + e.getStatusCode().value(), e);
        } catch (Exception e) {
            throw new IllegalStateException("query video task failed: " + e.getMessage(), e);
        }
    }

    /** 媒体文件下载大小上限（100MB），防止超大文件整包进堆导致 OOM。 */
    private static final int MAX_DOWNLOAD_BYTES = 100 * 1024 * 1024;

    private byte[] downloadBytes(String url) {
        // 预签名 TOS 链接必须原样下载，且绝不能带 ARK Bearer；否则会出现 AuthorizationHeaderMalformed
        log.info("下载媒体文件（JDK HttpClient，无 Authorization）host={}", extractHost(url));
        try {
            @SuppressWarnings("null")
            java.net.URI dlUri = URI.create(url);
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(dlUri)
                            .GET()
                            .timeout(Duration.ofMinutes(5))
                            .header("User-Agent", "wechat-cloudbot/echo-bot")
                            .build();
            // 流式下载并限制大小，避免超大媒体整包进堆导致 OOM
            HttpResponse<java.io.InputStream> response =
                    downloadClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                try (java.io.InputStream is = response.body()) {
                    byte[] err = is == null ? new byte[0] : is.readNBytes(300);
                    throw new IllegalStateException("download media HTTP " + code + ": "
                            + new String(err, java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            try (java.io.InputStream is = response.body()) {
                byte[] body = is == null ? new byte[0] : is.readNBytes(MAX_DOWNLOAD_BYTES + 1);
                if (body.length > MAX_DOWNLOAD_BYTES) {
                    throw new IllegalStateException(
                            "媒体文件超过下载上限 " + (MAX_DOWNLOAD_BYTES / 1024 / 1024) + "MB");
                }
                if (body.length == 0) {
                    throw new IllegalStateException("download media empty body");
                }
                log.info("媒体下载成功 bytes={}", body.length);
                return body;
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("download media failed: " + e.getMessage(), e);
        }
    }

    private static String extractHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "(unknown)";
        }
    }


    private record VideoTaskStatus(String status, String videoUrl, String error) {}
}
