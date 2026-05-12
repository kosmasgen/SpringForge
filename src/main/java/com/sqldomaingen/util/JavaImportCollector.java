package com.sqldomaingen.util;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Utility class for collecting and managing import statements.
 *
 * <p>This class ensures:
 * <ul>
 *     <li>No duplicate imports</li>
 *     <li>No java.lang imports</li>
 *     <li>Deterministic order</li>
 * </ul>
 */
public class JavaImportCollector {

    private final Set<String> imports = new LinkedHashSet<>();

    /**
     * Adds a raw import line.
     *
     * @param importLine full import line
     */
    public void addImport(String importLine) {
        if (importLine == null || importLine.isBlank()) {
            return;
        }

        if (importLine.startsWith("import java.lang")) {
            return;
        }

        imports.add(importLine.trim());
    }

    /**
     * Adds import based on Java type.
     *
     * @param javaType simple or full type name
     */
    public void addImportForType(String javaType) {
        if (javaType == null || javaType.isBlank()) {
            return;
        }

        String importLine = JavaTypeSupport.resolveImportLine(javaType);

        if (importLine != null) {
            addImport(importLine);
        }
    }

    /**
     * Adds imports for a possibly generic Java type.
     *
     * @param javaType raw or generic type (e.g. List<UUID>)
     */
    public void addImportForComplexType(String javaType) {
        if (javaType == null || javaType.isBlank()) {
            return;
        }

        String cleaned = javaType
                .replace("<", " ")
                .replace(">", " ")
                .replace(",", " ")
                .replace("[", " ")
                .replace("]", " ");

        String[] tokens = cleaned.split("\\s+");

        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }

            // Do not import DTOs from the same package
            if (token.endsWith("Dto")) {
                continue;
            }

            addImportForType(token);
        }
    }

    /**
     * Adds a static import line.
     *
     * @param importLine static import (e.g. import static org.assertj...;)
     */
    public void addStaticImport(String importLine) {
        if (importLine == null || importLine.isBlank()) {
            return;
        }

        if (!importLine.startsWith("import static")) {
            return;
        }

        imports.add(importLine.trim());
    }

    /**
     * Adds multiple imports at once.
     *
     * @param importLines import lines
     */
    public void addAll(Set<String> importLines) {
        if (importLines == null || importLines.isEmpty()) {
            return;
        }

        importLines.forEach(this::addImport);
    }

    /**
     * Returns all collected imports.
     *
     * @return ordered import set
     */
    public Set<String> getImports() {
        return imports;
    }

    /**
     * Builds the final import block as string.
     *
     * @return formatted import block
     */
    public String buildImportBlock() {
        StringBuilder builder = new StringBuilder();

        for (String importLine : imports) {
            builder.append(importLine).append("\n");
        }

        if (!imports.isEmpty()) {
            builder.append("\n");
        }

        return builder.toString();
    }

}