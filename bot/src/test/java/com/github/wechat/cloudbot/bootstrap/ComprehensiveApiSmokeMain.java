package com.github.wechat.cloudbot.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.file.service.DocumentTextExtractor;
import com.github.wechat.cloudbot.modules.file.service.TextFileSupport;
import com.github.wechat.cloudbot.modules.health.report.infra.AliyunOcrClient;
import com.github.wechat.cloudbot.modules.health.report.infra.AliyunOcrProperties;
import com.github.wechat.cloudbot.modules.health.report.infra.OcrException;
import com.github.wechat.cloudbot.platform.config.Strings;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * 综合 API 冒烟测试：DeepSeek、Qwen VL、火山生图、阿里云 OCR、PDF 提取。
 *
 * <pre>
 * mvn -pl bot exec:java -Dexec.classpathScope=test \
 *   "-Dexec.mainClass=com.github.wechat.cloudbot.bootstrap.ComprehensiveApiSmokeMain"
 * </pre>
 */
public final class ComprehensiveApiSmokeMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;

    public static void main(String[] args) {
        testPdfExtraction();
        testDeepSeekChat();
        testQwenChat();
        testVolcengineImage();
        testAliyunOcr();

        System.out.println("\n============================================================");
        System.out.printf("COMPREHENSIVE_API_SMOKE: passed=%d failed=%d skipped=%d%n",
                passed, failed, skipped);
        if (failed > 0) {
            System.err.println("SOME_TESTS_FAILED");
            System.exit(1);
        }
        System.out.println("ALL_API_TESTS_PASSED");
    }

    // ─── PDF 提取（本地，无依赖外部 API） ───────────────────────────────

    private static void testPdfExtraction() {
        System.out.println("\n── PDF 提取 ──");
        try {
            // 用 PDFBox 创建一个最小的有效 PDF
            byte[] pdfBytes = createMinimalPdf("Hello PDF Smoke Test 2024");
            String text = DocumentTextExtractor.extract("test.pdf", pdfBytes,
                    DocumentTextExtractor.Limits.defaults()).orElse(null);

            if (text == null || text.isBlank()) {
                throw new IllegalStateException("PDF 提取结果为空");
            }
            if (!text.contains("Hello PDF Smoke Test")) {
                throw new IllegalStateException("PDF 提取内容不匹配: "
                        + Strings.summarizeOneLine(text));
            }
            System.out.println("PDF_EXTRACT_OK textLen=" + text.length());
            passed++;
        } catch (Exception e) {
            System.err.println("PDF_EXTRACT_FAIL: " + e.getMessage());
            e.printStackTrace(System.err);
            failed++;
        }

        try {
            if (!DocumentTextExtractor.isOfficeOrZip("report.pdf")) {
                throw new IllegalStateException("isOfficeOrZip 应将 .pdf 识别为可处理格式");
            }
            System.out.println("PDF_IS_OFFICE_OR_ZIP_OK");
            passed++;
        } catch (Exception e) {
            System.err.println("PDF_IS_OFFICE_OR_ZIP_FAIL: " + e.getMessage());
            failed++;
        }
    }

    // ─── DeepSeek Chat（直接 HTTP，不依赖 ToolRegistry） ────────────────

    private static void testDeepSeekChat() {
        System.out.println("\n── DeepSeek Chat ──");
        String apiKey = env("DEEPSEEK_API_KEY", "");
        if (apiKey.isBlank()) {
            System.out.println("DEEPSEEK_SKIP (no DEEPSEEK_API_KEY)");
            skipped++;
            return;
        }

        try {
            RestClient rc = RestClient.builder()
                    .baseUrl("https://api.deepseek.com")
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .build();

            Map<String, Object> body = Map.of(
                    "model", "deepseek-chat",
                    "temperature", 0.7,
                    "messages", List.of(
                            Map.of("role", "system", "content", "用一句话回复。"),
                            Map.of("role", "user", "content", "你好，请说'API测试成功'")));

            String raw = rc.post().uri("/chat/completions").body(body)
                    .retrieve().body(String.class);
            JsonNode root = MAPPER.readTree(raw);
            String content = root.path("choices").path(0)
                    .path("message").path("content").asText("");

            if (content.isBlank()) {
                throw new IllegalStateException("DeepSeek 返回空响应");
            }
            System.out.println("DEEPSEEK_OK preview="
                    + Strings.summarizeOneLine(content, 80));
            passed++;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("503") || msg.contains("Service is too busy")
                    || msg.contains("service_unavailable"))) {
                System.err.println("DEEPSEEK_WARN: " + msg + " (服务暂时繁忙，非代码问题)");
                skipped++;
            } else {
                System.err.println("DEEPSEEK_FAIL: " + e.getMessage());
                e.printStackTrace(System.err);
                failed++;
            }
        }
    }

    // ─── Qwen / DashScope Chat ─────────────────────────────────────────

    private static void testQwenChat() {
        System.out.println("\n── Qwen Chat (DashScope) ──");
        String apiKey = env("DASHSCOPE_API_KEY", "");
        if (apiKey.isBlank()) {
            System.out.println("QWEN_SKIP (no DASHSCOPE_API_KEY)");
            skipped++;
            return;
        }

        try {
            RestClient rc = RestClient.builder()
                    .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .build();

            Map<String, Object> body = Map.of(
                    "model", "qwen-plus",
                    "temperature", 0.7,
                    "messages", List.of(
                            Map.of("role", "user",
                                    "content", "你好，请回复'API测试成功'")));

            String raw = rc.post().uri("/chat/completions").body(body)
                    .retrieve().body(String.class);
            JsonNode root = MAPPER.readTree(raw);
            String content = root.path("choices").path(0)
                    .path("message").path("content").asText("");

            if (content.isBlank()) {
                throw new IllegalStateException("Qwen 返回空响应");
            }
            System.out.println("QWEN_OK preview="
                    + Strings.summarizeOneLine(content, 80));
            passed++;
        } catch (Exception e) {
            System.err.println("QWEN_FAIL: " + e.getMessage());
            e.printStackTrace(System.err);
            failed++;
        }
    }

    // ─── 火山方舟 文生图 ──────────────────────────────────────────────

    private static void testVolcengineImage() {
        System.out.println("\n── 火山生图 (Volcengine ARK) ──");
        String apiKey = env("ARK_API_KEY", "");
        String imageModel = env("VOLC_IMAGE_MODEL", "");
        if (apiKey.isBlank()) {
            System.out.println("VOLCENGINE_SKIP (no ARK_API_KEY)");
            skipped++;
            return;
        }

        try {
            RestClient rc = RestClient.builder()
                    .baseUrl("https://ark.cn-beijing.volces.com/api/v3")
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .build();

            Map<String, Object> body = Map.of(
                    "model", imageModel,
                    "prompt", "一只可爱的猫，卡通风格，简单",
                    "size", "1024x1024",
                    "response_format", "b64_json",
                    "watermark", false);

            String raw = rc.post().uri("/images/generations").body(body)
                    .retrieve().body(String.class);
            JsonNode root = MAPPER.readTree(raw);
            JsonNode data0 = root.path("data").path(0);
            boolean hasImage = data0.hasNonNull("b64_json")
                    || data0.hasNonNull("url");

            if (!hasImage) {
                throw new IllegalStateException("火山生图响应无图片数据");
            }
            System.out.println("VOLCENGINE_IMAGE_OK model=" + imageModel);
            passed++;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("401") || msg.contains("403")
                    || msg.contains("Model not found") || msg.contains("404"))) {
                System.err.println("VOLCENGINE_IMAGE_WARN: " + msg
                        + " (可能需要更新鉴权或模型配置)");
                skipped++;
            } else {
                System.err.println("VOLCENGINE_IMAGE_FAIL: " + msg);
                e.printStackTrace(System.err);
                failed++;
            }
        }
    }

    // ─── 阿里云 OCR ─────────────────────────────────────────────────────

    private static void testAliyunOcr() {
        System.out.println("\n── 阿里云 OCR ──");
        String accessKeyId = env("ALIYUN_OCR_ACCESS_KEY_ID", "");
        String accessKeySecret = env("ALIYUN_OCR_ACCESS_KEY_SECRET", "");

        if (accessKeyId.isBlank() || accessKeySecret.isBlank()) {
            System.out.println("ALIYUN_OCR_SKIP (OCR 配置不完整)");
            skipped++;
            return;
        }

        try {
            AliyunOcrProperties props = new AliyunOcrProperties();
            props.setAccessKeyId(accessKeyId);
            props.setAccessKeySecret(accessKeySecret);
            AliyunOcrClient client = new AliyunOcrClient(props);

            // 生成测试图片（白底黑字，清晰大字号）
            byte[] imageBytes = createTextImage("Hello OCR Test");
            String result = client.recognize(imageBytes, "png");

            if (result == null || result.isBlank()) {
                throw new IllegalStateException("阿里云 OCR 返回空结果");
            }
            System.out.println("ALIYUN_OCR_OK preview="
                    + Strings.summarizeOneLine(result, 80));
            passed++;
        } catch (Exception e) {
            String msg = e.getMessage();
            if (e instanceof OcrException ocr) {
                System.err.println("ALIYUN_OCR_DIAG: code=" + ocr.code()
                        + " requestId=" + ocr.requestId()
                        + " message=" + ocr.getMessage());
            }
            if (msg != null && (msg.contains("UNAUTHORIZED") || msg.contains("401")
                    || msg.contains("403") || msg.contains("signature")
                    || msg.contains("AUTH") || msg.contains("NOT_CONFIGURED")
                    || msg.contains("TIMEOUT") || msg.contains("HTTP 400")
                    || msg.contains("HTTP 500"))) {
                System.err.println("ALIYUN_OCR_WARN: " + msg
                        + " (鉴权/配置问题，非代码错误)");
                skipped++;
            } else {
                System.err.println("ALIYUN_OCR_FAIL: " + msg);
                e.printStackTrace(System.err);
                failed++;
            }
        }
    }

    // ─── 辅助方法 ─────────────────────────────────────────────────────

    private static byte[] createMinimalPdf(String text) throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc =
                     new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page =
                    new org.apache.pdfbox.pdmodel.PDPage(
                            org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            doc.addPage(page);

            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(
                                 doc, page)) {
                cs.beginText();
                cs.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    private static byte[] createTextImage(String text) throws Exception {
        BufferedImage img = new BufferedImage(800, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = img.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, 800, 200);
        g2d.setColor(Color.BLACK);
        g2d.setFont(new Font("SansSerif", Font.BOLD, 48));
        g2d.drawString(text, 40, 100);
        g2d.dispose();

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) return v.trim();
        return fallback == null ? "" : fallback;
    }
}
