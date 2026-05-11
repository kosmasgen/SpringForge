package com.sqldomaingen.validation;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Aggregates validation sections and overall metadata for one generation run.
 */
@Getter
public class GenerationValidationReport {

    private final String inputFile;
    private final String outputDir;
    private final String basePackage;
    private final LocalDateTime generatedAt;
    private final List<Section> sections;
    private final String author;

    /**
     * Creates a report for one generation run.
     *
     * @param inputFile input SQL file path
     * @param outputDir generation output directory
     * @param basePackage generated base package
     * @param author Liquibase/generation author
     */
    public GenerationValidationReport(
            String inputFile,
            String outputDir,
            String basePackage,
            String author
    ) {
        this.inputFile = inputFile == null ? "" : inputFile;
        this.outputDir = outputDir == null ? "" : outputDir;
        this.basePackage = basePackage == null ? "" : basePackage;
        this.author = author == null ? "" : author;
        this.generatedAt = LocalDateTime.now();
        this.sections = new ArrayList<>();
    }

    /**
     * Adds one validation section to the report.
     *
     * @param title section title
     * @param details informational lines
     * @param violations validation violations
     */
    public void addSection(
            String title,
            List<String> details,
            List<String> violations
    ) {
        sections.add(new Section(
                Objects.requireNonNullElse(title, "Untitled Section"),
                details == null ? List.of() : List.copyOf(details),
                violations == null ? List.of() : List.copyOf(violations),
                List.of()
        ));
    }

    /**
     * Adds one report section including warnings.
     *
     * @param title section title
     * @param details informational lines
     * @param violations validation violations
     * @param warnings generation warnings
     */
    public void addSection(
            String title,
            List<String> details,
            List<String> violations,
            List<String> warnings
    ) {
        sections.add(new Section(
                Objects.requireNonNullElse(title, "Untitled Section"),
                details == null ? List.of() : List.copyOf(details),
                violations == null ? List.of() : List.copyOf(violations),
                warnings == null ? List.of() : List.copyOf(warnings)
        ));
    }

    /**
     * Returns all violations across all sections.
     *
     * @return flattened violations
     */
    public List<String> getAllViolations() {
        List<String> allViolations = new ArrayList<>();

        for (Section section : sections) {
            allViolations.addAll(section.violations());
        }

        return allViolations;
    }

    /**
     * Returns all warnings across all sections.
     *
     * @return flattened warnings
     */
    public List<String> getAllWarnings() {
        List<String> allWarnings = new ArrayList<>();

        for (Section section : sections) {
            allWarnings.addAll(section.warnings());
        }

        return allWarnings;
    }

    /**
     * Returns the total number of violations.
     *
     * @return total violation count
     */
    public int getTotalViolationCount() {
        return getAllViolations().size();
    }

    /**
     * Returns the total number of warnings.
     *
     * @return total warning count
     */
    public int getTotalWarningCount() {
        return getAllWarnings().size();
    }

    /**
     * One logical validation/report section.
     *
     * @param title section title
     * @param details informational lines
     * @param violations validation violations
     * @param warnings generation warnings
     */
    public record Section(
            String title,
            List<String> details,
            List<String> violations,
            List<String> warnings
    ) {
    }
}