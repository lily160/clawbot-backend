package com.github.wechat.cloudbot.modules.health.report.service;

import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseRequest;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 报告模块统一响应构建器。
 *
 * <p>封装 "解析 → 存储 → 趋势对比 → 复查建议 → 人性化文本 → Word 导出" 全链路，
 * 供 {@code AgentOrchestrator.tryParseHealthReport()} 等上游调用方一行接入。
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * ReportResponseBuilder.ReportResponse response =
 *     reportResponseBuilder.build(userId, pdfPath, filename);
 * String wechatText = response.humanFriendlyText();  // 直接发给用户
 * byte[] docx = response.wordDocxBytes();            // Word 附件
 * }</pre>
 */
@Service
public class ReportResponseBuilder {

    private static final Logger log = LoggerFactory.getLogger(ReportResponseBuilder.class);

    private final HealthReportParseService parseService;
    private final HealthReportRepository repository;
    private final ReportTrendAnalyzer trendAnalyzer;
    private final ReportSafetyFormatter formatter;
    private final ReportWordExporter wordExporter;

    public ReportResponseBuilder(
            HealthReportParseService parseService,
            HealthReportRepository repository,
            ReportTrendAnalyzer trendAnalyzer,
            ReportSafetyFormatter formatter,
            ReportWordExporter wordExporter) {
        this.parseService = parseService;
        this.repository = repository;
        this.trendAnalyzer = trendAnalyzer;
        this.formatter = formatter;
        this.wordExporter = wordExporter;
    }

    /**
     * 一站式构建报告响应：解析 → 存储 → 趋势 → 建议 → 文本 → Word。
     *
     * @param userId   微信用户 ID
     * @param pdfPath  PDF 文件路径
     * @param filename 原始文件名
     * @return 完整的报告响应（人性化文本 + Word 文件 + 结构化数据）
     */
    public ReportResponse build(String userId, Path pdfPath, String filename) {
        // 1. 解析
        HealthReportParseResult result = parseService.parse(
                new HealthReportParseRequest(pdfPath, filename));

        // 2. 持久化存储
        long reportId = 0;
        try {
            String reportJson = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(result);
            reportId = repository.saveReportWithIndicators(userId, filename, result, reportJson);
            log.info("报告已存储 reportId={} userId={}", reportId, userId);
        } catch (Exception e) {
            log.warn("报告存储失败（不影响主流程）: {}", e.getMessage());
        }

        // 3. 历史数据（趋势对比）
        Map<String, HealthIndicator> lastIndicators = null;
        boolean hasHistory = false;
        try {
            lastIndicators = repository.getLastReportIndicators(userId);
            hasHistory = lastIndicators != null && !lastIndicators.isEmpty();
        } catch (Exception e) {
            log.warn("获取历史报告失败: {}", e.getMessage());
        }

        // 4. 趋势分析 + 复查建议
        List<ReportTrendAnalyzer.TrendItem> trends =
                trendAnalyzer.analyze(result.indicators(), lastIndicators);
        ReportTrendAnalyzer.FollowUpSuggestions followUp =
                trendAnalyzer.suggest(result.abnormalIndicators(), trends);
        List<ReportTrendAnalyzer.FollowUpPlan> followUpPlans =
                trendAnalyzer.buildFollowUpPlans(result.abnormalIndicators(), trends);

        // 5. 人性化文本
        String humanFriendlyText = formatter.formatRich(result, trends, followUp, hasHistory);

        // 6. Word 导出
        byte[] docxBytes;
        try {
            docxBytes = wordExporter.export(result, trends, followUp);
        } catch (Exception e) {
            log.error("Word 导出失败: {}", e.getMessage());
            docxBytes = new byte[0];
        }

        return new ReportResponse(
                result,
                humanFriendlyText,
                docxBytes,
                trends,
                followUp,
                followUpPlans,
                hasHistory,
                reportId);
    }

    /**
     * 完整报告响应，包含上游调用方需要的所有信息。
     */
    public record ReportResponse(
            HealthReportParseResult parseResult,
            String humanFriendlyText,
            byte[] wordDocxBytes,
            List<ReportTrendAnalyzer.TrendItem> trends,
            ReportTrendAnalyzer.FollowUpSuggestions followUpSuggestions,
            List<ReportTrendAnalyzer.FollowUpPlan> followUpPlans,
            boolean hasHistory,
            long reportId) {

        /** 微信文本回复（人性化、可直接发送）。 */
        public String wechatText() {
            return humanFriendlyText;
        }

        /** Word 文档字节。 */
        public byte[] docxBytes() {
            return wordDocxBytes;
        }

        /** Word 文档推荐文件名。 */
        public String docxFilename() {
            String base = parseResult.sourceFilename().replaceAll("(?i)\\.pdf$", "");
            return base + "-解析报告.docx";
        }

        /** 异常项数量。 */
        public int abnormalCount() {
            return parseResult.abnormalIndicators().size();
        }

        /** 指标总数。 */
        public int indicatorCount() {
            return parseResult.indicators().size();
        }

        /** 是否有异常需要关注。 */
        public boolean hasAbnormal() {
            return abnormalCount() > 0;
        }
    }
}
