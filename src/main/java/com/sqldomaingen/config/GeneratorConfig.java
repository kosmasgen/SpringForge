package com.sqldomaingen.config;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Generator configuration loaded from generator-config.yml.
 */
@Getter
@Setter
public class GeneratorConfig {

    /**
     * Tables treated as lookup tables.
     */
    private List<String> lookupTables = new ArrayList<>();

    /**
     * Checks whether the given table is configured as lookup table.
     *
     * @param tableName table name
     * @return true when table is configured as lookup table
     */
    public boolean isLookupTable(String tableName) {
        if (tableName == null || tableName.isBlank() || lookupTables == null || lookupTables.isEmpty()) {
            return false;
        }

        String normalizedInputTableName = normalizeTableName(tableName);

        return lookupTables.stream()
                .filter(configuredTableName -> configuredTableName != null && !configuredTableName.isBlank())
                .map(this::normalizeTableName)
                .anyMatch(configuredTableName -> configuredTableName.equals(normalizedInputTableName));
    }

    /**
     * Normalizes a table name by removing the schema prefix and lowercasing it.
     *
     * @param tableName raw table name
     * @return normalized table name
     */
    private String normalizeTableName(String tableName) {
        String trimmedTableName = tableName.trim();
        int dotIndex = trimmedTableName.lastIndexOf('.');

        if (dotIndex >= 0 && dotIndex < trimmedTableName.length() - 1) {
            return trimmedTableName.substring(dotIndex + 1).toLowerCase();
        }

        return trimmedTableName.toLowerCase();
    }
}