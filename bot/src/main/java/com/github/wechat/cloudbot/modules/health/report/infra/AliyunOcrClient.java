package com.github.wechat.cloudbot.modules.health.report.infra;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Component
public class AliyunOcrClient {

    private static final Logger log = LoggerFactory.getLogger(AliyunOcrClient.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final AliyunOcrProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public AliyunOcrClient(AliyunOcrProperties properties) {
        this(properties, HttpClient.newHttpClient());
    }

    public AliyunOcrClient(AliyunOcrProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    public String recognize(byte[] imageBytes, String imageFormat) {
        if (!properties.isConfigured()) {
            throw new OcrException("OCR_NOT_CONFIGURED", "", "Aliyun OCR not configured");
        }
        if (properties.useAccessKey()) {
            return recognizeViaAccessKey(imageBytes);
        }
        return recognizeViaAppCode(imageBytes);
    }

    // ── AppCode 方式（API市场） ──

    private String recognizeViaAppCode(byte[] imageBytes) {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        JsonObject root = new JsonObject();
        root.addProperty("img", base64Image);
        String body = root.toString();

        log.info("阿里云OCR(AppCode) endpoint={}, imageBytes={}", properties.getEndpoint(), imageBytes.length);

        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getEndpoint()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "APPCODE " + properties.getAppCode())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return sendAndParse(request);
    }

    // ── AccessKey 方式（官方API，form body） ──

    private String recognizeViaAccessKey(byte[] imageBytes) {
        // 签名参数（不含 body，原始二进制 body 不参与 POP 签名）
        Map<String, String> params = new TreeMap<>();
        params.put("AccessKeyId", properties.getAccessKeyId());
        params.put("Action", "RecognizeAdvanced");
        params.put("Format", "JSON");
        params.put("SignatureMethod", "HMAC-SHA1");
        params.put("SignatureNonce", UUID.randomUUID().toString());
        params.put("SignatureVersion", "1.0");
        params.put("Timestamp", ZonedDateTime.now(ZoneId.of("UTC"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")));
        params.put("Version", "2021-07-07");

        String signature = sign(params, properties.getAccessKeySecret());
        params.put("Signature", signature);

        StringBuilder qs = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!qs.isEmpty()) qs.append('&');
            qs.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        String url = properties.getEndpoint() + "?" + qs;

        log.info("阿里云OCR(AccessKey) action=RecognizeAdvanced, imageBytes={}", imageBytes.length);

        // 原始图片二进制作为 body（非 base64）
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(imageBytes))
                .build();
        return sendAndParse(request);
    }

    // ── 签名 ──

    private String sign(Map<String, String> params, String secret) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!canonical.isEmpty()) canonical.append('&');
            canonical.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        String stringToSign = "POST" + "&" + urlEncode("/") + "&" + urlEncode(canonical.toString());
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((secret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new OcrException("SIGN_FAILED", "", "Signature failed", e);
        }
    }

    // ── 发送 ──

    private String sendAndParse(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 400) {
                String preview = response.body();
                if (preview.length() > 500) preview = preview.substring(0, 500);
                log.error("阿里云OCR HTTP {} body={}", response.statusCode(), preview);
                throw new OcrException("HTTP_" + response.statusCode(), "",
                        "Aliyun OCR HTTP " + response.statusCode());
            }
            return parseResponse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrException("INTERRUPTED", "", "Aliyun OCR interrupted", e);
        } catch (IOException e) {
            throw new OcrException("REQUEST_FAILED", "", "Aliyun OCR request failed", e);
        }
    }

    private String parseResponse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();

        if (root.has("Code")) {
            String code = root.get("Code").getAsString();
            if (!"0".equals(code) && !"200".equals(code)) {
                String msg = root.has("Message") ? root.get("Message").getAsString() : "unknown";
                throw new OcrException(code, "", "Aliyun OCR error: " + msg);
            }
        }

        // 官方API: Data 是 JSON 字符串 {"content":"..."}
        if (root.has("Data")) {
            JsonElement dataElem = root.get("Data");
            JsonObject data;
            if (dataElem.isJsonObject()) {
                data = dataElem.getAsJsonObject();
            } else if (dataElem.isJsonPrimitive() && dataElem.getAsJsonPrimitive().isString()) {
                data = JsonParser.parseString(dataElem.getAsString()).getAsJsonObject();
            } else {
                data = new JsonObject();
            }
            if (data.has("content")) {
                String text = data.get("content").getAsString();
                log.info("阿里云OCR成功，识别 {} 字符", text.length());
                return text;
            }
        }

        // API市场: {"content":"..."}
        if (root.has("content")) {
            String text = root.get("content").getAsString();
            log.info("阿里云OCR成功，识别 {} 字符", text.length());
            return text;
        }

        throw new OcrException("EMPTY_RESULT", "", "Aliyun OCR returned no content");
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }
}
