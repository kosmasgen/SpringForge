package com.sqldomaingen.validation;

import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.Table;
import com.sqldomaingen.model.UniqueConstraint;
import com.sqldomaingen.util.Constants;
import com.sqldomaingen.util.GeneratorSupport;
import lombok.RequiredArgsConstructor;


import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates generated Liquibase changelogs against the parsed schema model.
 */
@RequiredArgsConstructor
public class LiquibaseSchemaParityValidation {

    private final Path schemaPath;
    private final Path liquibaseRoot;
    private final List<Table> parsedTables;

    /**
     * Runs Liquibase/schema parity validation.
     *
     * @return collected violations
     */
    public List<String> validate() {
        List<String> violations = new ArrayList<>();

        validateSchemaFileExists(violations);
        validateLiquibaseRootExists(violations);
        validateMainXmlExists(violations);
        validateAuditXmlExists(violations);
        validateTableChangelogFilesExist(violations);

        if (!violations.isEmpty()) {
            return violations;
        }

        try {
            Path auditXmlPath = liquibaseRoot.resolve("audit.xml");
            Path mainXmlPath = liquibaseRoot.resolve("main.xml");

            String auditXml = Files.readString(auditXmlPath);
            String mainXml = Files.readString(mainXmlPath);

            assertContains(auditXml, "CREATE SCHEMA IF NOT EXISTS audit;", violations, "audit.xml");
            assertContains(auditXml, "<createTable tableName=\"REVINFO\" schemaName=\"audit\">", violations, "audit.xml");
            assertContains(auditXml, "<createSequence sequenceName=\"revinfo_seq\"", violations, "audit.xml");

            assertMainIncludesAllTables(mainXml, parsedTables, violations);

            for (Table table : parsedTables) {
                assertGeneratedTableChangelogMatchesParsedTable(liquibaseRoot, table, violations);

                String fileName = toIncludeFileName(table.getName());
                Path tableXmlPath = liquibaseRoot.resolve(fileName);

                if (Files.exists(tableXmlPath)) {
                    assertIndexes(Files.readString(tableXmlPath), table, violations);
                }
            }

            validateCheckSqlKeywordSpacing(violations);
        } catch (Exception exception) {
            violations.add("Liquibase schema parity validation failed: " + exception.getMessage());
        }

        return violations;
    }


    /**
     * Validates that generated CHECK SQL does not contain broken merged keywords.
     *
     * @param violations collected violations
     */
    private void validateCheckSqlKeywordSpacing(List<String> violations) {
        try (var paths = Files.walk(liquibaseRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".xml"))
                    .forEach(path -> assertCheckSqlDoesNotContainBrokenKeywordSpacing(path, violations));
        } catch (Exception exception) {
            violations.add("Could not inspect Liquibase CHECK SQL keyword spacing: " + exception.getMessage());
        }
    }

    private void validateSchemaFileExists(List<String> violations) {
        if (schemaPath == null || !Files.exists(schemaPath)) {
            violations.add("Missing schema file: " + schemaPath);
        }
    }

    private void validateLiquibaseRootExists(List<String> violations) {
        if (liquibaseRoot == null || !Files.exists(liquibaseRoot)) {
            violations.add("Missing Liquibase changelog directory: " + liquibaseRoot);
        }
    }

    private void validateMainXmlExists(List<String> violations) {
        if (liquibaseRoot == null) {
            return;
        }

        Path mainXmlPath = liquibaseRoot.resolve("main.xml");

        if (!Files.exists(mainXmlPath)) {
            violations.add("Generated main.xml was not found: " + mainXmlPath.toAbsolutePath());
        }
    }

    private void validateAuditXmlExists(List<String> violations) {
        if (liquibaseRoot == null) {
            return;
        }

        Path auditXmlPath = liquibaseRoot.resolve("audit.xml");

        if (!Files.exists(auditXmlPath)) {
            violations.add("Generated audit.xml was not found: " + auditXmlPath.toAbsolutePath());
        }
    }

    private void validateTableChangelogFilesExist(List<String> violations) {
        if (liquibaseRoot == null || parsedTables == null || parsedTables.isEmpty()) {
            return;
        }

        for (Table table : parsedTables) {
            if (table == null || table.getName() == null || table.getName().isBlank()) {
                continue;
            }

            String changelogFileName = toTableChangelogFileName(table.getName());
            Path changelogPath = liquibaseRoot.resolve(changelogFileName);

            if (!Files.exists(changelogPath)) {
                violations.add("Generated changelog file was not found: " + changelogFileName);
            }
        }
    }

    private String toTableChangelogFileName(String tableName) {
        String normalizedTableName = GeneratorSupport.normalizeTableName(tableName);
        return toCamelCase(normalizedTableName) + ".xml";
    }

    private String toCamelCase(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "";
        }

        String[] parts = rawName.trim().toLowerCase().split("_+");
        StringBuilder builder = new StringBuilder(parts[0]);

        for (int index = 1; index < parts.length; index++) {
            String part = parts[index];

            if (part.isBlank()) {
                continue;
            }

            builder.append(Character.toUpperCase(part.charAt(0)));

            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }

        return builder.toString();
    }


    /**
     * Verifies that generated CHECK SQL does not contain merged SQL keywords.
     *
     * @param path generated Liquibase XML path
     * @param violations collected violations
     */
    private void assertCheckSqlDoesNotContainBrokenKeywordSpacing(Path path, List<String> violations) {
        try {
            String xml = Files.readString(path);
            String normalizedXml = normalizeXmlWhitespace(xml);

            Pattern checkPattern = Pattern.compile("(?i)CHECK\\s*\\((.*?)\\)");
            Matcher checkMatcher = checkPattern.matcher(normalizedXml);

            while (checkMatcher.find()) {
                String checkExpression = checkMatcher.group(1);

                List<Pattern> brokenPatterns = List.of(
                        Pattern.compile("\\bNULLOR[A-Za-z_][A-Za-z0-9_]*\\b"),
                        Pattern.compile("\\bOR[A-Z_][A-Za-z0-9_]*\\b"),
                        Pattern.compile("\\bAND[A-Z_][A-Za-z0-9_]*\\b"),
                        Pattern.compile("(?i)\\bCHECK\\s*\\(\\s*OR\\b"),
                        Pattern.compile("(?i)\\bCHECK\\s*\\(\\s*AND\\b")
                );

                for (Pattern brokenPattern : brokenPatterns) {
                    Matcher matcher = brokenPattern.matcher(checkExpression);

                    if (matcher.find()) {
                        violations.add("[" + path.getFileName() + "] Broken CHECK SQL keyword spacing found: " + matcher.group());
                    }
                }
            }
        } catch (Exception exception) {
            violations.add("Could not inspect generated Liquibase file: " + path + " -> " + exception.getMessage());
        }
    }





    /**
     * Verifies that main.xml includes audit.xml and all expected table changelog files.
     *
     * @param mainXml generated main.xml content
     * @param parsedTables parsed tables
     * @param violations collected violations
     */
    private void assertMainIncludesAllTables(String mainXml, List<Table> parsedTables, List<String> violations) {
        assertContains(mainXml, "<include file=\"audit.xml\" relativeToChangelogFile=\"true\" />", violations, "main.xml");

        for (Table table : parsedTables) {
            String expectedFileName = toIncludeFileName(table.getName());
            assertContains(
                    mainXml,
                    "<include file=\"" + expectedFileName + "\" relativeToChangelogFile=\"true\" />",
                    violations,
                    "main.xml"
            );
        }
    }

    /**
     * Verifies that one generated table changelog preserves the parsed SQL semantics.
     *
     * @param versionDir generated Liquibase version directory
     * @param table parsed table
     * @param violations collected violations
     * @throws Exception if reading fails
     */
    private void assertGeneratedTableChangelogMatchesParsedTable(
            Path versionDir,
            Table table,
            List<String> violations
    ) throws Exception {
        String fileName = toIncludeFileName(table.getName());
        Path tableXmlPath = versionDir.resolve(fileName);

        if (!Files.exists(tableXmlPath)) {
            violations.add("Generated changelog file was not found: " + fileName);
            return;
        }

        String xml = Files.readString(tableXmlPath);

        String normalizedTableName = stripSchema(table.getName());
        String schemaName = extractSchema(table.getName());
        String auditTableName = normalizedTableName + "_aud";
        String expectedMainCreateTableTag = buildExpectedMainCreateTableTag(normalizedTableName, schemaName);

        assertContains(xml, expectedMainCreateTableTag, violations, normalizedTableName);
        assertContains(xml, "<createTable tableName=\"" + auditTableName + "\" schemaName=\"audit\">", violations, normalizedTableName);
        assertContains(xml, "<addPrimaryKey tableName=\"" + auditTableName + "\"", violations, normalizedTableName);
        assertContains(xml, "constraintName=\"pk_" + auditTableName + "\"", violations, normalizedTableName);
        assertContains(xml, "<addForeignKeyConstraint baseTableName=\"" + auditTableName + "\"", violations, normalizedTableName);
        assertContains(xml, "constraintName=\"fk_" + normalizedTableName + "_aud_revinfo\"", violations, normalizedTableName);

        String mainTableBlock = safeExtractBlock(
                xml,
                expectedMainCreateTableTag,
                "</createTable>",
                violations,
                normalizedTableName,
                "main createTable block"
        );

        String auditTableBlock = safeExtractBlock(
                xml,
                "<createTable tableName=\"" + auditTableName + "\" schemaName=\"audit\">",
                "</createTable>",
                violations,
                normalizedTableName,
                "audit createTable block"
        );

        if (mainTableBlock == null || auditTableBlock == null) {
            return;
        }

        for (Column column : table.getColumns()) {
            assertMainColumnMapping(xml, mainTableBlock, column, normalizedTableName, schemaName, violations);
            assertAuditColumnMapping(auditTableBlock, column, normalizedTableName, violations);
        }

        assertMainTableColumnCount(xml, mainTableBlock, table, normalizedTableName, schemaName, violations);
        assertAuditTableColumnCount(auditTableBlock, table, violations);
        assertMainTableColumnOrder(xml, mainTableBlock, table, normalizedTableName, schemaName, violations);
        assertAuditTableColumnOrder(auditTableBlock, table, violations);

        assertForeignKeys(xml, table, violations);
        assertForeignKeyCount(xml, table, violations);

        assertCompositeUniqueConstraints(xml, table, violations);
        assertCompositeUniqueConstraintCount(xml, table, violations);

        assertCheckConstraints(xml, table, violations);
        assertCheckConstraintCount(xml, table, violations);
    }



    /**
     * Records a violation when the given fragment does not exist in the generated XML.
     *
     * @param actualXml actual generated XML
     * @param expectedFragment expected fragment
     * @param violations collected violations
     * @param context validation context
     */
    private void assertContains(
            String actualXml,
            String expectedFragment,
            List<String> violations,
            String context
    ) {
        String normalizedActualXml = normalizeXmlWhitespace(actualXml);
        String normalizedExpectedFragment = normalizeXmlWhitespace(expectedFragment);

        if (!normalizedActualXml.contains(normalizedExpectedFragment)) {
            violations.add("[" + context + "] Expected XML fragment not found: " + expectedFragment);
        }
    }

    /**
     * Safely extracts a table or section block between two markers.
     *
     * @param text source text
     * @param startMarker block start marker
     * @param endMarker block end marker
     * @param violations collected violations
     * @param context validation context
     * @param blockName human-readable block name
     * @return extracted block or null when missing
     */
    private String safeExtractBlock(
            String text,
            String startMarker,
            String endMarker,
            List<String> violations,
            String context,
            String blockName
    ) {
        int startIndex = text.indexOf(startMarker);
        if (startIndex < 0) {
            violations.add("[" + context + "] Start marker not found for " + blockName + ": " + startMarker);
            return null;
        }

        int endIndex = text.indexOf(endMarker, startIndex);
        if (endIndex < 0) {
            violations.add("[" + context + "] End marker not found for " + blockName + ": " + endMarker);
            return null;
        }

        endIndex += endMarker.length();
        return text.substring(startIndex, endIndex);
    }

    /**
     * Builds the expected createTable tag for the main table.
     *
     * @param tableName schema-free table name
     * @param schemaName schema name
     * @return expected createTable tag
     */
    private String buildExpectedMainCreateTableTag(String tableName, String schemaName) {
        if (schemaName == null || schemaName.isBlank()) {
            return "<createTable tableName=\"" + tableName + "\">";
        }

        return "<createTable tableName=\"" + tableName + "\" schemaName=\"" + schemaName + "\">";
    }

    /**
     * Verifies one main-table column mapping in the generated XML block.
     *
     * @param xml full generated table changelog XML
     * @param mainTableBlock generated main-table block
     * @param column parsed column
     * @param tableName normalized table name
     * @param schemaName schema name or null
     * @param violations collected violations
     */
    private void assertMainColumnMapping(
            String xml,
            String mainTableBlock,
            Column column,
            String tableName,
            String schemaName,
            List<String> violations
    ) {
        String columnName = normalizeIdentifier(column.getName());

        String columnBlock = findColumnBlock(mainTableBlock, columnName);
        if (columnBlock == null) {
            columnBlock = extractDeferredMainTableColumnBlock(xml, tableName, schemaName, columnName);
        }

        if (columnBlock == null) {
            violations.add("[" + tableName + "." + columnName + "] Column block not found in main table block.");
            return;
        }

        assertColumnType(columnBlock, column, tableName, violations);
        assertPrimaryKeyMapping(columnBlock, column, tableName, violations);
        assertNullableMapping(columnBlock, column, tableName, violations);
        assertUniqueMapping(columnBlock, column, tableName, violations);
        assertDefaultValueMapping(columnBlock, column, tableName, violations);
        assertAutoIncrementMapping(columnBlock, column, tableName, violations);
    }

    /**
     * Verifies one audit-table column mapping in the generated XML block.
     *
     * @param auditTableBlock generated audit-table block
     * @param column parsed column
     * @param tableName normalized table name
     * @param violations collected violations
     */
    private void assertAuditColumnMapping(
            String auditTableBlock,
            Column column,
            String tableName,
            List<String> violations
    ) {
        String columnName = normalizeIdentifier(column.getName());
        String columnBlock = extractColumnBlock(auditTableBlock, columnName, tableName, violations, "audit");

        if (columnBlock == null) {
            return;
        }

        assertColumnType(columnBlock, column, tableName, violations);
    }

    /**
     * Verifies the generated SQL type semantics for one column block.
     *
     * @param columnBlock generated column block
     * @param column parsed column
     * @param tableName normalized table name
     * @param violations collected violations
     */
    private void assertColumnType(
            String columnBlock,
            Column column,
            String tableName,
            List<String> violations
    ) {
        String columnName = normalizeIdentifier(column.getName());

        String actualType;
        if (isDeferredSqlColumnBlock(columnBlock)) {
            actualType = extractTypeFromDeferredSqlColumnBlock(columnBlock, tableName, columnName, violations);
        } else {
            actualType = extractAttributeValueFromXmlColumn(columnBlock, "type", tableName, columnName, violations);
        }

        if (actualType == null) {
            return;
        }

        String expectedType = normalizeType(column.getSqlType());

        TypeDescriptor expectedDescriptor = parseTypeDescriptor(expectedType, tableName, columnName, violations);
        TypeDescriptor actualDescriptor = parseTypeDescriptor(actualType, tableName, columnName, violations);

        if (expectedDescriptor == null || actualDescriptor == null) {
            return;
        }

        addViolationIfDifferent(
                expectedDescriptor.baseType(),
                actualDescriptor.baseType(),
                violations,
                "[" + tableName + "." + columnName + "] Unexpected base type. Expected: "
                        + expectedDescriptor.baseType() + ", actual: " + actualDescriptor.baseType()
        );

        addViolationIfDifferent(
                expectedDescriptor.lengthOrPrecision(),
                actualDescriptor.lengthOrPrecision(),
                violations,
                "[" + tableName + "." + columnName + "] Unexpected length/precision. Expected: "
                        + expectedDescriptor.lengthOrPrecision() + ", actual: " + actualDescriptor.lengthOrPrecision()
        );

        addViolationIfDifferent(
                expectedDescriptor.scale(),
                actualDescriptor.scale(),
                violations,
                "[" + tableName + "." + columnName + "] Unexpected scale. Expected: "
                        + expectedDescriptor.scale() + ", actual: " + actualDescriptor.scale()
        );

        addViolationIfDifferent(
                expectedDescriptor.array(),
                actualDescriptor.array(),
                violations,
                "[" + tableName + "." + columnName + "] Unexpected array flag. Expected: "
                        + expectedDescriptor.array() + ", actual: " + actualDescriptor.array()
        );
    }

    /**
     * Verifies primary key mapping for one column block.
     *
     * @param columnBlock generated column block
     * @param column parsed column
     * @param tableName normalized table name
     * @param violations collected violations
     */
    private void assertPrimaryKeyMapping(
            String columnBlock,
            Column column,
            String tableName,
            List<String> violations
    ) {
        boolean actualPrimaryKey = columnBlock.contains("primaryKey=\"true\"");
        boolean expectedPrimaryKey = column.isPrimaryKey();
        String columnName = normalizeIdentifier(column.getName());

        addViolationIfDifferent(
                expectedPrimaryKey,
                actualPrimaryKey,
                violations,
                "[" + tableName + "." + columnName + "] Unexpected primary key mapping. Expected: "
                        + expectedPrimaryKey + ", actual: " + actualPrimaryKey
        );
    }

    /**
     * Verifies nullable mapping for one column block.
     *
     * @param columnBlock generated column block
     * @param column parsed column
     * @param tableName normalized table name
     * @param violations collected violations
     */
    private void assertNullableMapping(
            String columnBlock,
            Column column,
            String tableName,
            List<String> violations
    ) {
        if (isDeferredSqlColumnBlock(columnBlock)) {
            String normalizedColumnBlock = normalizeXmlWhitespace(columnBlock).toUpperCase(Locale.ROOT);
            boolean actualNullableFalse = normalizedColumnBlock.contains(" NOT NULL");
            boolean expectedNullableFalse = !column.isNullable();
            String columnName = normalizeIdentifier(column.getName());

            addViolationIfDifferent(
                    expectedNullableFalse,
                    actualNullableFalse,
                    violations,
                    "[" + tableName + "." + columnName + "] Unexpected nullable mapping. Expected nullable=\"false\": "
                            + expectedNullableFalse + ", actual: " + actualNullableFalse
            );
            return;
        }

        boolean actualNullableFalse = columnBlock.contains("nullable=\"false\"");
        boolean expectedNullableFalse = !column.isNullable();
        String columnName = normalizeIdentifier(column.getName());

        addViolationIfDifferent(
                expectedNullableFalse,
                actualNullableFalse,
                violations,
                "[" + tableName + "." + columnName + "] Unexpected nullable mapping. Expected nullable=\"false\": "
                        + expectedNullableFalse + ", actual: " + actualNullableFalse
        );
    }

    /**
     * Verifies unique mapping for one column block.
     *
     * @param columnBlock generated column block
     * @param column parsed column
     * @param tableName normalized table name
     * @param violations collected violations
     */
    private void assertUniqueMapping(
            String columnBlock,
            Column column,
            String tableName,
            List<String> violations
    ) {
        if (isDeferredSqlColumnBlock(columnBlock)) {
            String normalizedColumnBlock = normalizeXmlWhitespace(columnBlock).toUpperCase(Locale.ROOT);
            boolean actualUnique = normalizedColumnBlock.contains(" UNIQUE");
            boolean expectedUnique = column.isUnique();
            String columnName = normalizeIdentifier(column.getName());

            addViolationIfDifferent(
                    expectedUnique,
                    actualUnique,
                    violations,
                    "[" + tableName + "." + columnName + "] Unexpected unique mapping. Expected: "
                            + expectedUnique + ", actual: " + actualUnique
            );
            return;
        }

        boolean actualUnique = columnBlock.contains("unique=\"true\"");
        boolean expectedUnique = column.isUnique();
        String columnName = normalizeIdentifier(column.getName());

        addViolationIfDifferent(
                expectedUnique,
                actualUnique,
                violations,
                "[" + tableName + "." + columnName + "] Unexpected unique mapping. Expected: "
                        + expectedUnique + ", actual: " + actualUnique
        );
    }

    /**
     * Verifies default value mapping for one parsed column.
     *
     * @param columnBlock generated column block
     * @param column parsed column
     * @param tableName normalized table name
     * @param violations collected violations
     */
    private void assertDefaultValueMapping(
            String columnBlock,
            Column column,
            String tableName,
            List<String> violations
    ) {
        String columnName = normalizeIdentifier(column.getName());
        String defaultValue = column.getDefaultValue();

        if (defaultValue == null || defaultValue.isBlank()) {
            boolean hasAnyDefaultAttribute = columnBlock.contains("defaultValue=")
                    || columnBlock.contains("defaultValueBoolean=")
                    || columnBlock.contains("defaultValueNumeric=")
                    || columnBlock.contains("defaultValueComputed=")
                    || normalizeXmlWhitespace(columnBlock).toUpperCase(Locale.ROOT).contains(" DEFAULT ");

            if (hasAnyDefaultAttribute) {
                violations.add("[" + tableName + "." + columnName + "] Unexpected default value mapping found.");
            }
            return;
        }

        String trimmedDefaultValue = defaultValue.trim();
        String normalizedDefaultValue = trimmedDefaultValue.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();

        if (isEmptyStringCastDefault(normalizedDefaultValue)) {
            assertContains(
                    columnBlock,
                    "defaultValue=\"\"",
                    violations,
                    tableName + "." + columnName
            );
            return;
        }

        if (isDeferredSqlColumnBlock(columnBlock)) {
            String normalizedColumnBlock = normalizeXmlWhitespace(columnBlock);

            if ("true".equals(normalizedDefaultValue) || "false".equals(normalizedDefaultValue)) {
                assertContains(
                        normalizedColumnBlock,
                        "DEFAULT " + normalizedDefaultValue,
                        violations,
                        tableName + "." + columnName
                );
                return;
            }

            if (normalizedDefaultValue.matches("-?\\d+(\\.\\d+)?")) {
                assertContains(
                        normalizedColumnBlock,
                        "DEFAULT " + normalizedDefaultValue,
                        violations,
                        tableName + "." + columnName
                );
                return;
            }

            if (normalizedDefaultValue.contains("(")
                    || "current_timestamp".equals(normalizedDefaultValue)
                    || "current_date".equals(normalizedDefaultValue)
                    || "current_time".equals(normalizedDefaultValue)
                    || "localtimestamp".equals(normalizedDefaultValue)
                    || "localtime".equals(normalizedDefaultValue)
                    || column.isDefaultExpression()) {
                assertContains(
                        normalizedColumnBlock,
                        "DEFAULT " + trimmedDefaultValue,
                        violations,
                        tableName + "." + columnName
                );
                return;
            }

            if (trimmedDefaultValue.matches("^'.*'$")) {
                assertContains(
                        normalizedColumnBlock,
                        "DEFAULT " + trimmedDefaultValue,
                        violations,
                        tableName + "." + columnName
                );
                return;
            }

            assertContains(
                    normalizedColumnBlock,
                    "DEFAULT " + trimmedDefaultValue,
                    violations,
                    tableName + "." + columnName
            );
            return;
        }

        if ("true".equals(normalizedDefaultValue) || "false".equals(normalizedDefaultValue)) {
            assertContains(
                    columnBlock,
                    "defaultValueBoolean=\"" + normalizedDefaultValue + "\"",
                    violations,
                    tableName + "." + columnName
            );
            return;
        }

        if (normalizedDefaultValue.matches("-?\\d+(\\.\\d+)?")) {
            assertContains(
                    columnBlock,
                    "defaultValueNumeric=\"" + normalizedDefaultValue + "\"",
                    violations,
                    tableName + "." + columnName
            );
            return;
        }

        if (normalizedDefaultValue.matches("^'?[^']*'?::(charactervarying|character varying|varchar|text)$")) {
            String literalValue = trimmedDefaultValue
                    .replaceFirst("(?i)::(charactervarying|character varying|varchar|text)$", "")
                    .trim();

            if (literalValue.startsWith("'") && literalValue.endsWith("'") && literalValue.length() >= 2) {
                literalValue = literalValue.substring(1, literalValue.length() - 1);
            }

            if (literalValue.isEmpty() || "::charactervarying".equalsIgnoreCase(literalValue)) {
                assertContains(
                        columnBlock,
                        "defaultValue=\"\"",
                        violations,
                        tableName + "." + columnName
                );
                return;
            }

            assertContains(
                    columnBlock,
                    "defaultValue=\"" + literalValue + "\"",
                    violations,
                    tableName + "." + columnName
            );
            return;
        }

        if (trimmedDefaultValue.matches("^'.*'$")) {
            String literalValue = trimmedDefaultValue.substring(1, trimmedDefaultValue.length() - 1);
            assertContains(
                    columnBlock,
                    "defaultValue=\"" + literalValue + "\"",
                    violations,
                    tableName + "." + columnName
            );
            return;
        }

        if (normalizedDefaultValue.contains("(")
                || "current_timestamp".equals(normalizedDefaultValue)
                || "current_date".equals(normalizedDefaultValue)
                || "current_time".equals(normalizedDefaultValue)
                || "localtimestamp".equals(normalizedDefaultValue)
                || "localtime".equals(normalizedDefaultValue)
                || column.isDefaultExpression()) {
            assertContains(
                    columnBlock,
                    "defaultValueComputed=\"" + trimmedDefaultValue + "\"",
                    violations,
                    tableName + "." + columnName
            );
            return;
        }

        if (normalizedDefaultValue.endsWith("::jsonb") || normalizedDefaultValue.endsWith("::json")) {
            String expectedDefaultValue = trimmedDefaultValue;

            if (!expectedDefaultValue.startsWith("'")) {
                int castIndex = expectedDefaultValue.lastIndexOf("::");
                expectedDefaultValue = "'" + expectedDefaultValue.substring(0, castIndex) + "'" + expectedDefaultValue.substring(castIndex);
            }

            assertContains(
                    columnBlock,
                    "defaultValueComputed=\"" + expectedDefaultValue + "\"",
                    violations,
                    tableName + "." + columnName
            );
        }
    }

    /**
     * Detects if a default value represents an empty string cast (e.g. ''::character varying).
     *
     * @param defaultValue the default value extracted from Liquibase XML
     * @return true if it is an empty string cast, false otherwise
     */
    private boolean isEmptyStringCastDefault(String defaultValue) {
        if (defaultValue == null) {
            return false;
        }

        String normalized = defaultValue
                .replaceAll("\\s+", "")
                .toLowerCase();

        return normalized.equals("''::charactervarying")
                || normalized.equals("''::varchar")
                || normalized.equals("''::text");
    }

    /**
     * Verifies auto-increment mapping for one column block.
     *
     * @param columnBlock generated column block
     * @param column parsed column
     * @param tableName normalized table name
     * @param violations collected violations
     */
    private void assertAutoIncrementMapping(
            String columnBlock,
            Column column,
            String tableName,
            List<String> violations
    ) {
        if (isDeferredSqlColumnBlock(columnBlock)) {
            boolean actualAutoIncrement = false;
            boolean expectedAutoIncrement = column.isIdentity();
            String columnName = normalizeIdentifier(column.getName());

            addViolationIfDifferent(
                    expectedAutoIncrement,
                    actualAutoIncrement,
                    violations,
                    "[" + tableName + "." + columnName + "] Unexpected auto-increment mapping. Expected: "
                            + expectedAutoIncrement + ", actual: " + actualAutoIncrement
            );
            return;
        }

        boolean actualAutoIncrement = columnBlock.contains("autoIncrement=\"true\"");
        boolean expectedAutoIncrement = column.isIdentity();
        String columnName = normalizeIdentifier(column.getName());

        addViolationIfDifferent(
                expectedAutoIncrement,
                actualAutoIncrement,
                violations,
                "[" + tableName + "." + columnName + "] Unexpected auto-increment mapping. Expected: "
                        + expectedAutoIncrement + ", actual: " + actualAutoIncrement
        );
    }

    /**
     * Verifies generated foreign keys for the parsed table.
     *
     * @param xml generated table changelog XML
     * @param table parsed table
     * @param violations collected violations
     */
    private void assertForeignKeys(String xml, Table table, List<String> violations) {
        String tableName = stripSchema(table.getName());

        for (Column column : table.getColumns()) {
            if (!column.isForeignKey()) {
                continue;
            }

            if (isExternalForeignKey(column)) {
                continue;
            }

            assertContains(xml, "<addForeignKeyConstraint baseTableName=\"" + tableName + "\"", violations, tableName);
            assertContains(xml, "baseColumnNames=\"" + normalizeIdentifier(column.getName()) + "\"", violations, tableName);

            String referencedTable = stripSchema(column.getReferencedTable());
            assertContains(xml, "referencedTableName=\"" + normalizeIdentifier(referencedTable) + "\"", violations, tableName);

            String referencedColumn = column.getReferencedColumn() != null && !column.getReferencedColumn().isBlank()
                    ? normalizeIdentifier(column.getReferencedColumn())
                    : "id";
            assertContains(xml, "referencedColumnNames=\"" + referencedColumn + "\"", violations, tableName);

            if (column.getOnDelete() != null && !column.getOnDelete().isBlank()) {
                assertContains(xml, "onDelete=\"" + column.getOnDelete() + "\"", violations, tableName);
            }

            if (column.getOnUpdate() != null && !column.getOnUpdate().isBlank()) {
                assertContains(xml, "onUpdate=\"" + column.getOnUpdate() + "\"", violations, tableName);
            }
        }
    }

    /**
     * Checks whether a foreign key points to a table outside the parsed local schema.
     *
     * @param column parsed column
     * @return true when the referenced table is not part of the parsed schema
     */
    private boolean isExternalForeignKey(Column column) {
        if (column == null || column.getReferencedTable() == null || column.getReferencedTable().isBlank()) {
            return false;
        }

        if (parsedTables == null || parsedTables.isEmpty()) {
            return false;
        }

        String referencedTable = column.getReferencedTable();

        return parsedTables.stream()
                .filter(Objects::nonNull)
                .noneMatch(table -> samePhysicalTable(table.getName(), referencedTable));
    }

    /**
     * Checks whether two physical table names refer to the same table.
     *
     * @param left first table name
     * @param right second table name
     * @return true when both names match by full name or schema-free name
     */
    private boolean samePhysicalTable(String left, String right) {
        if (left == null || right == null) {
            return false;
        }

        String normalizedLeft = left.trim().replace("\"", "");
        String normalizedRight = right.trim().replace("\"", "");

        return normalizedLeft.equalsIgnoreCase(normalizedRight)
                || stripSchema(normalizedLeft).equalsIgnoreCase(stripSchema(normalizedRight));
    }

    /**
     * Verifies that the number of generated main-table foreign keys matches the parsed local table references.
     *
     * @param xml generated table changelog XML
     * @param table parsed table
     * @param violations collected violations
     */
    private void assertForeignKeyCount(String xml, Table table, List<String> violations) {
        long expectedForeignKeyCount = table.getColumns().stream()
                .filter(Column::isForeignKey)
                .filter(column -> !isExternalForeignKey(column))
                .count();

        String normalizedTableName = stripSchema(table.getName());

        int actualForeignKeyCount = countOccurrences(
                xml,
                "<addForeignKeyConstraint baseTableName=\"" + normalizedTableName + "\""
        );

        if (expectedForeignKeyCount != actualForeignKeyCount) {
            violations.add(
                    "[" + normalizedTableName + "] Unexpected foreign key count. Expected: "
                            + expectedForeignKeyCount + ", actual: " + actualForeignKeyCount
            );
        }
    }

    /**
     * Verifies generated composite unique constraints for the parsed table.
     *
     * @param xml generated table changelog XML
     * @param table parsed table
     * @param violations collected violations
     */
    private void assertCompositeUniqueConstraints(String xml, Table table, List<String> violations) {
        if (table.getUniqueConstraints() == null || table.getUniqueConstraints().isEmpty()) {
            return;
        }

        String tableName = stripSchema(table.getName());

        for (UniqueConstraint uniqueConstraint : table.getUniqueConstraints()) {
            List<String> columns = uniqueConstraint.getColumns();
            if (columns == null || columns.size() <= 1) {
                continue;
            }

            String joinedColumns = columns.stream()
                    .map(this::normalizeIdentifier)
                    .collect(Collectors.joining(", "));

            assertContains(
                    xml,
                    "<addUniqueConstraint tableName=\"" + tableName + "\"",
                    violations,
                    tableName
            );
            assertContains(
                    xml,
                    "columnNames=\"" + joinedColumns + "\"",
                    violations,
                    tableName
            );

            if (uniqueConstraint.getName() != null && !uniqueConstraint.getName().isBlank()) {
                assertContains(
                        xml,
                        "constraintName=\"" + uniqueConstraint.getName() + "\"",
                        violations,
                        tableName
                );
            }
        }
    }

    /**
     * Verifies that the number of generated composite unique constraints matches the parsed table.
     *
     * @param xml generated table changelog XML
     * @param table parsed table
     * @param violations collected violations
     */
    private void assertCompositeUniqueConstraintCount(String xml, Table table, List<String> violations) {
        long expectedUniqueConstraintCount = table.getUniqueConstraints() == null
                ? 0
                : table.getUniqueConstraints().stream()
                  .filter(uniqueConstraint -> uniqueConstraint.getColumns() != null
                                              && uniqueConstraint.getColumns().size() > 1)
                  .count();

        String normalizedTableName = stripSchema(table.getName());

        int actualUniqueConstraintCount = countOccurrences(
                xml,
                "<addUniqueConstraint tableName=\"" + normalizedTableName + "\""
        );

        if (expectedUniqueConstraintCount != actualUniqueConstraintCount) {
            violations.add(
                    "[" + normalizedTableName + "] Unexpected composite unique constraint count. Expected: "
                            + expectedUniqueConstraintCount + ", actual: " + actualUniqueConstraintCount
            );
        }
    }

    /**
     * Verifies generated check constraints for the parsed table.
     *
     * @param xml generated table changelog XML
     * @param table parsed table
     * @param violations collected violations
     */
    private void assertCheckConstraints(String xml, Table table, List<String> violations) {
        if (table.getConstraints() == null || table.getConstraints().isEmpty()) {
            return;
        }

        String normalizedXml = normalizeXmlForConstraintComparison(xml);
        String tableName = stripSchema(table.getName());

        for (String constraint : table.getConstraints()) {
            if (constraint == null || constraint.isBlank()) {
                continue;
            }

            String normalizedConstraint = normalizeCheckConstraint(constraint);
            if (normalizedConstraint.isBlank()) {
                continue;
            }

            assertContains(normalizedXml, normalizedConstraint, violations, tableName);
        }
    }

    /**
     * Verifies that the number of generated check constraints matches the parsed table.
     *
     * @param xml generated table changelog XML
     * @param table parsed table
     * @param violations collected violations
     */
    private void assertCheckConstraintCount(String xml, Table table, List<String> violations) {
        long expectedCheckConstraintCount = table.getConstraints() == null
                ? 0
                : table.getConstraints().stream()
                  .filter(Objects::nonNull)
                  .filter(constraint -> Pattern.compile("(?i)\\bCHECK\\s*\\(").matcher(constraint).find())
                  .map(this::normalizeCheckConstraint)
                  .filter(constraint -> !constraint.isBlank())
                  .count();

        String normalizedXml = normalizeXmlWhitespace(xml);
        String tableName = stripSchema(table.getName());

        int actualAddCheckConstraintCount =
                countOccurrences(normalizedXml.toLowerCase(Locale.ROOT), "<addcheckconstraint");

        int actualSqlCheckConstraintCount =
                countSqlCheckConstraintsForTable(normalizedXml, tableName);

        int actualCheckConstraintCount =
                actualAddCheckConstraintCount + actualSqlCheckConstraintCount;

        if (expectedCheckConstraintCount != actualCheckConstraintCount) {
            violations.add(
                    "[" + tableName + "] Unexpected check constraint count. Expected: "
                            + expectedCheckConstraintCount + ", actual: " + actualCheckConstraintCount
            );
        }
    }

    /**
     * Counts CHECK constraints emitted through raw SQL blocks for the given table.
     *
     * @param normalizedXml normalized generated XML
     * @param tableName normalized table name
     * @return number of SQL-based CHECK constraints for the table
     */
    private int countSqlCheckConstraintsForTable(String normalizedXml, String tableName) {
        String flattenedXml = normalizedXml
                .replaceAll("[\\n\\r]+", " ")
                .replaceAll("\\s+", " ");

        Pattern pattern = Pattern.compile(
                "(?i)ALTER TABLE .*?\\b"
                        + Pattern.quote(tableName)
                        + "\\b.*?ADD CONSTRAINT .*?CHECK\\s*\\("
        );

        Matcher matcher = pattern.matcher(flattenedXml);

        int count = 0;
        while (matcher.find()) {
            count++;
        }

        return count;
    }

    /**
     * Verifies that the generated XML contains exactly the expected number of main-table columns,
     * counting both createTable columns and deferred ALTER TABLE ADD COLUMN statements.
     *
     * @param xml full generated table changelog XML
     * @param mainTableBlock generated main-table block
     * @param table parsed table
     * @param tableName normalized table name
     * @param schemaName schema name or null
     * @param violations collected violations
     */
    private void assertMainTableColumnCount(
            String xml,
            String mainTableBlock,
            Table table,
            String tableName,
            String schemaName,
            List<String> violations
    ) {
        List<String> actualColumnNames = extractEffectiveMainTableColumnNames(
                xml,
                mainTableBlock,
                table,
                tableName,
                schemaName
        );

        int actualColumnCount = actualColumnNames.size();
        int expectedColumnCount = table.getColumns().size();

        if (expectedColumnCount != actualColumnCount) {
            violations.add(
                    "[" + tableName + "] Unexpected main-table column count. Expected: "
                            + expectedColumnCount + ", actual: " + actualColumnCount
            );
        }
    }

    /**
     * Verifies that the generated XML contains exactly the expected number of audit-table columns.
     *
     * @param auditTableBlock generated audit-table block
     * @param table parsed table
     * @param violations collected violations
     */
    private void assertAuditTableColumnCount(String auditTableBlock, Table table, List<String> violations) {
        int actualColumnCount = countOccurrences(auditTableBlock, "<column name=\"");
        int expectedColumnCount = table.getColumns().size() + 2;
        String tableName = stripSchema(table.getName());

        if (expectedColumnCount != actualColumnCount) {
            violations.add(
                    "[" + tableName + "] Unexpected audit-table column count. Expected: "
                            + expectedColumnCount + ", actual: " + actualColumnCount
            );
        }
    }

    /**
     * Verifies that the main-table columns preserve the parsed SQL order,
     * allowing some columns to be added later through ALTER TABLE ADD COLUMN.
     *
     * @param xml full generated table changelog XML
     * @param mainTableBlock generated main-table block
     * @param table parsed table
     * @param tableName normalized table name
     * @param schemaName schema name or null
     * @param violations collected violations
     */
    private void assertMainTableColumnOrder(
            String xml,
            String mainTableBlock,
            Table table,
            String tableName,
            String schemaName,
            List<String> violations
    ) {
        List<String> actualColumnNames = extractEffectiveMainTableColumnNames(
                xml,
                mainTableBlock,
                table,
                tableName,
                schemaName
        );

        List<String> expectedColumnNames = table.getColumns().stream()
                .map(Column::getName)
                .map(this::normalizeIdentifier)
                .toList();

        if (!Objects.equals(expectedColumnNames, actualColumnNames)) {
            violations.add(
                    "[" + tableName + "] Unexpected main-table column order. Expected: "
                            + expectedColumnNames + ", actual: " + actualColumnNames
            );
        }
    }

    /**
     * Extracts the effective main-table column names in SQL order, allowing a column
     * to be satisfied either by createTable or by a deferred ALTER TABLE ADD COLUMN statement.
     *
     * @param xml full generated table changelog XML
     * @param mainTableBlock generated main-table block
     * @param table parsed table
     * @param tableName normalized table name
     * @param schemaName schema name or null
     * @return ordered effective main-table column names
     */
    private List<String> extractEffectiveMainTableColumnNames(
            String xml,
            String mainTableBlock,
            Table table,
            String tableName,
            String schemaName
    ) {
        List<String> effectiveColumnNames = new ArrayList<>();

        for (Column column : table.getColumns()) {
            String columnName = normalizeIdentifier(column.getName());

            if (findColumnBlock(mainTableBlock, columnName) != null) {
                effectiveColumnNames.add(columnName);
                continue;
            }

            if (extractDeferredMainTableColumnBlock(xml, tableName, schemaName, columnName) != null) {
                effectiveColumnNames.add(columnName);
            }
        }

        return effectiveColumnNames;
    }

    /**
     * Finds a column block inside a createTable block without recording a violation.
     *
     * @param createTableBlock createTable block
     * @param columnName normalized column name
     * @return extracted column block or null when absent
     */
    private String findColumnBlock(String createTableBlock, String columnName) {
        if (createTableBlock == null || createTableBlock.isBlank() || columnName == null || columnName.isBlank()) {
            return null;
        }

        String marker = "<column name=\"" + columnName + "\"";
        int startIndex = createTableBlock.indexOf(marker);

        if (startIndex < 0) {
            return null;
        }

        int selfClosingEndIndex = createTableBlock.indexOf("/>", startIndex);
        int regularEndIndex = createTableBlock.indexOf("</column>", startIndex);

        if (selfClosingEndIndex >= 0 && (regularEndIndex < 0 || selfClosingEndIndex < regularEndIndex)) {
            return createTableBlock.substring(startIndex, selfClosingEndIndex + 2);
        }

        if (regularEndIndex < 0) {
            return null;
        }

        return createTableBlock.substring(startIndex, regularEndIndex + "</column>".length());
    }

    /**
     * Checks whether the given fragment is a deferred SQL column definition
     * produced through ALTER TABLE ... ADD COLUMN.
     *
     * @param columnBlock source fragment
     * @return true when the fragment is a deferred SQL column block
     */
    private boolean isDeferredSqlColumnBlock(String columnBlock) {
        if (columnBlock == null || columnBlock.isBlank()) {
            return false;
        }

        String normalizedBlock = normalizeXmlWhitespace(columnBlock).toUpperCase(Locale.ROOT);
        return normalizedBlock.startsWith("ALTER TABLE");
    }

    /**
     * Extracts the SQL type from a deferred ALTER TABLE ... ADD COLUMN statement.
     *
     * @param columnBlock deferred SQL fragment
     * @param tableName normalized table name
     * @param columnName normalized column name
     * @param violations collected violations
     * @return extracted SQL type or null when it cannot be resolved
     */
    private String extractTypeFromDeferredSqlColumnBlock(
            String columnBlock,
            String tableName,
            String columnName,
            List<String> violations
    ) {
        String normalizedBlock = normalizeXmlWhitespace(columnBlock);

        Pattern pattern = Pattern.compile(
                "(?i)ADD COLUMN\\s+\"?" + Pattern.quote(columnName) + "\"?\\s+(.+?)(?:\\s+GENERATED\\s+ALWAYS\\s+AS\\b|\\s+DEFAULT\\b|\\s+NOT\\s+NULL\\b|\\s+NULL\\b|\\s+UNIQUE\\b|\\s+PRIMARY\\s+KEY\\b|\\s+CONSTRAINT\\b|\\s*;)"
        );

        Matcher matcher = pattern.matcher(normalizedBlock);
        if (!matcher.find()) {
            violations.add(
                    "[" + tableName + "." + columnName + "] Could not extract SQL type from deferred column fragment: "
                            + normalizedBlock
            );
            return null;
        }

        return matcher.group(1).trim();
    }

    /**
     * Extracts a deferred column SQL fragment when the column is added later with
     * ALTER TABLE ... ADD COLUMN instead of appearing inside createTable.
     *
     * @param xml full generated table changelog XML
     * @param tableName normalized table name
     * @param schemaName schema name or null
     * @param columnName normalized column name
     * @return extracted SQL fragment or null
     */
    private String extractDeferredMainTableColumnBlock(
            String xml,
            String tableName,
            String schemaName,
            String columnName
    ) {
        String normalizedXml = normalizeXmlWhitespace(xml);

        String qualifiedTableName = (schemaName == null || schemaName.isBlank())
                ? tableName
                : schemaName + "." + tableName;

        Pattern pattern = Pattern.compile(
                "(?i)(ALTER TABLE\\s+(?:ONLY\\s+)?\"?"
                        + Pattern.quote(qualifiedTableName)
                        + "\"?\\s+ADD COLUMN\\s+\"?"
                        + Pattern.quote(columnName)
                        + "\"?\\s+.+?;)"
        );

        Matcher matcher = pattern.matcher(normalizedXml);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Verifies that the audit-table columns preserve the parsed SQL order plus revision columns.
     *
     * @param auditTableBlock generated audit-table block
     * @param table parsed table
     * @param violations collected violations
     */
    private void assertAuditTableColumnOrder(String auditTableBlock, Table table, List<String> violations) {
        List<String> actualColumnNames = extractColumnNamesInOrder(auditTableBlock);

        List<String> expectedColumnNames = new ArrayList<>(
                table.getColumns().stream()
                        .map(Column::getName)
                        .map(this::normalizeIdentifier)
                        .toList()
        );
        expectedColumnNames.add("rev");
        expectedColumnNames.add("revtype");

        String tableName = stripSchema(table.getName());

        if (!Objects.equals(expectedColumnNames, actualColumnNames)) {
            violations.add(
                    "[" + tableName + "] Unexpected audit-table column order. Expected: "
                            + expectedColumnNames + ", actual: " + actualColumnNames
            );
        }
    }

    /**
     * Extracts one exact column block from a createTable block.
     *
     * @param createTableBlock table block
     * @param columnName normalized column name
     * @param tableName normalized table name
     * @param violations collected violations
     * @param blockLabel block label
     * @return extracted column block or null when missing
     */
    private String extractColumnBlock(
            String createTableBlock,
            String columnName,
            String tableName,
            List<String> violations,
            String blockLabel
    ) {
        String marker = "<column name=\"" + columnName + "\"";
        int startIndex = createTableBlock.indexOf(marker);

        if (startIndex < 0) {
            violations.add("[" + tableName + "." + columnName + "] Column block not found in " + blockLabel + " table block.");
            return null;
        }

        int selfClosingEndIndex = createTableBlock.indexOf("/>", startIndex);
        int regularEndIndex = createTableBlock.indexOf("</column>", startIndex);

        if (selfClosingEndIndex >= 0 && (regularEndIndex < 0 || selfClosingEndIndex < regularEndIndex)) {
            return createTableBlock.substring(startIndex, selfClosingEndIndex + 2);
        }

        if (regularEndIndex < 0) {
            violations.add("[" + tableName + "." + columnName + "] Column closing tag not found in " + blockLabel + " table block.");
            return null;
        }

        return createTableBlock.substring(startIndex, regularEndIndex + "</column>".length());
    }

    /**
     * Extracts all column names in encounter order from a createTable block.
     *
     * @param createTableBlock table block
     * @return ordered column names
     */
    private List<String> extractColumnNamesInOrder(String createTableBlock) {
        Pattern columnPattern = Pattern.compile("<column\\s+name=\"([^\"]+)\"");
        Matcher matcher = columnPattern.matcher(createTableBlock);

        List<String> columnNames = new ArrayList<>();
        while (matcher.find()) {
            columnNames.add(matcher.group(1));
        }

        return columnNames;
    }

    /**
     * Extracts an attribute value from a Liquibase XML column fragment.
     *
     * @param xmlFragment source XML fragment
     * @param attributeName attribute name
     * @param tableName normalized table name
     * @param columnName normalized column name
     * @param violations collected violations
     * @return attribute value or null when missing
     */
    private String extractAttributeValueFromXmlColumn(
            String xmlFragment,
            String attributeName,
            String tableName,
            String columnName,
            List<String> violations
    ) {
        Pattern attributePattern = Pattern.compile(attributeName + "=\"([^\"]+)\"");
        Matcher matcher = attributePattern.matcher(xmlFragment);

        if (matcher.find()) {
            return matcher.group(1);
        }

        Pattern nestedConstraintsPattern = Pattern.compile(
                "<constraints[^>]*" + attributeName + "=\"([^\"]+)\"[^>]*/?>",
                Pattern.CASE_INSENSITIVE
        );
        Matcher nestedMatcher = nestedConstraintsPattern.matcher(xmlFragment);

        if (nestedMatcher.find()) {
            return nestedMatcher.group(1);
        }

        violations.add(
                "[" + tableName + "." + columnName + "] Attribute not found: " + attributeName
                        + " in fragment: " + normalizeXmlWhitespace(xmlFragment)
        );
        return null;
    }

    /**
     * Counts the occurrences of a token inside a text.
     *
     * @param text source text
     * @param token token to count
     * @return number of occurrences
     */
    private int countOccurrences(String text, String token) {
        String normalizedText = normalizeXmlWhitespace(text);
        String normalizedToken = normalizeXmlWhitespace(token);

        int count = 0;
        int index = 0;

        while ((index = normalizedText.indexOf(normalizedToken, index)) >= 0) {
            count++;
            index += normalizedToken.length();
        }

        return count;
    }

    /**
     * Adds a violation when two values are different.
     *
     * @param expected expected value
     * @param actual actual value
     * @param violations collected violations
     * @param message violation message
     */
    private void addViolationIfDifferent(
            Object expected,
            Object actual,
            List<String> violations,
            String message
    ) {
        if (!Objects.equals(expected, actual)) {
            violations.add(message);
        }
    }

    /**
     * Normalizes XML whitespace so assertions are not sensitive to formatting differences.
     *
     * @param value XML text or fragment
     * @return whitespace-normalized text
     */
    private String normalizeXmlWhitespace(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value.replaceAll("\\s+", " ").trim();
    }

    /**
     * Normalizes a SQL identifier by removing surrounding double quotes.
     *
     * @param identifier raw SQL identifier
     * @return normalized identifier
     */
    private String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }

        String normalizedIdentifier = identifier.trim();

        if (normalizedIdentifier.length() >= 2
                && normalizedIdentifier.startsWith("\"")
                && normalizedIdentifier.endsWith("\"")) {
            normalizedIdentifier = normalizedIdentifier.substring(1, normalizedIdentifier.length() - 1);
        }

        return normalizedIdentifier.trim();
    }

    /**
     * Normalizes a SQL type for stable comparison.
     *
     * @param sqlType raw SQL type
     * @return normalized SQL type
     */
    private String normalizeType(String sqlType) {
        if (sqlType == null || sqlType.isBlank()) {
            return "";
        }

        String normalizedType = sqlType.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*\\(\\s*", "(")
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\s*\\)", ")")
                .replaceAll("\\s*\\[\\s*]", "[]")
                .toUpperCase(Locale.ROOT);

        // Exact matches
        switch (normalizedType) {
            case "BIGSERIAL":
            case "INT8":
                return "BIGINT";
            case "SERIAL":
            case "INT4":
                return "INTEGER";
            case "SMALLSERIAL":
            case "INT2":
                return "SMALLINT";
            case "FLOAT8":
                return "DOUBLE";
            case "FLOAT4":
                return "REAL";
            case "BOOL":
                return "BOOLEAN";
            case "BPCHAR":
                return "CHAR";
            default:
                break;
        }

        // Pattern-based mappings
        if (normalizedType.startsWith("BPCHAR(")) {
            return normalizedType.replaceFirst("BPCHAR", "CHAR");
        }

        if (normalizedType.startsWith("CHARACTER VARYING")) {
            return normalizedType.replaceFirst("CHARACTER VARYING", "VARCHAR");
        }

        if (normalizedType.startsWith("CHARACTERVARYING")) {
            return normalizedType.replaceFirst("CHARACTERVARYING", "VARCHAR");
        }

        if (normalizedType.startsWith("TIMESTAMP")) {
            return "TIMESTAMP";
        }

        if (normalizedType.startsWith("TIME(")) {
            return "TIME";
        }

        return normalizedType;
    }

    /**
     * Parses a SQL type into structured components.
     *
     * @param sqlType normalized or raw SQL type
     * @param tableName normalized table name
     * @param columnName normalized column name
     * @param violations collected violations
     * @return parsed type descriptor or null when parsing fails
     */
    private TypeDescriptor parseTypeDescriptor(
            String sqlType,
            String tableName,
            String columnName,
            List<String> violations
    ) {
        String normalizedType = normalizeType(sqlType);
        Matcher matcher = Constants.TYPE_PATTERN.matcher(normalizedType);

        if (!matcher.matches()) {
            violations.add("[" + tableName + "." + columnName + "] Could not parse SQL type: " + sqlType);
            return null;
        }

        String baseType = matcher.group(1) == null ? "" : matcher.group(1).trim();
        Integer lengthOrPrecision = matcher.group(2) == null ? null : Integer.valueOf(matcher.group(2));
        Integer scale = matcher.group(3) == null ? null : Integer.valueOf(matcher.group(3));
        boolean array = matcher.group(4) != null && !matcher.group(4).isBlank();

        return new TypeDescriptor(baseType, lengthOrPrecision, scale, array);
    }

    /**
     * Normalizes a parsed check constraint for XML comparison.
     *
     * @param constraint raw parsed constraint
     * @return normalized constraint expression
     */
    private String normalizeCheckConstraint(String constraint) {
        if (constraint == null || constraint.isBlank()) {
            return "";
        }

        String normalizedConstraint = constraint.trim()
                .replace("\"", "")
                .replaceAll("\\s+", " ")
                .replaceFirst("(?i)\\bCHECK\\s+CHECK\\b", "CHECK")
                .trim();

        String upperConstraint = normalizedConstraint.toUpperCase(Locale.ROOT);
        int checkIndex = upperConstraint.indexOf("CHECK");
        if (checkIndex < 0) {
            return "";
        }

        normalizedConstraint = normalizedConstraint.substring(checkIndex);

        return normalizedConstraint
                .replaceAll("(?i)\\bCHECK\\s+CHECK\\b", "CHECK")
                .replaceAll("(?i)\\bNULLOR\\b", "NULL OR")
                .replaceAll("(?i)\\bOR([A-Za-z_][A-Za-z0-9_]*)", "OR $1")
                .replaceAll("(?i)\\bAND([A-Za-z_][A-Za-z0-9_]*)", "AND $1")
                .replaceAll("(?i)(-?[0-9]+)AND(-?[0-9]+)", "$1 AND $2")
                .replaceAll("\\s*\\(\\s*", "(")
                .replaceAll("\\s*\\)\\s*", ")")
                .replaceAll("\\s*=\\s*", "=")
                .replaceAll("\\s*<>\\s*", "<>")
                .replaceAll("\\s*!=\\s*", "!=")
                .replaceAll("\\s*<=\\s*", "<=")
                .replaceAll("\\s*>=\\s*", ">=")
                .replaceAll("\\s*<\\s*", "<")
                .replaceAll("\\s*>\\s*", ">")
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("(?i)\\s+BETWEEN\\s+", " BETWEEN ")
                .replaceAll("(?i)\\s+AND\\s+", " AND ")
                .replaceAll("(?i)\\s+OR\\s+", " OR ")
                .replaceAll("(?i)\\s+IN\\s*", " IN")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Normalizes generated XML for check-constraint comparison.
     *
     * @param xml generated XML
     * @return normalized XML text
     */
    private String normalizeXmlForConstraintComparison(String xml) {
        if (xml == null) {
            return "";
        }

        return xml.replace("\"", "")
                .replaceAll("\\s+", " ")
                .replaceAll("(?i)\\bCHECK\\s+CHECK\\b", "CHECK")
                .replaceAll("(?i)\\bNULLOR\\b", "NULL OR")
                .replaceAll("(?i)\\bOR([A-Za-z_][A-Za-z0-9_]*)", "OR $1")
                .replaceAll("(?i)\\bAND([A-Za-z_][A-Za-z0-9_]*)", "AND $1")
                .replaceAll("(?i)(-?[0-9]+)AND(-?[0-9]+)", "$1 AND $2")
                .replaceAll("\\s*\\(\\s*", "(")
                .replaceAll("\\s*\\)\\s*", ")")
                .replaceAll("\\s*=\\s*", "=")
                .replaceAll("\\s*<>\\s*", "<>")
                .replaceAll("\\s*!=\\s*", "!=")
                .replaceAll("\\s*<=\\s*", "<=")
                .replaceAll("\\s*>=\\s*", ">=")
                .replaceAll("\\s*<\\s*", "<")
                .replaceAll("\\s*>\\s*", ">")
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("(?i)\\s+BETWEEN\\s+", " BETWEEN ")
                .replaceAll("(?i)\\s+AND\\s+", " AND ")
                .replaceAll("(?i)\\s+OR\\s+", " OR ")
                .replaceAll("(?i)\\s+IN\\s*", " IN")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Converts a physical table name to the expected Liquibase changelog filename.
     *
     * @param physicalTableName physical table name
     * @return changelog filename
     */
    private String toIncludeFileName(String physicalTableName) {
        String schemaFreeName = stripSchema(physicalTableName);
        String[] parts = schemaFreeName.split("_");

        StringBuilder builder = new StringBuilder(parts[0]);
        for (int index = 1; index < parts.length; index++) {
            builder.append(capitalize(parts[index]));
        }

        return builder + ".xml";
    }

    /**
     * Removes schema prefix from a physical table name.
     *
     * @param tableName physical table name
     * @return schema-free table name
     */
    private String stripSchema(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return "";
        }

        int dotIndex = tableName.indexOf('.');
        return dotIndex >= 0 ? tableName.substring(dotIndex + 1) : tableName;
    }

    /**
     * Extracts schema name from a schema-qualified table reference.
     *
     * @param tableName schema-qualified or plain table name
     * @return schema name or null
     */
    private String extractSchema(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return null;
        }

        int dotIndex = tableName.indexOf('.');
        return dotIndex > 0 ? tableName.substring(0, dotIndex) : null;
    }

    /**
     * Capitalizes the first character of the given text.
     *
     * @param value input text
     * @return capitalized text
     */
    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }

        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }


    /**
     * Verifies that generated index definitions preserve the parsed table indexes.
     *
     * @param xml generated table changelog XML
     * @param table parsed table
     * @param violations collected violations
     */
    private void assertIndexes(String xml, Table table, List<String> violations) {
        if (table.getIndexes() == null || table.getIndexes().isEmpty()) {
            return;
        }

        String normalizedTableName = stripSchema(table.getName());

        for (com.sqldomaingen.model.IndexDefinition indexDefinition : table.getIndexes()) {
            if (shouldSkipUnsupportedIndex(indexDefinition)) {
                continue;
            }

            if (shouldExpectRawSqlIndex(indexDefinition)) {
                assertRawSqlIndex(xml, table, indexDefinition, violations);
                continue;
            }

            assertLiquibaseCreateIndex(xml, normalizedTableName, indexDefinition, violations);
        }
    }

    private boolean shouldSkipUnsupportedIndex(com.sqldomaingen.model.IndexDefinition indexDefinition) {
        if (indexDefinition.getColumns() == null) {
            return false;
        }

        return indexDefinition.getColumns().stream()
                .filter(Objects::nonNull)
                .map(column -> column.toLowerCase(Locale.ROOT))
                .anyMatch(column -> column.contains("gin_trgm_ops")
                        || column.contains("gist_trgm_ops")
                        || column.contains("f_unaccent_ci"));
    }



    private boolean shouldExpectRawSqlIndex(com.sqldomaingen.model.IndexDefinition indexDefinition) {
        if (indexDefinition.getWhereClause() != null && !indexDefinition.getWhereClause().isBlank()) {
            return true;
        }

        return indexDefinition.getColumns().stream()
                .filter(Objects::nonNull)
                .anyMatch(column -> {
                    String normalizedColumn = column.trim().toLowerCase(Locale.ROOT);
                    return normalizedColumn.contains("(")
                            || normalizedColumn.contains(")")
                            || normalizedColumn.matches(".*\\s+(asc|desc)$");
                });
    }

    private void assertRawSqlIndex(
            String xml,
            Table table,
            com.sqldomaingen.model.IndexDefinition indexDefinition,
            List<String> violations
    ) {
        String qualifiedTableName = table.getName();
        String indexMethod = indexDefinition.getUsingMethod() == null || indexDefinition.getUsingMethod().isBlank()
                ? "btree"
                : indexDefinition.getUsingMethod();

        String columns = String.join(", ", indexDefinition.getColumns());

        String expectedSql = "CREATE "
                + (indexDefinition.isUnique() ? "UNIQUE " : "")
                + "INDEX "
                + indexDefinition.getName()
                + " ON "
                + qualifiedTableName
                + " USING "
                + indexMethod
                + " ("
                + columns
                + ")"
                + buildExpectedWhereClause(indexDefinition)
                + ";";

        assertContains(xml, expectedSql, violations, stripSchema(table.getName()));
    }

    private String buildExpectedWhereClause(com.sqldomaingen.model.IndexDefinition indexDefinition) {
        if (indexDefinition.getWhereClause() == null || indexDefinition.getWhereClause().isBlank()) {
            return "";
        }

        return " WHERE " + indexDefinition.getWhereClause().trim();
    }

    private void assertLiquibaseCreateIndex(
            String xml,
            String normalizedTableName,
            com.sqldomaingen.model.IndexDefinition indexDefinition,
            List<String> violations
    ) {
        assertContains(xml, "indexName=\"" + indexDefinition.getName() + "\"", violations, normalizedTableName);

        for (String columnName : indexDefinition.getColumns()) {
            String normalizedColumnName = normalizeIndexColumnNameForXml(columnName);

            // FIX: δεν περιμένουμε ASC/DESC μέσα στο name
            assertContains(
                    xml,
                    "<column name=\"" + normalizedColumnName + "\"",
                    violations,
                    normalizedTableName
            );
        }
    }

    private String normalizeIndexColumnNameForXml(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            return "";
        }

        String cleaned = columnName.trim()
                .replaceAll("(?i)\\s+asc$", "")
                .replaceAll("(?i)\\s+desc$", "")
                .replaceAll("(?i)(asc|desc)$", "")
                .trim();

        return normalizeIdentifier(cleaned);
    }



    /**
     * Structured SQL type descriptor.
     *
     * @param baseType base SQL type
     * @param lengthOrPrecision varchar length or numeric precision
     * @param scale numeric scale
     * @param array whether the type is an array
     */
    private record TypeDescriptor(String baseType, Integer lengthOrPrecision, Integer scale, boolean array) {
    }
}