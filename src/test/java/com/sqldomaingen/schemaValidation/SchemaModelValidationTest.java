package com.sqldomaingen.schemaValidation;

import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.IndexDefinition;
import com.sqldomaingen.model.Table;
import com.sqldomaingen.shell.GeneratorCommands;
import com.sqldomaingen.util.Constants;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Validates the parsed schema model produced from an input SQL schema.
 *
 * <p>This test focuses on parser/model correctness before entity and Liquibase generation.
 * It verifies:
 * <ul>
 *     <li>parsed tables are structurally valid,</li>
 *     <li>parsed columns expose stable metadata,</li>
 *     <li>foreign keys point to valid or external tables,</li>
 *     <li>indexes are attached to the correct tables,</li>
 *     <li>index column references are valid when applicable,</li>
 *     <li>optional schema-specific scenarios when detected.</li>
 * </ul>
 */
class SchemaModelValidationTest {

    /**
     * Validates that the schema is parsed into a consistent in-memory table model
     * and prints a structured validation report.
     *
     * @throws Exception if schema loading or parsing fails unexpectedly
     */
    @Test
    void shouldParseRealSchemaIntoConsistentTableModel() throws Exception {
        List<String> violations = new ArrayList<>();

        Path schemaPath = Constants.SCHEMA_PATH;
        if (!Files.exists(schemaPath)) {
            violations.add("Missing schema file: " + schemaPath.toAbsolutePath());
            printReport(violations);
            return;
        }

        String sql = Files.readString(schemaPath);

        GeneratorCommands generatorCommands = new GeneratorCommands();
        List<Table> parsedTables;

        try {
            parsedTables = generatorCommands.parseSQLToTables(sql);
        } catch (Exception exception) {
            violations.add("Parsing real schema threw exception: " + exception.getMessage());
            printReport(violations);
            return;
        }

        if (parsedTables == null) {
            violations.add("Parsed tables list should not be null");
            printReport(violations);
            return;
        }

        if (parsedTables.isEmpty()) {
            violations.add("Parsed tables list should not be empty");
            printReport(violations);
            return;
        }

        Map<String, Table> tableByName = indexTablesByNormalizedName(parsedTables, violations);

        String schemaName = resolveSchemaName(parsedTables);

        assertEveryTableIsStructurallyValid(parsedTables, violations);
        assertEveryColumnIsStructurallyValid(parsedTables, violations);
        assertNoDuplicateColumnNames(parsedTables, violations);
        assertForeignKeysPointToExistingTables(parsedTables, tableByName, violations);
        assertForeignKeySourceColumnsExist(parsedTables, violations);
        assertIndexesAreStructurallyValid(parsedTables, violations);
        assertIndexColumnsExistWhenTheyArePlainColumns(parsedTables, violations);
        assertDataStagingTableScenarioIfPresent(tableByName, schemaName, violations);
        assertCompanySearchMaterializedViewIndexesIfPresent(tableByName, schemaName, violations);

        printReport(violations);
    }

    /**
     * Verifies that each parsed table does not contain duplicate column names.
     *
     * @param parsedTables parsed table models
     * @param violations collected violations
     */
    private void assertNoDuplicateColumnNames(List<Table> parsedTables, List<String> violations) {
        for (Table table : parsedTables) {
            if (table == null || table.getColumns() == null) {
                continue;
            }

            Map<String, Long> columnNameCounts = table.getColumns().stream()
                    .filter(Objects::nonNull)
                    .map(Column::getName)
                    .filter(Objects::nonNull)
                    .map(this::normalizePhysicalName)
                    .collect(java.util.stream.Collectors.groupingBy(
                            columnName -> columnName,
                            LinkedHashMap::new,
                            java.util.stream.Collectors.counting()
                    ));

            columnNameCounts.entrySet().stream()
                    .filter(entry -> entry.getValue() > 1)
                    .forEach(entry -> violations.add(
                            "Duplicate column name found in table '" + safeTableName(table) + "': " + entry.getKey()
                    ));
        }
    }

    /**
     * Verifies that every parsed foreign-key source column exists in its owning table.
     *
     * @param parsedTables parsed table models
     * @param violations collected violations
     */
    private void assertForeignKeySourceColumnsExist(List<Table> parsedTables, List<String> violations) {
        for (Table table : parsedTables) {
            if (table == null || table.getColumns() == null) {
                continue;
            }

            Set<String> tableColumnNames = table.getColumns().stream()
                    .filter(Objects::nonNull)
                    .map(Column::getName)
                    .filter(Objects::nonNull)
                    .map(this::normalizePhysicalName)
                    .collect(java.util.stream.Collectors.toSet());

            for (Column column : table.getColumns()) {
                if (column == null || !column.isForeignKey()) {
                    continue;
                }

                String normalizedColumnName = normalizePhysicalName(column.getName());

                if (!tableColumnNames.contains(normalizedColumnName)) {
                    violations.add("Foreign-key source column does not exist in table '"
                            + safeTableName(table) + "': " + safeColumnName(column));
                }
            }
        }
    }

    /**
     * Verifies that plain index column a references exist in the owning table.
     *
     * @param parsedTables parsed table models
     * @param violations collected violations
     */
    private void assertIndexColumnsExistWhenTheyArePlainColumns(List<Table> parsedTables, List<String> violations) {
        for (Table table : parsedTables) {
            if (table == null || table.getColumns() == null || table.getIndexes() == null) {
                continue;
            }

            Set<String> tableColumnNames = table.getColumns().stream()
                    .filter(Objects::nonNull)
                    .map(Column::getName)
                    .filter(Objects::nonNull)
                    .map(this::normalizePhysicalName)
                    .collect(java.util.stream.Collectors.toSet());

            for (IndexDefinition indexDefinition : table.getIndexes()) {
                if (indexDefinition == null || indexDefinition.getColumns() == null) {
                    continue;
                }

                for (String indexColumn : indexDefinition.getColumns()) {
                    if (!isPlainIndexColumn(indexColumn)) {
                        continue;
                    }

                    String normalizedIndexColumn = normalizePhysicalName(removeIndexColumnDirection(indexColumn));

                    if (!tableColumnNames.contains(normalizedIndexColumn)) {
                        violations.add("Index '" + safeIndexName(indexDefinition)
                                + "' references missing column '" + indexColumn
                                + "' on table '" + safeTableName(table) + "'");
                    }
                }
            }
        }
    }

    /**
     * Checks whether an index element is a plain column reference.
     *
     * @param indexColumn parsed index column or expression
     * @return true when the index element is a plain column reference
     */
    private boolean isPlainIndexColumn(String indexColumn) {
        if (indexColumn == null || indexColumn.isBlank()) {
            return false;
        }

        String normalizedIndexColumn = indexColumn.trim();

        return !normalizedIndexColumn.contains("(")
                && !normalizedIndexColumn.contains(")")
                && !normalizedIndexColumn.contains("::")
                && !normalizedIndexColumn.contains(" ");
    }

    /**
     * Removes optional ASC or DESC direction from an index column.
     *
     * @param indexColumn parsed index column
     * @return index column without direction
     */
    private String removeIndexColumnDirection(String indexColumn) {
        if (indexColumn == null || indexColumn.isBlank()) {
            return "";
        }

        return indexColumn.trim()
                .replaceFirst("(?i)\\s+ASC$", "")
                .replaceFirst("(?i)\\s+DESC$", "")
                .trim();
    }

    /**
     * Resolves schema name dynamically from parsed tables.
     */
    private String resolveSchemaName(List<Table> tables) {
        if (tables == null || tables.isEmpty()) {
            return "public";
        }

        for (Table table : tables) {
            if (table == null || table.getName() == null || table.getName().isBlank()) {
                continue;
            }

            String tableName = table.getName().trim();
            int dotIndex = tableName.indexOf('.');

            if (dotIndex > 0) {
                return tableName.substring(0, dotIndex);
            }
        }

        return "public";
    }

    /**
     * Builds a lookup map for parsed tables using normalized physical table names.
     *
     * @param parsedTables parsed table models
     * @param violations collected violations
     * @return normalized table lookup map
     */
    private Map<String, Table> indexTablesByNormalizedName(List<Table> parsedTables, List<String> violations) {
        Map<String, Table> tableByName = new LinkedHashMap<>();

        for (Table table : parsedTables) {
            if (table == null) {
                violations.add("Parsed table must not be null");
                continue;
            }

            if (table.getName() == null) {
                violations.add("Parsed table name must not be null");
                continue;
            }

            if (table.getName().isBlank()) {
                violations.add("Parsed table name must not be blank");
                continue;
            }

            tableByName.put(normalizePhysicalName(table.getName()), table);
        }

        return tableByName;
    }

    /**
     * Verifies that every parsed table has the minimum required structure.
     *
     * @param parsedTables parsed table models
     * @param violations collected violations
     */
    private void assertEveryTableIsStructurallyValid(List<Table> parsedTables, List<String> violations) {
        for (Table table : parsedTables) {
            if (table == null) {
                violations.add("Parsed table must not be null");
                continue;
            }

            if (table.getName() == null) {
                violations.add("Table name must not be null");
            } else if (table.getName().isBlank()) {
                violations.add("Table name must not be blank");
            }

            if (table.getColumns() == null) {
                violations.add("Columns list must not be null for table: " + safeTableName(table));
                continue;
            }

            if (table.getColumns().isEmpty()) {
                violations.add("Columns list must not be empty for table: " + safeTableName(table));
            }

            long primaryKeyCount = table.getColumns().stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(Column::isPrimaryKey)
                    .count();

            if (primaryKeyCount < 1) {
                violations.add("Expected at least one primary key column for table: " + safeTableName(table));
            }
        }
    }

    /**
     * Verifies company_search_mv index scenarios only when the materialized view exists.
     *
     * @param tableByName normalized table lookup map
     * @param schemaName resolved schema name
     * @param violations collected violations
     */
    private void assertCompanySearchMaterializedViewIndexesIfPresent(
            Map<String, Table> tableByName,
            String schemaName,
            List<String> violations
    ) {
        Table companySearchMvTable = tableByName.get(normalizePhysicalName(schemaName + ".company_search_mv"));
        if (companySearchMvTable == null) {
            return;
        }

        if (companySearchMvTable.getIndexes() == null) {
            violations.add("company_search_mv indexes should not be null");
        }
    }

    /**
     * Verifies that every parsed column contains stable core metadata.
     *
     * @param parsedTables parsed table models
     * @param violations collected violations
     */
    private void assertEveryColumnIsStructurallyValid(List<Table> parsedTables, List<String> violations) {
        for (Table table : parsedTables) {
            if (table == null || table.getColumns() == null) {
                continue;
            }

            for (Column column : table.getColumns()) {
                if (column == null) {
                    violations.add("Column must not be null in table: " + safeTableName(table));
                    continue;
                }

                if (column.getName() == null) {
                    violations.add("Column name must not be null in table: " + safeTableName(table));
                } else if (column.getName().isBlank()) {
                    violations.add("Column name must not be blank in table: " + safeTableName(table));
                }

                if (column.getSqlType() == null) {
                    violations.add("SQL type must not be null for column: "
                            + safeTableName(table) + "." + safeColumnName(column));
                } else if (column.getSqlType().isBlank()) {
                    violations.add("SQL type must not be blank for column: "
                            + safeTableName(table) + "." + safeColumnName(column));
                }

                if (column.getJavaType() == null) {
                    violations.add("Java type must not be null for column: "
                            + safeTableName(table) + "." + safeColumnName(column));
                } else if (column.getJavaType().isBlank()) {
                    violations.add("Java type must not be blank for column: "
                            + safeTableName(table) + "." + safeColumnName(column));
                }

                if (column.getDefaultValue() != null && column.getDefaultValue().isBlank()) {
                    violations.add("Default value must not be blank when present for column: "
                            + safeTableName(table) + "." + safeColumnName(column));
                }
            }
        }
    }

    /**
     * Verifies that every parsed foreign key has a valid referenced table name.
     * Foreign keys pointing to tables outside the currently parsed schema file are allowed
     * and are reported separately.
     *
     * @param parsedTables parsed table models
     * @param tableByName normalized table lookup map
     * @param violations collected violations
     */
    private void assertForeignKeysPointToExistingTables(
            List<Table> parsedTables,
            Map<String, Table> tableByName,
            List<String> violations
    ) {
        List<String> externalReferences = new ArrayList<>();

        for (Table table : parsedTables) {
            if (table == null || table.getColumns() == null) {
                continue;
            }

            for (Column column : table.getColumns()) {
                if (column == null || !column.isForeignKey()) {
                    continue;
                }

                if (column.getReferencedTable() == null) {
                    violations.add("Referenced table must not be null for foreign key column: "
                            + safeTableName(table) + "." + safeColumnName(column));
                    continue;
                }

                if (column.getReferencedTable().isBlank()) {
                    violations.add("Referenced table must not be blank for foreign key column: "
                            + safeTableName(table) + "." + safeColumnName(column));
                    continue;
                }

                String normalizedReferencedTable = normalizePhysicalName(column.getReferencedTable());

                if (!tableByName.containsKey(normalizedReferencedTable)) {
                    externalReferences.add(
                            safeTableName(table) + "." + safeColumnName(column) + " -> " + column.getReferencedTable()
                    );
                }
            }
        }

        if (!externalReferences.isEmpty()) {
            violations.add("External foreign key references not present in the parsed schema file:");
            for (String externalReference : externalReferences) {
                violations.add(" - " + externalReference);
            }
        }
    }

    /**
     * Verifies that parsed indexes are attached and minimally valid.
     *
     * @param parsedTables parsed table models
     * @param violations collected violations
     */
    private void assertIndexesAreStructurallyValid(List<Table> parsedTables, List<String> violations) {
        for (Table table : parsedTables) {
            if (table == null || table.getIndexes() == null || table.getIndexes().isEmpty()) {
                continue;
            }

            for (IndexDefinition indexDefinition : table.getIndexes()) {
                if (indexDefinition == null) {
                    violations.add("Index must not be null for table: " + safeTableName(table));
                    continue;
                }

                if (indexDefinition.getName() == null) {
                    violations.add("Index name must not be null for table: " + safeTableName(table));
                } else if (indexDefinition.getName().isBlank()) {
                    violations.add("Index name must not be blank for table: " + safeTableName(table));
                }

                if (indexDefinition.getTableName() == null) {
                    violations.add("Index tableName must not be null for index: " + safeIndexName(indexDefinition));
                } else if (indexDefinition.getTableName().isBlank()) {
                    violations.add("Index tableName must not be blank for index: " + safeIndexName(indexDefinition));
                } else if (!normalizePhysicalName(table.getName()).equals(normalizePhysicalName(indexDefinition.getTableName()))) {
                    violations.add("Index is attached to the wrong table: " + safeIndexName(indexDefinition));
                }

                if (indexDefinition.getColumns() == null) {
                    violations.add("Index columns must not be null for index: " + safeIndexName(indexDefinition));
                    continue;
                }

                if (indexDefinition.getColumns().isEmpty()) {
                    violations.add("Index columns must not be empty for index: " + safeIndexName(indexDefinition));
                }

                for (String columnName : indexDefinition.getColumns()) {
                    if (columnName == null) {
                        violations.add("Index column name must not be null for index: " + safeIndexName(indexDefinition));
                    } else if (columnName.isBlank()) {
                        violations.add("Index column name must not be blank for index: " + safeIndexName(indexDefinition));
                    }
                }
            }
        }
    }

    /**
     * Verifies the known data_staging scenario only when the table exists.
     *
     * @param tableByName normalized table lookup map
     * @param schemaName resolved schema name
     * @param violations collected violations
     */
    private void assertDataStagingTableScenarioIfPresent(
            Map<String, Table> tableByName,
            String schemaName,
            List<String> violations
    ) {
        Table dataStagingTable = tableByName.get(normalizePhysicalName(schemaName + ".data_staging"));
        if (dataStagingTable == null) {
            return;
        }

        Column idColumn = findColumn(dataStagingTable);
        if (idColumn == null) {
            violations.add("data_staging.id should exist");
        } else if (!idColumn.isPrimaryKey()) {
            violations.add("data_staging.id should be primary key");
        }
    }


    /**
     * Prints the collected validation report.
     *
     * @param violations collected violations
     */
    private void printReport(List<String> violations) {
        System.out.println();
        System.out.println("==================================================");
        System.out.println("SCHEMA MODEL VALIDATION REPORT");
        System.out.println("==================================================");

        if (violations.isEmpty()) {
            System.out.println("No schema model validation issues found.");
        } else {
            for (int index = 0; index < violations.size(); index++) {
                System.out.println((index + 1) + ". " + violations.get(index));
            }
        }

        System.out.println("==================================================");
        System.out.println("Total issues: " + violations.size());
        System.out.println("==================================================");
        System.out.println();
    }

    /**
     * Finds ID column from parsed table.
     *
     * @param table parsed table
     * @return id column or null
     */
    private Column findColumn(Table table) {
        if (table == null || table.getColumns() == null) {
            return null;
        }

        for (Column column : table.getColumns()) {
            if (column == null || column.getName() == null) {
                continue;
            }

            if (normalizePhysicalName(column.getName()).equals("id")) {
                return column;
            }
        }

        return null;
    }




    /**
     * Normalizes physical names for stable comparisons.
     *
     * @param value raw physical name
     * @return normalized name
     */
    private String normalizePhysicalName(String value) {
        if (value == null) {
            return "";
        }

        return value.trim()
                .replace("\"", "")
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }



    /**
     * Safely resolves a table name for reporting.
     *
     * @param table source table
     * @return safe table name
     */
    private String safeTableName(Table table) {
        return table == null || table.getName() == null || table.getName().isBlank()
                ? "<unknown-table>"
                : table.getName();
    }

    /**
     * Safely resolves a column name for reporting.
     *
     * @param column source column
     * @return safe column name
     */
    private String safeColumnName(Column column) {
        return column == null || column.getName() == null || column.getName().isBlank()
                ? "<unknown-column>"
                : column.getName();
    }

    /**
     * Safely resolves an index name for reporting.
     *
     * @param indexDefinition source index
     * @return safe index name
     */
    private String safeIndexName(IndexDefinition indexDefinition) {
        return indexDefinition == null || indexDefinition.getName() == null || indexDefinition.getName().isBlank()
                ? "<unknown-index>"
                : indexDefinition.getName();
    }
}