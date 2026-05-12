package com.sqldomaingen.validation;

import com.sqldomaingen.util.Constants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the unified validation report as a simple PDF document
 * without external PDF library dependencies.
 */
public class ValidationReportPdfWriter {

    /**
     * Writes the validation report to a PDF file.
     *
     * @param report validation report
     * @param outputPath target PDF path
     * @throws IOException if writing fails
     */
    public void writePdf(GenerationValidationReport report, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());

        List<PdfLine> lines = buildReportLines(report);
        byte[] pdfBytes = buildPdf(lines);

        Files.write(outputPath, pdfBytes);
    }

    /**
     * Builds styled report lines for PDF rendering.
     *
     * @param report validation report
     * @return styled PDF lines
     */
    private List<PdfLine> buildReportLines(GenerationValidationReport report) {
        List<PdfLine> lines = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        lines.add(PdfLine.title("GENERATION VALIDATION REPORT"));
        lines.add(PdfLine.blank());

        lines.add(PdfLine.label("Generated at: " + report.getGeneratedAt().format(formatter)));
        lines.add(PdfLine.label("Author: " + report.getAuthor()));
        lines.add(PdfLine.label("Input file: " + report.getInputFile()));
        lines.add(PdfLine.label("Output dir: " + report.getOutputDir()));
        lines.add(PdfLine.label("Base package: " + report.getBasePackage()));
        lines.add(PdfLine.blank());

        lines.add(PdfLine.label("Total issues: " + report.getTotalViolationCount()));
        lines.add(PdfLine.label("Total warnings: " + report.getTotalWarningCount()));
        lines.add(PdfLine.blank());

        for (GenerationValidationReport.Section section : report.getSections()) {
            appendSectionLines(lines, section);
        }

        return lines;
    }

    /**
     * Appends one report section to the PDF line list.
     *
     * @param lines target PDF lines
     * @param section report section
     */
    private void appendSectionLines(List<PdfLine> lines, GenerationValidationReport.Section section) {
        lines.add(PdfLine.blank());
        lines.add(PdfLine.section(section.title()));

        appendDetails(lines, section);
        appendViolations(lines, section);
        appendWarnings(lines, section);
    }

    /**
     * Appends section details.
     *
     * @param lines target PDF lines
     * @param section report section
     */
    private void appendDetails(List<PdfLine> lines, GenerationValidationReport.Section section) {
        if (section.details().isEmpty()) {
            return;
        }

        lines.add(PdfLine.subheading("Details"));

        if ("Schema Tables".equals(section.title())) {
            int index = 1;

            for (String detail : section.details()) {
                lines.add(PdfLine.detail(index + ". " + detail));
                index++;
            }

            return;
        }

        for (String detail : section.details()) {
            lines.add(PdfLine.detail("- " + detail));
        }
    }

    /**
     * Appends section violations.
     *
     * @param lines target PDF lines
     * @param section report section
     */
    private void appendViolations(List<PdfLine> lines, GenerationValidationReport.Section section) {
        lines.add(PdfLine.subheading("Violations"));

        if (section.violations().isEmpty()) {
            lines.add(PdfLine.detail("None"));
            return;
        }

        for (String violation : section.violations()) {
            lines.add(PdfLine.violation("[VIOLATION] " + violation));
        }
    }

    /**
     * Appends section warnings.
     *
     * @param lines target PDF lines
     * @param section report section
     */
    private void appendWarnings(List<PdfLine> lines, GenerationValidationReport.Section section) {
        lines.add(PdfLine.subheading("Warnings"));

        if (section.warnings().isEmpty()) {
            lines.add(PdfLine.detail("None"));
            return;
        }

        for (String warning : section.warnings()) {
            lines.add(PdfLine.warning("[WARNING] " + warning));
        }
    }

    /**
     * Builds a minimal valid PDF byte array.
     *
     * @param lines report lines
     * @return PDF bytes
     */
    private byte[] buildPdf(List<PdfLine> lines) {
        List<List<PdfLine>> pages = paginate(lines);

        StringBuilder pdf = new StringBuilder();
        List<Integer> xrefOffsets = new ArrayList<>();

        pdf.append("%PDF-1.4\n");

        xrefOffsets.add(pdf.length());
        pdf.append("1 0 obj\n");
        pdf.append("<< /Type /Catalog /Pages 2 0 R >>\n");
        pdf.append("endobj\n");

        xrefOffsets.add(pdf.length());
        pdf.append("2 0 obj\n");
        pdf.append("<< /Type /Pages /Count ").append(pages.size()).append(" /Kids [");

        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            int pageObjectNumber = 3 + (pageIndex * 2);
            pdf.append(pageObjectNumber).append(" 0 R ");
        }

        pdf.append("] >>\n");
        pdf.append("endobj\n");

        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            int pageObjectNumber = 3 + (pageIndex * 2);
            int contentObjectNumber = pageObjectNumber + 1;

            xrefOffsets.add(pdf.length());
            pdf.append(pageObjectNumber).append(" 0 obj\n");
            pdf.append("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] ");
            pdf.append("/Resources << /Font << ");
            pdf.append("/F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> ");
            pdf.append("/F2 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >> ");
            pdf.append("/F3 << /Type /Font /Subtype /Type1 /BaseFont /Courier >> ");
            pdf.append(">> >> ");
            pdf.append("/Contents ").append(contentObjectNumber).append(" 0 R >>\n");
            pdf.append("endobj\n");

            String stream = buildPageContentStream(pages.get(pageIndex));

            xrefOffsets.add(pdf.length());
            pdf.append(contentObjectNumber).append(" 0 obj\n");
            pdf.append("<< /Length ").append(stream.getBytes(StandardCharsets.UTF_8).length).append(" >>\n");
            pdf.append("stream\n");
            pdf.append(stream);
            pdf.append("endstream\n");
            pdf.append("endobj\n");
        }

        int xrefStart = pdf.length();

        pdf.append("xref\n");
        pdf.append("0 ").append(xrefOffsets.size() + 1).append("\n");
        pdf.append("0000000000 65535 f \n");

        for (Integer offset : xrefOffsets) {
            pdf.append(String.format("%010d 00000 n \n", offset));
        }

        pdf.append("trailer\n");
        pdf.append("<< /Size ").append(xrefOffsets.size() + 1).append(" /Root 1 0 R >>\n");
        pdf.append("startxref\n");
        pdf.append(xrefStart).append("\n");
        pdf.append("%%EOF");

        return pdf.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Builds the content stream for one PDF page.
     *
     * @param lines page lines
     * @return PDF content stream
     */
    private String buildPageContentStream(List<PdfLine> lines) {
        StringBuilder stream = new StringBuilder();

        stream.append("BT\n");
        stream.append("50 790 Td\n");

        boolean firstLine = true;

        for (PdfLine line : lines) {
            if (!firstLine) {
                stream.append("0 -").append(line.lineHeight()).append(" Td\n");
            }

            stream.append(line.fontName())
                    .append(" ")
                    .append(line.fontSize())
                    .append(" Tf\n");

            stream.append("(")
                    .append(escapePdfText(line.text()))
                    .append(") Tj\n");

            firstLine = false;
        }

        stream.append("ET\n");
        return stream.toString();
    }

    /**
     * Splits report lines into pages.
     *
     * @param lines report lines
     * @return paginated lines
     */
    private List<List<PdfLine>> paginate(List<PdfLine> lines) {
        List<List<PdfLine>> pages = new ArrayList<>();
        List<PdfLine> currentPage = new ArrayList<>();

        for (PdfLine line : lines) {
            if (shouldStartNewPageBeforeLine(line, currentPage)) {
                pages.add(currentPage);
                currentPage = new ArrayList<>();
            }

            List<PdfLine> wrappedLines = wrapLine(line);

            for (PdfLine wrappedLine : wrappedLines) {
                if (currentPage.size() >= Constants.MAX_LINES_PER_PAGE) {
                    pages.add(currentPage);
                    currentPage = new ArrayList<>();
                }

                currentPage.add(wrappedLine);
            }
        }

        if (!currentPage.isEmpty()) {
            pages.add(currentPage);
        }

        if (pages.isEmpty()) {
            pages.add(List.of(PdfLine.detail("Empty validation report.")));
        }

        return pages;
    }

    /**
     * Determines whether a new page should start before rendering the given line.
     *
     * @param line next line to render
     * @param currentPage current page lines
     * @return true when a section should move to a new page
     */
    private boolean shouldStartNewPageBeforeLine(PdfLine line, List<PdfLine> currentPage) {
        if (line == null || currentPage == null || currentPage.isEmpty()) {
            return false;
        }

        boolean sectionHeading = line.fontName().equals("/F2")
                && line.fontSize() == 13;

        if (!sectionHeading) {
            return false;
        }

        int remainingLines = Constants.MAX_LINES_PER_PAGE - currentPage.size();

        return remainingLines < 12;
    }

    /**
     * Wraps one styled line to a fixed maximum character width.
     *
     * @param line source line
     * @return wrapped styled lines
     */
    private List<PdfLine> wrapLine(PdfLine line) {
        List<PdfLine> wrappedLines = new ArrayList<>();
        String safeText = line.text() == null ? "" : line.text();

        if (safeText.length() <= Constants.MAX_LINE_LENGTH) {
            wrappedLines.add(line);
            return wrappedLines;
        }

        int startIndex = 0;

        while (startIndex < safeText.length()) {
            int endIndex = Math.min(startIndex + Constants.MAX_LINE_LENGTH, safeText.length());
            String part = safeText.substring(startIndex, endIndex);

            wrappedLines.add(line.withText(startIndex == 0 ? part : "    " + part));
            startIndex = endIndex;
        }

        return wrappedLines;
    }

    /**
     * Escapes text for inclusion in a PDF text stream.
     *
     * @param value source text
     * @return escaped text
     */
    private String escapePdfText(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)");
    }

    /**
     * One styled PDF line.
     *
     * @param text line text
     * @param fontName PDF font resource name
     * @param fontSize font size
     * @param lineHeight line height
     */
    private record PdfLine(
            String text,
            String fontName,
            int fontSize,
            int lineHeight
    ) {

        /**
         * Creates a title line.
         *
         * @param text line text
         * @return title line
         */
        private static PdfLine title(String text) {
            return new PdfLine(text, "/F2", 16, 20);
        }

        /**
         * Creates a section heading line.
         *
         * @param text line text
         * @return section line
         */
        private static PdfLine section(String text) {
            return new PdfLine(text.toUpperCase(), "/F2", 13, 18);
        }

        /**
         * Creates a subsection heading line.
         *
         * @param text line text
         * @return subsection line
         */
        private static PdfLine subheading(String text) {
            return new PdfLine(text + ":", "/F2", 10, 14);
        }

        /**
         * Creates a metadata label line.
         *
         * @param text line text
         * @return label line
         */
        private static PdfLine label(String text) {
            return new PdfLine(text, "/F2", 10, 14);
        }

        /**
         * Creates a detail line.
         *
         * @param text line text
         * @return detail line
         */
        private static PdfLine detail(String text) {
            return new PdfLine("  " + text, "/F1", 10, 14);
        }

        /**
         * Creates a warning line.
         *
         * @param text line text
         * @return warning line
         */
        private static PdfLine warning(String text) {
            return new PdfLine("  " + text, "/F3", 9, 13);
        }

        /**
         * Creates a violation line.
         *
         * @param text line text
         * @return violation line
         */
        private static PdfLine violation(String text) {
            return new PdfLine("  " + text, "/F3", 9, 13);
        }

        /**
         * Creates a blank line.
         *
         * @return blank line
         */
        private static PdfLine blank() {
            return new PdfLine("", "/F1", 10, 10);
        }

        /**
         * Creates a copy with different text.
         *
         * @param newText replacement text
         * @return copied line
         */
        private PdfLine withText(String newText) {
            return new PdfLine(newText, fontName, fontSize, lineHeight);
        }
    }
}