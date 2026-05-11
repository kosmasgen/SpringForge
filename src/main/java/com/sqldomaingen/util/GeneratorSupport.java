package com.sqldomaingen.util;

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared utility methods used by code generators.
 */
@Log4j2
public final class GeneratorSupport {

    /**
     * Prevents instantiation of this utility class.
     */
    private GeneratorSupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Removes any schema or qualifier prefix from a table name
     * and normalizes it to lowercase.
     *
     * @param raw raw table name
     * @return normalized table name without schema prefix
     */
    public static String normalizeTableName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String normalizedValue = raw.trim();
        int dotIndex = normalizedValue.lastIndexOf('.');

        if (dotIndex >= 0 && dotIndex < normalizedValue.length() - 1) {
            normalizedValue = normalizedValue.substring(dotIndex + 1);
        }

        return normalizedValue.toLowerCase();
    }

    /**
     * Creates the target directory when it does not already exist.
     *
     * @param path directory path
     * @return created or existing directory path
     */
    public static Path ensureDirectory(Path path) {
        try {
            Files.createDirectories(path);
            return path;
        } catch (IOException exception) {
            log.error("Failed to create directory: {}", path.toAbsolutePath(), exception);
            throw new IllegalStateException("Failed to create directory: " + path, exception);
        }
    }

    /**
     * Writes generated content to a file.
     *
     * @param filePath target file path
     * @param content generated file content
     * @param overwrite overwrite existing file when true
     */
    public static void writeFile(Path filePath, String content, boolean overwrite) {
        try {
            Path parentPath = filePath.getParent();
            if (parentPath != null) {
                Files.createDirectories(parentPath);
            }

            if (!overwrite && Files.exists(filePath)) {
                log.info("Skipping existing file: {}", filePath.toAbsolutePath());
                return;
            }

            Files.writeString(filePath, content, StandardCharsets.UTF_8);
            log.debug("Generated file: {}", filePath.toAbsolutePath());
        } catch (IOException exception) {
            log.error("Failed to write file: {}", filePath.toAbsolutePath(), exception);
            throw new IllegalStateException("Failed to write file: " + filePath, exception);
        }
    }

    /**
     * Writes generated content to a file and always overwrites an existing file.
     *
     * @param filePath target file path
     * @param content generated file content
     */
    public static void writeFile(Path filePath, String content) {
        writeFile(filePath, content, true);
    }

    /**
     * Returns a trimmed non-null string.
     *
     * @param value source value
     * @return trimmed value or empty string when null
     */
    public static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }


    /**
     * Removes wrapping SQL identifier quotes from a value.
     *
     * @param value raw identifier value
     * @return identifier without wrapping quotes
     */
    public static String unquoteIdentifier(String value) {
        String normalizedValue = trimToEmpty(value);

        while (normalizedValue.length() >= 2
                && normalizedValue.startsWith("\"")
                && normalizedValue.endsWith("\"")) {
            normalizedValue = normalizedValue.substring(1, normalizedValue.length() - 1).trim();
        }

        return normalizedValue;
    }
}