package com.sqldomaingen.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

/**
 * Writes the validation report as XML.
 */
public class ValidationReportXmlWriter {

    /**
     * Writes the validation report to an XML file.
     *
     * @param report validation report
     * @param outputPath target XML file path
     * @throws IOException if writing fails
     */
    public void writeXml(GenerationValidationReport report, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());

        String xmlContent = buildXml(report);

        Files.writeString(outputPath, xmlContent);
    }

    /**
     * Builds XML content from the report.
     *
     * @param report validation report
     * @return XML string
     */
    private String buildXml(GenerationValidationReport report) {
        StringBuilder xml = new StringBuilder();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<generationValidationReport>\n");

        xml.append("  <generatedAt>")
                .append(report.getGeneratedAt().format(formatter))
                .append("</generatedAt>\n");

        xml.append("  <author>")
                .append(escape(report.getAuthor()))
                .append("</author>\n");

        xml.append("  <inputFile>")
                .append(escape(report.getInputFile()))
                .append("</inputFile>\n");

        xml.append("  <outputDir>")
                .append(escape(report.getOutputDir()))
                .append("</outputDir>\n");

        xml.append("  <basePackage>")
                .append(escape(report.getBasePackage()))
                .append("</basePackage>\n");

        xml.append("  <totalIssues>")
                .append(report.getTotalViolationCount())
                .append("</totalIssues>\n");

        xml.append("  <totalWarnings>")
                .append(report.getTotalWarningCount())
                .append("</totalWarnings>\n");

        xml.append("  <sections>\n");

        for (GenerationValidationReport.Section section : report.getSections()) {

            xml.append("    <section>\n");

            xml.append("      <title>")
                    .append(escape(section.title()))
                    .append("</title>\n");

            xml.append("      <details>\n");

            for (String detail : section.details()) {
                xml.append("        <detail>")
                        .append(escape(detail))
                        .append("</detail>\n");
            }

            xml.append("      </details>\n");

            xml.append("      <violations>\n");

            for (String violation : section.violations()) {
                xml.append("        <violation>")
                        .append(escape(violation))
                        .append("</violation>\n");
            }

            xml.append("      </violations>\n");

            xml.append("      <warnings>\n");

            for (String warning : section.warnings()) {
                xml.append("        <warning>")
                        .append(escape(warning))
                        .append("</warning>\n");
            }

            xml.append("      </warnings>\n");

            xml.append("    </section>\n");
        }

        xml.append("  </sections>\n");
        xml.append("</generationValidationReport>\n");

        return xml.toString();
    }

    /**
     * Escapes XML special characters.
     *
     * @param value input string
     * @return escaped string
     */
    private String escape(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
