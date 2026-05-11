package com.sqldomaingen.validation;

import com.sqldomaingen.util.Constants;

import java.time.format.DateTimeFormatter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

        List<String> lines = buildReportLines(report);
        byte[] pdfBytes = buildPdf(lines);

        Files.write(outputPath, pdfBytes);
    }

    /**
     * Builds report lines for PDF rendering.
     *
     * @param report validation report
     * @return plain text lines
     */
    private List<String> buildReportLines(GenerationValidationReport report) {
        List<String> lines = new ArrayList<>();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        lines.add("GENERATION VALIDATION REPORT");
        lines.add("");

        lines.add("Generated at: " + report.getGeneratedAt().format(formatter));
        lines.add("Author: " + report.getAuthor());
        lines.add("Input file: " + report.getInputFile());
        lines.add("Output dir: " + report.getOutputDir());
        lines.add("Base package: " + report.getBasePackage());

        lines.add("Total issues: " + report.getTotalViolationCount());
        lines.add("Total warnings: " + report.getTotalWarningCount());

        lines.add("");

        for (GenerationValidationReport.Section section : report.getSections()) {

            lines.add("SECTION: " + section.title());

            if (!section.details().isEmpty()) {

                lines.add("Details:");

                if ("Schema Tables".equals(section.title())) {

                    int index = 1;

                    for (String detail : section.details()) {
                        lines.add(index + ". " + detail);
                        index++;
                    }

                } else {
                    lines.addAll(section.details());
                }
            }

            if (section.violations().isEmpty()) {
                lines.add("Violations: None");
            } else {

                lines.add("Violations:");

                for (String violation : section.violations()) {
                    lines.add("- " + violation);
                }
            }

            if (section.warnings().isEmpty()) {
                lines.add("Warnings: None");
            } else {

                lines.add("Warnings:");

                for (String warning : section.warnings()) {
                    lines.add("- " + warning);
                }
            }

            lines.add("");
        }

        return lines;
    }



    /**
     * Builds a minimal valid PDF byte array.
     *
     * @param lines report lines
     * @return PDF bytes
     */
    private byte[] buildPdf(List<String> lines) {
        List<List<String>> pages = paginate(lines);

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
            pdf.append("/Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >> ");
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
    private String buildPageContentStream(List<String> lines) {
        StringBuilder stream = new StringBuilder();

        stream.append("BT\n");
        stream.append("/F1 10 Tf\n");
        stream.append("50 790 Td\n");
        stream.append("14 TL\n");

        boolean firstLine = true;
        for (String line : lines) {
            String escapedLine = escapePdfText(line);

            if (!firstLine) {
                stream.append("T*\n");
            }

            stream.append("(").append(escapedLine).append(") Tj\n");
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
    private List<List<String>> paginate(List<String> lines) {
        List<List<String>> pages = new ArrayList<>();
        List<String> currentPage = new ArrayList<>();

        for (String line : lines) {
            List<String> wrappedLines = wrapLine(line);

            for (String wrappedLine : wrappedLines) {
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
            pages.add(List.of("Empty validation report."));
        }

        return pages;
    }

    /**
     * Wraps one line to a fixed maximum character width.
     *
     * @param line source line
     * @return wrapped lines
     */
    private List<String> wrapLine(String line) {
        List<String> wrappedLines = new ArrayList<>();
        String safeLine = line == null ? "" : line;

        if (safeLine.length() <= Constants.MAX_LINE_LENGTH) {
            wrappedLines.add(safeLine);
            return wrappedLines;
        }

        int startIndex = 0;
        while (startIndex < safeLine.length()) {
            int endIndex = Math.min(startIndex + Constants.MAX_LINE_LENGTH, safeLine.length());
            wrappedLines.add(safeLine.substring(startIndex, endIndex));
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
        return value
                .replace("\\", "\\\\")
                .replace("(", "\\(")
                .replace(")", "\\)");
    }
}