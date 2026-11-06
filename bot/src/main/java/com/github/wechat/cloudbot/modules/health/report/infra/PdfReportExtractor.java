package com.github.wechat.cloudbot.modules.health.report.infra;

import com.github.wechat.cloudbot.modules.health.report.domain.ReportPageText;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class PdfReportExtractor {

    private static final int MAX_PAGES = 30;
    private static final int TEXT_LENGTH_THRESHOLD = 20;

    public List<ReportPageText> extractText(Path pdfPath) {
        Objects.requireNonNull(pdfPath, "pdfPath");
        try (PDDocument document = PDDocument.load(pdfPath.toFile())) {
            int pageCount = document.getNumberOfPages();
            if (pageCount > MAX_PAGES) {
                throw new IllegalArgumentException("PDF page count exceeds limit: " + pageCount);
            }

            PDFTextStripper stripper = new PDFTextStripper();
            List<ReportPageText> pages = new ArrayList<>(pageCount);
            for (int pageNo = 1; pageNo <= pageCount; pageNo++) {
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String normalizedText = normalize(stripper.getText(document));
                // 短文本页通常是扫描图片或空白页，标记后由上层决定是否走 OCR。
                String source = isPdfText(normalizedText) ? "PDF_TEXT" : "OCR_REQUIRED";
                pages.add(new ReportPageText(pageNo, normalizedText, source));
            }
            return List.copyOf(pages);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to extract PDF text from " + pdfPath, e);
        }
    }

    public byte[] renderPage(Path pdfPath, int pageNo, int dpi) {
        Objects.requireNonNull(pdfPath, "pdfPath");
        if (pageNo < 1) {
            throw new IllegalArgumentException("pageNo must be >= 1");
        }
        if (dpi <= 0) {
            throw new IllegalArgumentException("dpi must be positive");
        }

        try (PDDocument document = PDDocument.load(pdfPath.toFile())) {
            if (pageNo > document.getNumberOfPages()) {
                throw new IllegalArgumentException("pageNo exceeds page count: " + pageNo);
            }

            // 渲染单页图片只用于 OCR 兜底，不会影响纯文本页的解析路径。
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(pageNo - 1, dpi);
            try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (!ImageIO.write(image, "jpg", out)) {
                    throw new IllegalStateException("JPEG encoder is not available");
                }
                return out.toByteArray();
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to render PDF page from " + pdfPath, e);
        }
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r", "\n")
                .replace('\u00A0', ' ')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static boolean isPdfText(String text) {
        // Keep the threshold conservative; we only want pages with usable native text to skip OCR.
        // 阈值故意设得保守一点，宁可多走一次 OCR，也不要把碎片化文本误判为可直接解析。
        return text.replaceAll("\\s+", "").length() >= TEXT_LENGTH_THRESHOLD;
    }
}
