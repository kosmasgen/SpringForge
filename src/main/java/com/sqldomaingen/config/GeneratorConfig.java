package com.sqldomaingen.config;

import com.sqldomaingen.util.GeneratorSupport;
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

        String normalizedInputTableName = GeneratorSupport.normalizeTableName(tableName);

        return lookupTables.stream()
                .filter(configuredTableName -> configuredTableName != null
                        && !configuredTableName.isBlank())
                .map(GeneratorSupport::normalizeTableName)
                .anyMatch(configuredTableName -> configuredTableName.equals(normalizedInputTableName));
    }
}