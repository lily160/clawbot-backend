package com.github.wechat.cloudbot.modules.health.report.service;

import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 将体检报告解析结果导出为格式化的 Word 文档，适合普通用户阅读。
 */
@Component
public class ReportWordExporter {

    private static final String FONT_CN = "微软雅黑";
    private static final String FONT_EN = "Calibri";

    public byte[] export(HealthReportParseResult result) throws Exception {
        return export(result, null, null);
    }

    /**
     * @param trends 与上次报告的对比趋势（可为null）
     * @param followUp 复查建议（可为null）
     */
    public byte[] export(HealthReportParseResult result,
                         List<ReportTrendAnalyzer.TrendItem> trends,
                         ReportTrendAnalyzer.FollowUpSuggestions followUp) throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // ── 标题 ──
            addTitle(doc, "体检报告解析");
            addNormal(doc, "报告来源：" + result.sourceFilename());
            addNormal(doc, "解析日期：" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            addNormal(doc, "共 " + result.pageCount() + " 页");
            addBlank(doc);

            // ── 统计摘要 ──
            addHeading(doc, "一、统计摘要");
            List<HealthIndicator> all = result.indicators();
            long normal = all.stream().filter(i -> i.status() == IndicatorStatus.NORMAL).count();
            long high = all.stream().filter(i -> i.status() == IndicatorStatus.HIGH).count();
            long low = all.stream().filter(i -> i.status() == IndicatorStatus.LOW).count();
            long abnormal = all.stream().filter(i -> i.status() == IndicatorStatus.ABNORMAL).count();
            long unknown = all.stream().filter(i -> i.status() == IndicatorStatus.UNKNOWN).count();

            addNormal(doc, "共识别 " + all.size() + " 项指标，其中：");
            addNormal(doc, "  ✓ 正常：" + normal + " 项");
            if (high > 0) addColored(doc, "  ↑ 偏高：" + high + " 项", "FF0000");
            if (low > 0) addColored(doc, "  ↓ 偏低：" + low + " 项", "FF0000");
            if (abnormal > 0) addColored(doc, "  ⚠ 异常：" + abnormal + " 项", "FF0000");
            if (unknown > 0) addNormal(doc, "  ? 无参考范围：" + unknown + " 项");
            addBlank(doc);

            // ── 指标明细表 ──
            addHeading(doc, "二、指标明细");
            XWPFTable table = doc.createTable(all.size() + 1, 6);
            setTableStyle(table);
            // 表头
            String[] headers = {"项目", "结果", "单位", "参考范围", "判定", "页码"};
            fillRow(table, 0, headers, "4472C4", true);
            // 数据行
            int row = 1;
            for (HealthIndicator ind : all) {
                String statusText = switch (ind.status()) {
                    case NORMAL -> "正常";
                    case HIGH -> "偏高";
                    case LOW -> "偏低";
                    case ABNORMAL -> "异常";
                    case UNKNOWN -> "—";
                };
                String color = ind.status() == IndicatorStatus.NORMAL ? null
                        : (ind.status() == IndicatorStatus.UNKNOWN ? "808080" : "FF0000");
                String[] cells = {
                        ind.name(),
                        ind.value(),
                        blankOr(ind.unit(), "—"),
                        blankOr(ind.referenceRange(), "—"),
                        statusText,
                        String.valueOf(ind.pageNo())
                };
                fillRow(table, row++, cells, color, false);
            }
            addBlank(doc);

            // ── 偏离分析 ──
            List<HealthIndicator> deviated = result.abnormalIndicators();
            if (!deviated.isEmpty()) {
                addHeading(doc, "三、偏离分析");
                addNormal(doc, "以下 " + deviated.size() + " 项指标偏离参考范围，建议关注：");
                addBlank(doc);
                for (HealthIndicator ind : deviated) {
                    String flag = switch (ind.status()) {
                        case HIGH -> "【偏高】";
                        case LOW -> "【偏低】";
                        default -> "【异常】";
                    };
                    StringBuilder sb = new StringBuilder();
                    sb.append("• ").append(flag).append(" ").append(ind.name())
                            .append("：").append(ind.value());
                    if (!ind.unit().isBlank()) sb.append(" ").append(ind.unit());
                    if (!ind.referenceRange().isBlank())
                        sb.append("（参考范围：").append(ind.referenceRange()).append("）");
                    addColored(doc, sb.toString(), "FF0000");
                }
                addBlank(doc);
            }

            // ── 原始数据 ──
            if (deviated.isEmpty()) {
                addHeading(doc, "三、结论");
                addNormal(doc, "本次解析未发现明显偏离参考范围的指标。如有不适请及时就医。");
            }
            addBlank(doc);

            // ── 趋势对比（如有历史数据）──
            int sectionNo = 4;
            if (trends != null && !trends.isEmpty()) {
                addHeading(doc, sectionLabel(sectionNo++, "指标变化趋势"));
                addNormal(doc, "以下指标与上次报告存在变化，供参考：");
                addBlank(doc);
                for (ReportTrendAnalyzer.TrendItem t : trends) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("• ").append(t.name()).append("：")
                            .append(t.previousValue()).append(" → ").append(t.currentValue());
                    if (!t.direction().isBlank()) sb.append("（").append(t.direction()).append("）");
                    addNormal(doc, sb.toString());
                }
                addBlank(doc);
            }

            // ── 复查建议 ──
            if (followUp != null && !followUp.items().isEmpty()) {
                addHeading(doc, sectionLabel(sectionNo++, "复查建议"));
                for (String item : followUp.items()) {
                    addNormal(doc, "• " + item);
                }
                addBlank(doc);
            }

            // ── 报告后续管理 ──
            addHeading(doc, sectionLabel(sectionNo++, "报告后续管理"));
            addNormal(doc, "1. 本报告已自动保存，下次上传体检报告时可自动对比指标变化趋势。");
            addNormal(doc, "2. 如需设置复查提醒，请回复[设置复查提醒 + 指标名 + 日期]，系统会在指定日期提醒您。");
            addNormal(doc, "3. 建议每 6-12 个月进行一次全面体检，保持指标动态跟踪。");
            addNormal(doc, "4. 如有不适请及时线下就医，本报告仅供参考。");
            addBlank(doc);

            // ── 免责声明 ──
            addHeading(doc, sectionLabel(sectionNo++, "免责声明"));
            addNormal(doc, result.disclaimer());
            addNormal(doc, result.reviewPrompt());

            doc.write(out);
            return out.toByteArray();
        }
    }

    private void addTitle(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(true);
        r.setFontSize(22);
        r.setFontFamily(FONT_CN);
        r.setColor("1F4E79");
    }

    private void addHeading(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(300);
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(true);
        r.setFontSize(14);
        r.setFontFamily(FONT_CN);
        r.setColor("2E75B6");
    }

    private void addNormal(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontSize(11);
        r.setFontFamily(FONT_CN);
    }

    private void addColored(XWPFDocument doc, String text, String color) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontSize(11);
        r.setFontFamily(FONT_CN);
        r.setColor(color);
    }

    private void addBlank(XWPFDocument doc) {
        doc.createParagraph();
    }

    private void setTableStyle(XWPFTable table) {
        CTTbl ct = table.getCTTbl();
        CTTblPr pr = ct.getTblPr() == null ? ct.addNewTblPr() : ct.getTblPr();
        CTTblBorders borders = pr.addNewTblBorders();
        setBorder(borders.addNewTop(), "999999");
        setBorder(borders.addNewBottom(), "999999");
        setBorder(borders.addNewLeft(), "999999");
        setBorder(borders.addNewRight(), "999999");
        setBorder(borders.addNewInsideH(), "999999");
        setBorder(borders.addNewInsideV(), "999999");
    }

    private void setBorder(CTBorder border, String color) {
        border.setVal(STBorder.SINGLE);
        border.setSz(BigInteger.valueOf(4));
        border.setColor(color);
    }

    private void fillRow(XWPFTable table, int rowIdx, String[] cells, String color, boolean isHeader) {
        XWPFTableRow row = table.getRow(rowIdx);
        for (int i = 0; i < cells.length; i++) {
            XWPFTableCell cell = row.getCell(i);
            if (cell == null) cell = row.createCell();
            XWPFParagraph p = cell.getParagraphs().get(0);
            p.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun r = p.createRun();
            r.setText(cells[i]);
            r.setFontSize(isHeader ? 10 : 10);
            r.setFontFamily(FONT_CN);
            if (isHeader) {
                r.setBold(true);
                r.setColor("FFFFFF");
                cell.setColor("4472C4");
            } else if (color != null) {
                r.setColor(color);
                r.setBold(true);
            }
        }
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String sectionLabel(int no, String title) {
        String[] labels = {"一", "二", "三", "四", "五", "六", "七", "八"};
        return (no <= labels.length ? labels[no - 1] : String.valueOf(no)) + "、" + title;
    }
}
