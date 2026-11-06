package com.github.wechat.cloudbot.modules.health.report.infra;

import com.github.wechat.cloudbot.modules.health.report.domain.ReportPageText;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfReportExtractorTest {

    @Test
    void extractsTextFromTextPdfAndMarksBlankPdfForOcr() throws Exception {
        Path textPdf = writePdf("Hemoglobin 138 g/L Reference Range 120-160");
        Path blankPdf = writePdf("");

        PdfReportExtractor extractor = new PdfReportExtractor();

        List<ReportPageText> textPages = extractor.extractText(textPdf);
        List<ReportPageText> blankPages = extractor.extractText(blankPdf);

        assertEquals(1, textPages.size());
        assertEquals(1, blankPages.size());
        assertTrue(textPages.get(0).text().contains("Hemoglobin 138 g/L"));
        assertEquals("PDF_TEXT", textPages.get(0).source());
        assertEquals("OCR_REQUIRED", blankPages.get(0).source());
        assertTrue(blankPages.get(0).text().isBlank());
    }

    @Test
    void rendersPageAsNonEmptyImageBytes() throws Exception {
        Path pdf = writePdf("Hemoglobin 138 g/L Reference Range 120-160");

        PdfReportExtractor extractor = new PdfReportExtractor();
        byte[] imageBytes = extractor.renderPage(pdf, 1, 150);

        assertFalse(imageBytes.length == 0);
    }

    @Test
    void extractsMultiPagePdfAndMarksSourceCorrectly() throws Exception {
        Path pdf = writeMultiPagePdf(List.of(
                "Hemoglobin 138 g/L Reference Range 120-160",
                "",
                "Glucose 7.2 mmol/L 3.9-6.1"));

        PdfReportExtractor extractor = new PdfReportExtractor();
        List<ReportPageText> pages = extractor.extractText(pdf);

        assertEquals(3, pages.size());
        assertEquals(1, pages.get(0).pageNo());
        assertEquals("PDF_TEXT", pages.get(0).source());
        assertTrue(pages.get(0).text().contains("Hemoglobin"));

        assertEquals(2, pages.get(1).pageNo());
        assertEquals("OCR_REQUIRED", pages.get(1).source());

        assertEquals(3, pages.get(2).pageNo());
        assertEquals("PDF_TEXT", pages.get(2).source());
        assertTrue(pages.get(2).text().contains("Glucose"));
    }

    @Test
    void rendersPageAtDifferentDpi() throws Exception {
        Path pdf = writePdf("Test content for rendering");
        PdfReportExtractor extractor = new PdfReportExtractor();

        byte[] lowRes = extractor.renderPage(pdf, 1, 72);
        byte[] highRes = extractor.renderPage(pdf, 1, 300);

        assertFalse(lowRes.length == 0);
        assertFalse(highRes.length == 0);
        // Higher DPI should produce larger image
        assertTrue(highRes.length > lowRes.length);
    }

    @Test
    void rejectsInvalidRenderPageArguments() throws Exception {
        Path pdf = writePdf("Test");
        PdfReportExtractor extractor = new PdfReportExtractor();

        assertThrows(IllegalArgumentException.class, () -> extractor.renderPage(pdf, 0, 150));
        assertThrows(IllegalArgumentException.class, () -> extractor.renderPage(pdf, -1, 150));
        assertThrows(IllegalArgumentException.class, () -> extractor.renderPage(pdf, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> extractor.renderPage(pdf, 1, -72));
    }

    @Test
    void rejectsRenderPageOutOfRange() throws Exception {
        Path pdf = writePdf("Test");
        PdfReportExtractor extractor = new PdfReportExtractor();

        assertThrows(IllegalArgumentException.class, () -> extractor.renderPage(pdf, 999, 150));
    }

    @Test
    void rejectsPageCountExceedingLimit() throws Exception {
        Path pdf = writePdfWithPageCount(31);

        PdfReportExtractor extractor = new PdfReportExtractor();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> extractor.extractText(pdf));
        assertTrue(ex.getMessage().contains("page count"));
    }

    private static Path writePdf(String content) throws Exception {
        Path file = Files.createTempFile("health-report", ".pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            if (!content.isBlank()) {
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(PDType1Font.HELVETICA, 12);
                    stream.newLineAtOffset(72, 720);
                    stream.showText(content);
                    stream.endText();
                }
            }

            document.save(file.toFile());
        }
        return file;
    }

    private static Path writeMultiPagePdf(List<String> pageContents) throws Exception {
        Path file = Files.createTempFile("health-report", ".pdf");
        try (PDDocument document = new PDDocument()) {
            for (String content : pageContents) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);

                if (!content.isBlank()) {
                    try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                        stream.beginText();
                        stream.setFont(PDType1Font.HELVETICA, 12);
                        stream.newLineAtOffset(72, 720);
                        stream.showText(content);
                        stream.endText();
                    }
                }
            }
            document.save(file.toFile());
        }
        return file;
    }

    private static Path writePdfWithPageCount(int pageCount) throws Exception {
        Path file = Files.createTempFile("health-report", ".pdf");
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(PDType1Font.HELVETICA, 12);
                    stream.newLineAtOffset(72, 720);
                    stream.showText("Page " + (i + 1) + " content text for testing");
                    stream.endText();
                }
            }
            document.save(file.toFile());
        }
        return file;
    }
}
