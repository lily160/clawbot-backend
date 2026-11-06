package com.github.wechat.cloudbot.modules.health.report.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.cloudbot.modules.health.common.HealthToolNames;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseRequest;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import com.github.wechat.cloudbot.modules.health.report.service.HealthReportParseService;
import com.github.wechat.cloudbot.modules.health.report.service.HealthReportRepository;
import com.github.wechat.cloudbot.modules.health.report.service.ReportSafetyFormatter;
import com.github.wechat.cloudbot.modules.health.report.service.ReportTrendAnalyzer;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Function Calling 工具：体检报告解析。
 *
 * <p>支持两种模式：
 * <ul>
 *   <li><b>基础模式</b>：无 userId 或首次报告 → 返回格式化摘要 + 结构化指标</li>
 *   <li><b>增强模式</b>：有 userId 且有历史报告 → 附加趋势对比 + 分级复查建议 + 跟进计划</li>
 * </ul>
 */
@Component
public class HealthReportParseTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(HealthReportParseTool.class);
    private static final String PDF_SIGNATURE = "%PDF-";

    private final HealthReportParseService healthReportParseService;
    private final ReportSafetyFormatter formatter;
    private final HealthReportRepository repository;
    private final ReportTrendAnalyzer trendAnalyzer;
    private final ObjectMapper objectMapper;

    public HealthReportParseTool(
            HealthReportParseService healthReportParseService,
            ReportSafetyFormatter formatter,
            HealthReportRepository repository,
            ReportTrendAnalyzer trendAnalyzer,
            ObjectMapper objectMapper) {
        this.healthReportParseService = healthReportParseService;
        this.formatter = formatter;
        this.repository = repository;
        this.trendAnalyzer = trendAnalyzer;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                HealthToolNames.REPORT_PARSE,
                "解析体检 PDF，输出结构化指标、异常项、免责声明和复查提示。只接受本地 PDF 文件路径。",
                """
                {"type":"object","properties":{"filePath":{"type":"string","description":"体检 PDF 的本地路径或上传后路径，必填"},"originalFilename":{"type":"string","description":"原始文件名，可选；不传则默认使用路径文件名"}},"required":["filePath"]}
                """);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String filePath = ToolArgs.text(args, "filePath");
            if (filePath.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "filePath 不能为空");
            }

            Path pdfPath = validatePdfPath(filePath);
            if (!Files.exists(pdfPath)) {
                return ToolResult.fail(call.id(), call.name(), "文件不存在：" + pdfPath);
            }
            if (!looksLikePdf(pdfPath)) {
                return ToolResult.fail(call.id(), call.name(), "只支持 PDF 文件，请检查扩展名和文件头");
            }

            String originalFilename = ToolArgs.text(args, "originalFilename");
            if (originalFilename.isBlank()) {
                originalFilename = pdfPath.getFileName().toString();
            }

            HealthReportParseResult result =
                    healthReportParseService.parse(new HealthReportParseRequest(pdfPath, originalFilename));

            // 尝试获取历史趋势
            String userId = call.userId();
            List<ReportTrendAnalyzer.TrendItem> trends = List.of();
            ReportTrendAnalyzer.FollowUpSuggestions followUp = null;
            List<ReportTrendAnalyzer.FollowUpPlan> followUpPlans = List.of();
            boolean hasHistory = false;

            if (userId != null && !userId.isBlank()) {
                try {
                    // 存储本次报告
                    String reportJson = objectMapper.writeValueAsString(result);
                    long reportId = repository.saveReportWithIndicators(userId, originalFilename, result, reportJson);
                    log.info("报告已存储 reportId={} userId={}", reportId, userId);

                    // 获取历史趋势
                    Map<String, HealthIndicator> lastIndicators = repository.getLastReportIndicators(userId);
                    hasHistory = lastIndicators != null && !lastIndicators.isEmpty();
                    if (hasHistory) {
                        trends = trendAnalyzer.analyze(result.indicators(), lastIndicators);
                        followUp = trendAnalyzer.suggest(result.abnormalIndicators(), trends);
                        followUpPlans = trendAnalyzer.buildFollowUpPlans(result.abnormalIndicators(), trends);
                    }
                } catch (Exception e) {
                    log.warn("获取历史趋势失败（不影响主流程）: {}", e.getMessage());
                }
            }

            // 无历史时仍生成复查建议（基于当前异常项）
            if (followUp == null && !result.abnormalIndicators().isEmpty()) {
                followUp = trendAnalyzer.suggest(result.abnormalIndicators(), List.of());
            }

            // 使用增强格式化
            String message = formatter.formatRich(result, trends, followUp, hasHistory);

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("message", message);
            payload.set("result", objectMapper.valueToTree(result));

            // 附加趋势信息
            if (!trends.isEmpty()) {
                ArrayNode trendsNode = objectMapper.createArrayNode();
                for (ReportTrendAnalyzer.TrendItem t : trends) {
                    ObjectNode tn = objectMapper.createObjectNode();
                    tn.put("name", t.name());
                    tn.put("currentValue", t.currentValue());
                    tn.put("previousValue", t.previousValue());
                    tn.put("unit", t.unit());
                    tn.put("referenceRange", t.referenceRange());
                    tn.put("direction", t.direction());
                    trendsNode.add(tn);
                }
                payload.set("trends", trendsNode);
            }

            // 附加复查建议
            if (followUp != null && followUp.items() != null && !followUp.items().isEmpty()) {
                ArrayNode suggestionsNode = objectMapper.createArrayNode();
                for (String item : followUp.items()) {
                    suggestionsNode.add(item);
                }
                ObjectNode followUpNode = objectMapper.createObjectNode();
                followUpNode.put("urgency", followUp.urgency());
                followUpNode.put("hasNewAbnormal", followUp.hasNewAbnormal());
                followUpNode.set("items", suggestionsNode);
                payload.set("followUpSuggestions", followUpNode);
            }

            // 附加跟进计划
            if (!followUpPlans.isEmpty()) {
                payload.set("followUpPlans", objectMapper.valueToTree(followUpPlans));
            }

            payload.put("hasHistory", hasHistory);

            return ToolResult.ok(call.id(), call.name(), objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            log.error("报告解析失败: {}", e.getMessage(), e);
            return ToolResult.fail(call.id(), call.name(), "报告解析失败：" + e.getMessage());
        }
    }

    private static Path validatePdfPath(String rawPath) {
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        // 路径穿越防护：只允许解析系统临时目录下的 PDF（正常链路用 Files.createTempFile 落盘），
        // 绝对路径/符号链接/UNC 等外部路径一律拒绝
        Path allowed = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!path.startsWith(allowed)) {
            throw new IllegalArgumentException("只允许解析系统临时目录下的 PDF 文件");
        }
        return path;
    }

    private static boolean looksLikePdf(Path pdfPath) throws IOException {
        String name = pdfPath.getFileName() == null ? "" : pdfPath.getFileName().toString().toLowerCase();
        if (!name.endsWith(".pdf")) {
            return false;
        }
        try (var in = Files.newInputStream(pdfPath)) {
            byte[] header = in.readNBytes(PDF_SIGNATURE.length());
            return new String(header).startsWith(PDF_SIGNATURE);
        }
    }
}
