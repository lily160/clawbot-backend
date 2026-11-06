package com.github.wechat.cloudbot.modules.health.report.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseRequest;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus;
import com.github.wechat.cloudbot.modules.health.report.service.HealthReportParseService;
import com.github.wechat.cloudbot.modules.health.report.service.HealthReportRepository;
import com.github.wechat.cloudbot.modules.health.report.service.ReportSafetyFormatter;
import com.github.wechat.cloudbot.modules.health.report.service.ReportTrendAnalyzer;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthReportParseToolTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final ReportTrendAnalyzer trendAnalyzer = new ReportTrendAnalyzer();
    private static final HealthReportRepository repository = new HealthReportRepository(new NoopDataSource());

    @Test
    void exposesTheExpectedFunctionCallingContract() {
        HealthReportParseTool tool = new HealthReportParseTool(
                new FakeService(), new FakeFormatter(), repository, trendAnalyzer, objectMapper);

        assertEquals("health_report_parse", tool.definition().name());
        assertTrue(tool.definition().parametersJsonSchema().contains("filePath"));
        assertTrue(tool.definition().parametersJsonSchema().contains("originalFilename"));
    }

    @Test
    void returnsStructuredPayloadThatCanBeSentBackToTheModel() throws Exception {
        Path pdf = Files.createTempFile("health-report-", ".pdf");
        Files.writeString(pdf, "%PDF-1.4\n%fake");
        HealthReportParseResult result = new HealthReportParseResult(
                "体检报告",
                "demo-report.pdf",
                2,
                List.of(new HealthIndicator(
                        "空腹血糖",
                        "7.2",
                        "mmol/L",
                        "3.9-6.1",
                        IndicatorStatus.HIGH,
                        2,
                        "空腹血糖 7.2 mmol/L 3.9-6.1",
                        0.88)),
                List.of(new HealthIndicator(
                        "空腹血糖",
                        "7.2",
                        "mmol/L",
                        "3.9-6.1",
                        IndicatorStatus.HIGH,
                        2,
                        "空腹血糖 7.2 mmol/L 3.9-6.1",
                        0.88)),
                "免责声明",
                "请结合原始报告并咨询专业医生复核");

        AtomicReference<HealthReportParseRequest> received = new AtomicReference<>();
        HealthReportParseService service = new FakeService() {
            @Override
            public HealthReportParseResult parse(HealthReportParseRequest request) {
                received.set(request);
                return result;
            }
        };
        HealthReportParseTool tool =
                new HealthReportParseTool(service, new FakeFormatter(), repository, trendAnalyzer, objectMapper);

        // 不带 userId 的 ToolCall（基础模式）
        ToolResult toolResult = tool.execute(new ToolCall("1", "health_report_parse",
                "{\"filePath\":\"" + escape(pdf.toString()) + "\",\"originalFilename\":\"demo-report.pdf\"}"));

        assertTrue(toolResult.success());
        JsonNode payload = objectMapper.readTree(toolResult.content());
        assertEquals("【体检报告解析】OK", payload.get("message").asText());
        assertEquals("demo-report.pdf", payload.get("result").get("sourceFilename").asText());
        assertEquals(2, payload.get("result").get("pageCount").asInt());
        assertEquals("demo-report.pdf", received.get().originalFilename());
        assertEquals(pdf.toAbsolutePath().normalize(), received.get().pdfPath().toAbsolutePath().normalize());
        assertTrue(payload.toString().contains("空腹血糖"));
        assertFalse(payload.toString().contains("API_KEY"));
    }

    @Test
    void enhancedModeIncludesTrendsWhenUserIdAvailable() throws Exception {
        Path pdf = Files.createTempFile("health-report-", ".pdf");
        Files.writeString(pdf, "%PDF-1.4\n%fake\n血红蛋白 138 g/L 120-160\n空腹血糖 7.2 mmol/L 3.9-6.1");

        HealthReportParseTool tool =
                new HealthReportParseTool(new FakeService(), new FakeFormatter(), repository, trendAnalyzer, objectMapper);

        // 带 userId 的 ToolCall（增强模式）
        ToolResult toolResult = tool.execute(new ToolCall("1", "health_report_parse",
                "{\"filePath\":\"" + escape(pdf.toString()) + "\",\"originalFilename\":\"demo-report.pdf\"}",
                "test-user-001"));

        assertTrue(toolResult.success());
        JsonNode payload = objectMapper.readTree(toolResult.content());
        // 增强模式应包含 hasHistory 字段
        assertTrue(payload.has("hasHistory"));
        // 基础字段仍在
        assertEquals("【体检报告解析】OK", payload.get("message").asText());
    }

    @Test
    void rejectsMissingFileAndNonPdfInput() throws Exception {
        HealthReportParseTool tool = new HealthReportParseTool(
                new FakeService(), new FakeFormatter(), repository, trendAnalyzer, objectMapper);

        // 临时目录内不存在的路径 → 报「文件不存在」
        Path missingInTmp = Files.createTempFile("health-report-", ".pdf");
        Files.deleteIfExists(missingInTmp);
        ToolResult missing = tool.execute(new ToolCall("1", "health_report_parse",
                "{\"filePath\":\"" + escape(missingInTmp.toString()) + "\"}"));
        assertFalse(missing.success());
        assertTrue(missing.content().contains("不存在"));

        // 临时目录之外的路径（如系统目录/桌面）→ 路径穿越防护直接拒绝
        ToolResult traversal = tool.execute(new ToolCall("1a", "health_report_parse",
                "{\"filePath\":\"C:/Windows/System32/report.pdf\"}"));
        assertFalse(traversal.success());
        assertTrue(traversal.content().contains("临时目录"), "应提示只允许解析临时目录文件");

        Path txt = Files.createTempFile("health-report-", ".txt");
        Files.writeString(txt, "not a pdf", StandardCharsets.UTF_8);
        ToolResult notPdf = tool.execute(new ToolCall("2", "health_report_parse",
                "{\"filePath\":\"" + escape(txt.toString()) + "\"}"));
        assertFalse(notPdf.success());
        assertTrue(notPdf.content().contains("PDF"));
    }

    @Test
    void richFormatMessageContainsHumanFriendlySections() throws Exception {
        Path pdf = Files.createTempFile("health-report-", ".pdf");
        Files.writeString(pdf, "%PDF-1.4\n%fake");

        // 使用真实 formatter 输出人性化文本
        ReportSafetyFormatter realFormatter = new ReportSafetyFormatter();
        HealthReportParseTool tool =
                new HealthReportParseTool(new FakeService(), realFormatter, repository, trendAnalyzer, objectMapper);

        ToolResult toolResult = tool.execute(new ToolCall("1", "health_report_parse",
                "{\"filePath\":\"" + escape(pdf.toString()) + "\",\"originalFilename\":\"demo-report.pdf\"}"));

        assertTrue(toolResult.success());
        JsonNode payload = objectMapper.readTree(toolResult.content());
        String message = payload.get("message").asText();
        // 人性化文本应包含关键板块
        assertTrue(message.contains("报告解析完成"), "Should have greeting");
        assertTrue(message.contains("报告概览"), "Should have overview");
        assertTrue(message.contains("生活"), "Should have lifestyle tips");
        assertTrue(message.contains("您可以"), "Should have next steps guide");
    }

    private static String escape(String path) {
        return path.replace("\\", "\\\\");
    }

    private static class FakeService extends HealthReportParseService {
        protected FakeService() {
            super();
        }

        @Override
        public HealthReportParseResult parse(HealthReportParseRequest request) {
            return new HealthReportParseResult(
                    "体检报告",
                    request.originalFilename(),
                    1,
                    List.of(),
                    List.of(),
                    "免责声明",
                    "请结合原始报告并咨询专业医生复核");
        }
    }

    private static final class FakeFormatter extends ReportSafetyFormatter {
        @Override
        public String format(HealthReportParseResult result) {
            return "【体检报告解析】OK";
        }

        @Override
        public String formatRich(HealthReportParseResult result,
                                 java.util.List<ReportTrendAnalyzer.TrendItem> trends,
                                 ReportTrendAnalyzer.FollowUpSuggestions followUp,
                                 boolean hasHistory) {
            return "【体检报告解析】OK";
        }
    }

    /** 空 DataSource 实现，仅用于满足 HealthReportRepository 构造器签名 */
    private static final class NoopDataSource implements DataSource {
        @Override public Connection getConnection() throws SQLException { return null; }
        @Override public Connection getConnection(String username, String password) throws SQLException { return null; }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
