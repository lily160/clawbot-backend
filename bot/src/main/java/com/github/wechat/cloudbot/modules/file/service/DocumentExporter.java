package com.github.wechat.cloudbot.modules.file.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 出站：把模型给出的结构化文本打成 docx / xlsx / zip 字节。
 */
public final class DocumentExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record ExportedFile(String fileName, byte[] bytes) {}

    public record Limits(int maxXlsxRows, int maxXlsxCols, int maxZipEntries, int maxOutboundBytes) {
        public static Limits defaults() {
            return new Limits(500, 30, 40, 1_048_576);
        }
    }

    private DocumentExporter() {}

    /**
     * @param format docx / xlsx / zip
     * @param preferredName 用户或模型给的文件名
     * @param content docx=正文；xlsx=CSV 或 JSON 二维数组；zip=JSON 条目数组
     */
    public static ExportedFile export(
            String format, String preferredName, String content, Limits limits) throws Exception {
        Limits lim = limits == null ? Limits.defaults() : limits;
        String fmt = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if (content == null) {
            content = "";
        }
        byte[] bytes =
                switch (fmt) {
                    case "docx" -> toDocx(content);
                    case "xlsx" -> toXlsx(content, lim);
                    case "zip" -> toZip(content, lim);
                    default -> throw new IllegalArgumentException("不支持的导出格式: " + format);
                };
        if (bytes.length > lim.maxOutboundBytes()) {
            throw new IllegalArgumentException(
                    "导出文件过大（"
                            + TextFileSupport.formatSizeKb(bytes.length)
                            + "），上限 "
                            + TextFileSupport.formatSizeKb(lim.maxOutboundBytes()));
        }
        return new ExportedFile(sanitizeName(preferredName, fmt), bytes);
    }

    private static String sanitizeName(String preferred, String format) {
        String raw = preferred == null || preferred.isBlank() ? "export." + format : preferred.trim();
        int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
        if (slash >= 0) {
            raw = raw.substring(slash + 1);
        }
        raw = raw.replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fff]", "_");
        String ext = TextFileSupport.extensionOf(raw);
        if (!format.equals(ext)) {
            int dot = raw.lastIndexOf('.');
            String base = dot > 0 ? raw.substring(0, dot) : raw;
            if (base.isBlank()) {
                base = "export";
            }
            raw = base + "." + format;
        }
        if (raw.length() > 80) {
            // 按 Unicode 码点安全截断，避免从 UTF-16 增补平面字符中间切出孤立代理项
            raw = truncateByCodePoints(raw, 60) + "." + format;
        }
        return raw;
    }

    /** 按 Unicode 码点安全截断。 */
    private static String truncateByCodePoints(String text, int maxCodePoints) {
        return text.codePoints()
                .limit(maxCodePoints)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    private static byte[] toDocx(String content) throws Exception {
        try (XWPFDocument doc = new XWPFDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            for (String line : lines) {
                XWPFParagraph p = doc.createParagraph();
                XWPFRun run = p.createRun();
                run.setText(line);
            }
            if (lines.length == 0) {
                doc.createParagraph().createRun().setText("");
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] toXlsx(String content, Limits lim) throws Exception {
        List<List<String>> rows = parseTable(content, lim);
        try (XSSFWorkbook wb = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            int r = 0;
            for (List<String> rowVals : rows) {
                Row row = sheet.createRow(r++);
                int c = 0;
                for (String v : rowVals) {
                    row.createCell(c++).setCellValue(v == null ? "" : v);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static List<List<String>> parseTable(String content, Limits lim) throws Exception {
        String trimmed = content.trim();
        if (trimmed.startsWith("[")) {
            JsonNode root = MAPPER.readTree(trimmed);
            List<List<String>> rows = new ArrayList<>();
            if (!root.isArray()) {
                throw new IllegalArgumentException("xlsx JSON 须为二维数组");
            }
            int rowCount = 0;
            for (JsonNode rowNode : root) {
                if (rowCount >= lim.maxXlsxRows()) {
                    break;
                }
                List<String> row = new ArrayList<>();
                if (rowNode.isArray()) {
                    int col = 0;
                    for (JsonNode cell : rowNode) {
                        if (col >= lim.maxXlsxCols()) {
                            break;
                        }
                        row.add(cell.isNull() ? "" : cell.asText(""));
                        col++;
                    }
                } else {
                    row.add(rowNode.asText(""));
                }
                rows.add(row);
                rowCount++;
            }
            return rows;
        }
        // CSV / TSV 文本
        List<List<String>> rows = new ArrayList<>();
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String line : lines) {
            if (rows.size() >= lim.maxXlsxRows()) {
                break;
            }
            if (line.isEmpty() && rows.isEmpty()) {
                continue;
            }
            String[] parts = line.contains("\t") ? line.split("\t", -1) : line.split(",", -1);
            List<String> row = new ArrayList<>();
            for (int i = 0; i < parts.length && i < lim.maxXlsxCols(); i++) {
                row.add(stripCsvQuotes(parts[i].trim()));
            }
            rows.add(row);
        }
        if (rows.isEmpty()) {
            rows.add(List.of(content));
        }
        return rows;
    }

    private static String stripCsvQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1).replace("\"\"", "\"");
        }
        return s;
    }

    private static byte[] toZip(String content, Limits lim) throws Exception {
        JsonNode root = MAPPER.readTree(content.trim());
        if (!root.isArray()) {
            throw new IllegalArgumentException("zip 内容须为 JSON 数组 [{path,text},...]");
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ZipOutputStream zos = new ZipOutputStream(bos)) {
            int n = 0;
            for (JsonNode item : root) {
                if (n >= lim.maxZipEntries()) {
                    break;
                }
                String path = textField(item, "path");
                if (path.isBlank()) {
                    path = textField(item, "name");
                }
                String text = textField(item, "text");
                if (text.isEmpty()) {
                    text = textField(item, "content");
                }
                path = sanitizeZipPath(path, n);
                byte[] entryBytes = text.getBytes(StandardCharsets.UTF_8);
                ZipEntry entry = new ZipEntry(path);
                zos.putNextEntry(entry);
                zos.write(entryBytes);
                zos.closeEntry();
                n++;
            }
            if (n == 0) {
                ZipEntry entry = new ZipEntry("readme.txt");
                zos.putNextEntry(entry);
                zos.write("empty zip".getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            zos.finish();
            return bos.toByteArray();
        }
    }

    private static String textField(JsonNode item, String field) {
        JsonNode n = item.get(field);
        return n == null || n.isNull() ? "" : n.asText("").trim();
    }

    private static String sanitizeZipPath(String path, int index) {
        String p = path == null || path.isBlank() ? "file" + index + ".txt" : path.trim();
        p = p.replace('\\', '/');
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.contains("..")) {
            p = "file" + index + ".txt";
        }
        if (!TextFileSupport.isTextExtension(p)) {
            int slash = p.lastIndexOf('/');
            String base = slash >= 0 ? p.substring(slash + 1) : p;
            int dot = base.lastIndexOf('.');
            String name = dot > 0 ? base.substring(0, dot) : base;
            if (name.isBlank()) {
                name = "file" + index;
            }
            p = (slash >= 0 ? p.substring(0, slash + 1) : "") + name + ".txt";
        }
        return p;
    }
}
