package com.github.wechat.cloudbot.modules.file.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 入站 docx / xlsx / zip / pdf → 纯文本，供注入 DeepSeek。
 *
 * <p>PDF 提取使用 PDFBox {@link org.apache.pdfbox.text.PDFTextStripper}，
 * 按阅读顺序输出文本。zip 内的 .pdf 条目同样支持。
 */
public final class DocumentTextExtractor {

    public record Limits(int maxZipEntries, int maxZipEntryBytes, int maxXlsxRows, int maxXlsxCols) {
        public static Limits defaults() {
            return new Limits(40, 256 * 1024, 500, 30);
        }
    }

    private DocumentTextExtractor() {}

    public static boolean isOfficeOrZip(String fileName) {
        String ext = TextFileSupport.extensionOf(fileName);
        return "docx".equals(ext) || "xlsx".equals(ext) || "zip".equals(ext) || "pdf".equals(ext);
    }

    public static Optional<String> extract(String fileName, byte[] data, Limits limits)
            throws Exception {
        if (data == null || data.length == 0) {
            return Optional.empty();
        }
        Limits lim = limits == null ? Limits.defaults() : limits;
        String ext = TextFileSupport.extensionOf(fileName);
        if (ext == null) {
            return Optional.empty();
        }
        return switch (ext) {
            case "docx" -> Optional.of(extractDocx(data));
            case "xlsx" -> Optional.of(extractXlsx(data, lim));
            case "zip" -> Optional.of(extractZip(data, lim));
            case "pdf" -> Optional.of(extractPdf(data));
            default -> Optional.empty();
        };
    }

    private static String extractDocx(byte[] data) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String t = p.getText();
                if (t != null && !t.isBlank()) {
                    sb.append(t.trim()).append('\n');
                }
            }
            for (XWPFTable table : doc.getTables()) {
                sb.append("[表格]\n");
                for (XWPFTableRow row : table.getRows()) {
                    StringBuilder line = new StringBuilder();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        if (!line.isEmpty()) {
                            line.append('\t');
                        }
                        line.append(cell.getText() == null ? "" : cell.getText().trim());
                    }
                    if (!line.isEmpty()) {
                        sb.append(line).append('\n');
                    }
                }
            }
        }
        return sb.toString().trim();
    }

    private static String extractXlsx(byte[] data, Limits lim) throws Exception {
        DataFormatter formatter = new DataFormatter();
        StringBuilder sb = new StringBuilder();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            int sheets = wb.getNumberOfSheets();
            for (int s = 0; s < sheets; s++) {
                Sheet sheet = wb.getSheetAt(s);
                sb.append("【Sheet: ")
                        .append(sheet.getSheetName())
                        .append("】\n");
                int rowsOut = 0;
                for (Row row : sheet) {
                    if (row == null) {
                        continue;
                    }
                    if (rowsOut >= lim.maxXlsxRows()) {
                        sb.append("…(行数已截断)\n");
                        break;
                    }
                    StringBuilder line = new StringBuilder();
                    int last = Math.min(row.getLastCellNum(), lim.maxXlsxCols());
                    for (int c = 0; c < last; c++) {
                        if (c > 0) {
                            line.append('\t');
                        }
                        Cell cell = row.getCell(c);
                        line.append(cell == null ? "" : formatter.formatCellValue(cell));
                    }
                    sb.append(line).append('\n');
                    rowsOut++;
                }
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    /**
     * 从 PDF 字节数组中提取纯文本，使用 PDFBox 按阅读顺序输出。
     * 扫描件（无内嵌文本层）会返回空字符串，由上层决定是否走 OCR 兜底。
     */
    private static String extractPdf(byte[] data) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(data))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            if (text != null && !text.isBlank()) {
                sb.append(text.trim());
            }
        }
        return sb.toString().trim();
    }

    private static String extractZip(byte[] data, Limits lim) throws IOException {
        StringBuilder sb = new StringBuilder();
        int entries = 0;
        long totalUncompressed = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (entries >= lim.maxZipEntries()) {
                    sb.append("\n…(zip 条目数已截断)\n");
                    break;
                }
                String name = entry.getName() == null ? "entry" : entry.getName();
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\")) {
                    continue;
                }
                byte[] buf = zis.readNBytes(lim.maxZipEntryBytes() + 1);
                if (buf.length > lim.maxZipEntryBytes()) {
                    sb.append("--- ")
                            .append(name)
                            .append(" ---\n(条目过大，已跳过)\n\n");
                    entries++;
                    continue;
                }
                totalUncompressed += buf.length;
                if (totalUncompressed > lim.maxZipEntryBytes() * (long) lim.maxZipEntries()) {
                    sb.append("\n…(zip 解压总量过大，已截断)\n");
                    break;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                sb.append("--- ").append(name).append(" ---\n");
                try {
                    if (lower.endsWith(".docx")) {
                        sb.append(extractDocx(buf));
                    } else if (lower.endsWith(".xlsx")) {
                        sb.append(extractXlsx(buf, lim));
                    } else if (lower.endsWith(".pdf")) {
                        sb.append(extractPdf(buf));
                    } else if (TextFileSupport.isTextExtension(name)) {
                        sb.append(TextFileSupport.decodeText(buf));
                    } else {
                        sb.append("(二进制或非文本，已跳过)");
                    }
                } catch (Exception e) {
                    sb.append("(解析失败: ").append(e.getMessage()).append(')');
                }
                sb.append("\n\n");
                entries++;
            }
        }
        return sb.toString().trim();
    }
}
