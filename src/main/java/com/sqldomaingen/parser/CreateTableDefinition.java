package com.sqldomaingen.parser;

import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.CompositeKeyDefinition;
import com.sqldomaingen.model.Table;
import com.sqldomaingen.model.UniqueConstraint;
import com.sqldomaingen.util.GeneratorSupport;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import java.util.*;
import java.util.stream.Collectors;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Log4j2
public class CreateTableDefinition {

    private String tableName;
    private List<ColumnDefinition> columnDefinitions = new ArrayList<>();
    private List<String> constraints = new ArrayList<>();
    private List<UniqueConstraint> compositeUniqueConstraints = new ArrayList<>();

    /**
     * Processes one CREATE TABLE statement and converts it to the internal table model.
     *
     * <p>This method extracts:
     * <ul>
     *     <li>physical table name</li>
     *     <li>column definitions</li>
     *     <li>table-level primary keys</li>
     *     <li>table-level unique constraints</li>
     *     <li>table-level foreign keys</li>
     *     <li>table-level check constraints</li>
     * </ul>
     *
     * @param ctx the CREATE TABLE parse context
     * @return populated {@link Table}
     */
    public Table processCreateTable(PostgreSQLParser.CreateTableStatementContext ctx) {
        log.info("Parse tree (CreateTableStatement): \n{}", ctx.toStringTree());
        log.info("processCreateTable() - START | ctx: {}", ctx);

        this.tableName = null;
        this.columnDefinitions = new ArrayList<>();
        this.constraints = new ArrayList<>();
        this.compositeUniqueConstraints = new ArrayList<>();

        this.tableName = extractTableName(ctx);
        log.info("Extracted table name: {}", this.tableName);

        log.debug("Column definitions BEFORE extraction: {}", this.columnDefinitions);
        this.columnDefinitions = extractColumnDefinitions(ctx);
        log.info("Column definitions AFTER extraction | count={}", this.columnDefinitions.size());

        if (ctx.tableConstraint() != null) {
            for (PostgreSQLParser.TableConstraintContext constraintCtx : ctx.tableConstraint()) {
                extractPrimaryKeyConstraint(constraintCtx);
            }
        }

        extractUniqueConstraints(ctx);
        extractForeignKeyConstraints(ctx);
        extractCheckConstraints(ctx);

        Table table = toTable();
        log.info("processCreateTable() - END | Generated Table: {}", table.getName());

        return table;
    }


    /**
     * Extracts table-level CHECK constraints and stores them as raw table constraints.
     *
     * <p>The original SQL text is read from the ANTLR input stream instead of using
     * {@code getText()}, because {@code getText()} removes whitespace and can corrupt
     * expressions such as {@code order_status IN (...)}.</p>
     *
     * @param ctx the CREATE TABLE parse context
     */
    public void extractCheckConstraints(PostgreSQLParser.CreateTableStatementContext ctx) {
        log.info("extractCheckConstraints() - START");

        if (ctx == null || ctx.tableConstraint() == null || ctx.tableConstraint().isEmpty()) {
            log.info("extractCheckConstraints() - END (no table constraints)");
            return;
        }

        for (PostgreSQLParser.TableConstraintContext tableConstraintContext : ctx.tableConstraint()) {
            if (tableConstraintContext == null) {
                continue;
            }

            String constraintText = tableConstraintContext.getStart().getInputStream()
                    .getText(new org.antlr.v4.runtime.misc.Interval(
                            tableConstraintContext.getStart().getStartIndex(),
                            tableConstraintContext.getStop().getStopIndex()
                    ));

            if (constraintText == null || constraintText.isBlank()) {
                continue;
            }

            String normalizedConstraintText = constraintText
                    .replace("\"", "")
                    .replaceAll("\\s+", " ")
                    .trim()
                    .toUpperCase(java.util.Locale.ROOT);

            if (!normalizedConstraintText.contains("CHECK")) {
                continue;
            }

            String checkConstraintDefinition = extractCheckConstraintDefinition(constraintText);
            if (checkConstraintDefinition == null || checkConstraintDefinition.isBlank()) {
                log.warn("CHECK constraint detected but could not be extracted: {}", constraintText);
                continue;
            }

            this.constraints.add(checkConstraintDefinition);
            log.info("Table-level CHECK extracted: {}", checkConstraintDefinition);
        }

        log.info("extractCheckConstraints() - END");
    }

    /**
     * Extracts a table-level CHECK constraint definition from the original SQL text.
     *
     * @param constraintText raw table constraint text
     * @return normalized CHECK constraint definition or {@code null} when not parseable
     */
    private String extractCheckConstraintDefinition(String constraintText) {
        if (constraintText == null || constraintText.isBlank()) {
            return null;
        }

        String normalizedConstraintText = constraintText
                .replace("\"", "")
                .replaceAll("\\s+", " ")
                .trim();

        String upperConstraintText = normalizedConstraintText.toUpperCase(java.util.Locale.ROOT);
        int checkIndex = upperConstraintText.indexOf("CHECK");

        if (checkIndex < 0) {
            return null;
        }

        String constraintName = extractNamedConstraintName(normalizedConstraintText);
        String checkExpression = normalizedConstraintText.substring(checkIndex + "CHECK".length()).trim();

        if (checkExpression.isBlank()) {
            return null;
        }

        if (constraintName != null && !constraintName.isBlank()) {
            return "CONSTRAINT " + constraintName + " CHECK " + checkExpression;
        }

        return "CHECK " + checkExpression;
    }


    /**
     * Extracts the physical table name from a CREATE TABLE statement.
     * <p>
     * The parser layer must keep the SQL table name as-is so that downstream
     * relationship resolution works with real database identifiers.
     *
     * @param ctx the CREATE TABLE parse context
     * @return the physical table name, including schema when present
     */
    public String extractTableName(PostgreSQLParser.CreateTableStatementContext ctx) {
        if (ctx == null || ctx.tableName() == null || ctx.tableName().isEmpty()) {
            throw new IllegalArgumentException("Table name not found in CREATE TABLE statement.");
        }

        this.tableName = ctx.tableName().getFirst().getText().replace("\"", "").trim();
        log.info("Extracted physical table name: {}", this.tableName);

        return this.tableName;
    }

    public List<ColumnDefinition> extractColumnDefinitions(PostgreSQLParser.CreateTableStatementContext ctx) {
        log.info("extractColumnDefinitions() - START");

        List<ColumnDefinition> extractedColumns = new ArrayList<>();

        if (ctx.columnDef() != null) {
            for (PostgreSQLParser.ColumnDefContext columnCtx : ctx.columnDef()) {
                try {
                    ColumnDefinition column = ColumnDefinition.fromContext(columnCtx);
                    extractedColumns.add(column);

                    log.info("Extracted column: '{}' | SQL type: '{}' | PK: {} | Default: {}",
                            column.getColumnName(), column.getSqlType(), column.isPrimaryKey(), column.getDefaultValue());
                } catch (Exception e) {
                    log.error("Failed to extract column from: {}", columnCtx.getText(), e);
                }
            }
        } else {
            log.warn("No columns found in the CREATE TABLE statement.");
        }

        log.info("extractColumnDefinitions() - END | Extracted {} columns.", extractedColumns.size());
        return extractedColumns;
    }

    /**
     * Extracts a table-level PRIMARY KEY constraint and applies it
     * to the matching column definitions.
     *
     * <p>This method also preserves the original constraint name when the SQL
     * declares a named constraint, for example:
     * <ul>
     *     <li>{@code CONSTRAINT pk_audit_trail PRIMARY KEY (id)}</li>
     *     <li>{@code PRIMARY KEY (id)}</li>
     * </ul>
     *
     * @param ctx the table constraint parse context
     */
    private void extractPrimaryKeyConstraint(PostgreSQLParser.TableConstraintContext ctx) {
        log.info("extractPrimaryKeyConstraint() - START");

        if (ctx == null) {
            log.warn("TableConstraintContext is null.");
            log.info("extractPrimaryKeyConstraint() - END");
            return;
        }

        String constraintText = ctx.getText();
        String normalizedConstraintText = constraintText == null
                ? ""
                : constraintText.replace("\"", "").replaceAll("\\s+", "").toUpperCase(java.util.Locale.ROOT);

        boolean primaryKeyConstraint =
                ctx.PRIMARY_KEY() != null || normalizedConstraintText.contains("PRIMARYKEY");

        if (!primaryKeyConstraint) {
            log.info("extractPrimaryKeyConstraint() - END");
            return;
        }

        String constraintName = extractNamedConstraintName(constraintText);

        if (ctx.columnNameList() == null || ctx.columnNameList().isEmpty()) {
            log.warn("PRIMARY KEY constraint found but no column list was detected.");
            log.info("extractPrimaryKeyConstraint() - END");
            return;
        }

        List<String> primaryKeyColumns = ctx.columnNameList().getFirst().columnName().stream()
                .map(columnNameContext -> columnNameContext.getText().replace("\"", "").trim())
                .filter(columnName -> !columnName.isBlank())
                .toList();

        for (String primaryKeyColumn : primaryKeyColumns) {
            columnDefinitions.stream()
                    .filter(columnDefinition -> columnDefinition.getColumnName().equalsIgnoreCase(primaryKeyColumn))
                    .findFirst()
                    .ifPresentOrElse(columnDefinition -> {
                        columnDefinition.setPrimaryKey(true);
                        columnDefinition.setNullable(false);

                        if (constraintName != null && !constraintName.isBlank()) {
                            columnDefinition.setPrimaryKeyConstraintName(constraintName);
                        }

                        log.info("PRIMARY KEY applied to column '{}' (constraintName={})",
                                columnDefinition.getColumnName(),
                                columnDefinition.getPrimaryKeyConstraintName());
                    }, () -> log.warn("PRIMARY KEY column '{}' not found in extracted column definitions.",
                            primaryKeyColumn));
        }

        log.info("extractPrimaryKeyConstraint() - END");
    }

    /**
     * Extracts table-level FOREIGN KEY constraints and applies them
     * to the corresponding column definitions.
     *
     * <p>This method also preserves the original foreign key constraint name
     * when the SQL declares one, for example:
     * <ul>
     *     <li>{@code CONSTRAINT fk_audit_trail_company FOREIGN KEY (company_id) REFERENCES pep_schema.company(id)}</li>
     * </ul>
     *
     * @param ctx the CREATE TABLE parse context
     */
    public void extractForeignKeyConstraints(PostgreSQLParser.CreateTableStatementContext ctx) {
        log.info("extractForeignKeyConstraints() - START");

        if (ctx == null || ctx.tableConstraint() == null || ctx.tableConstraint().isEmpty()) {
            log.info("extractForeignKeyConstraints() - END (no table constraints)");
            return;
        }

        for (PostgreSQLParser.TableConstraintContext tableConstraintContext : ctx.tableConstraint()) {
            if (tableConstraintContext == null) {
                continue;
            }

            String constraintText = tableConstraintContext.getText();
            if (constraintText == null || constraintText.isBlank()) {
                continue;
            }

            String normalizedConstraintText = constraintText
                    .replace("\"", "")
                    .replaceAll("\\s+", "")
                    .toUpperCase(java.util.Locale.ROOT);

            boolean foreignKeyConstraint =
                    normalizedConstraintText.contains("FOREIGNKEY")
                            && normalizedConstraintText.contains("REFERENCES");

            if (!foreignKeyConstraint) {
                continue;
            }

            String constraintName = extractNamedConstraintName(constraintText);

            List<PostgreSQLParser.ColumnNameListContext> columnNameLists = tableConstraintContext.columnNameList();
            if (columnNameLists == null || columnNameLists.isEmpty()) {
                log.warn("Foreign key constraint found but no columnNameList was parsed: {}", constraintText);
                continue;
            }

            List<String> sourceColumns = columnNameLists.get(0).columnName().stream()
                    .map(columnNameContext -> columnNameContext.getText().replace("\"", "").trim())
                    .filter(columnName -> !columnName.isBlank())
                    .toList();

            List<String> referencedColumns = java.util.Collections.emptyList();
            if (columnNameLists.size() > 1) {
                referencedColumns = columnNameLists.get(1).columnName().stream()
                        .map(columnNameContext -> columnNameContext.getText().replace("\"", "").trim())
                        .filter(columnName -> !columnName.isBlank())
                        .toList();
            }

            String referencedTableName = tableConstraintContext.tableName() != null
                    ? tableConstraintContext.tableName().getText().replace("\"", "").trim()
                    : null;

            String referencedSchemaName = tableConstraintContext.schemaName() != null
                    ? tableConstraintContext.schemaName().getText().replace("\"", "").trim()
                    : null;

            if (referencedTableName == null || referencedTableName.isBlank()) {
                log.warn("Foreign key constraint found but referenced table is missing: {}", constraintText);
                continue;
            }

            String referencedTable = referencedSchemaName != null && !referencedSchemaName.isBlank()
                    ? referencedSchemaName + "." + referencedTableName
                    : referencedTableName;

            String onDeleteAction = extractReferentialAction(normalizedConstraintText, "ONDELETE");
            String onUpdateAction = extractReferentialAction(normalizedConstraintText, "ONUPDATE");

            for (int index = 0; index < sourceColumns.size(); index++) {
                String sourceColumn = sourceColumns.get(index);

                String referencedColumn;
                if (!referencedColumns.isEmpty() && referencedColumns.size() == sourceColumns.size()) {
                    referencedColumn = referencedColumns.get(index);
                } else if (sourceColumns.size() == 1) {
                    referencedColumn = "id";
                } else {
                    referencedColumn = null;
                }

                columnDefinitions.stream()
                        .filter(columnDefinition -> columnDefinition.getColumnName().equalsIgnoreCase(sourceColumn))
                        .findFirst()
                        .ifPresentOrElse(columnDefinition -> {
                            columnDefinition.setForeignKey(true);
                            columnDefinition.setReferencedTable(referencedTable);
                            columnDefinition.setReferencedColumn(referencedColumn);
                            columnDefinition.setOnDelete(onDeleteAction);
                            columnDefinition.setOnUpdate(onUpdateAction);

                            if (constraintName != null && !constraintName.isBlank()) {
                                columnDefinition.setForeignKeyConstraintName(constraintName);
                            }

                            log.info("Foreign key applied: {} -> {}.{} (constraintName={})",
                                    sourceColumn,
                                    referencedTable,
                                    referencedColumn,
                                    columnDefinition.getForeignKeyConstraintName());

                            if (onDeleteAction != null) {
                                log.info("ON DELETE applied to column '{}': {}", sourceColumn, onDeleteAction);
                            }

                            if (onUpdateAction != null) {
                                log.info("ON UPDATE applied to column '{}': {}", sourceColumn, onUpdateAction);
                            }
                        }, () -> log.warn("Could not find source column '{}' for FK constraint '{}'",
                                sourceColumn,
                                constraintText));
            }
        }

        log.info("extractForeignKeyConstraints() - END");
    }

    /**
     * Extracts an explicitly declared table-level constraint name.
     *
     * @param constraintText raw table constraint text
     * @return the extracted constraint name or {@code null} when no explicit name exists
     */
    private String extractNamedConstraintName(String constraintText) {
        if (constraintText == null || constraintText.isBlank()) {
            return null;
        }

        String normalizedConstraintText = constraintText
                .replace("\"", "")
                .replaceAll("\\s+", " ")
                .trim();

        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)^\\s*CONSTRAINT\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s+")
                .matcher(normalizedConstraintText);

        if (!matcher.find()) {
            return null;
        }

        String constraintName = matcher.group(1);

        return constraintName == null || constraintName.isBlank()
                ? null
                : constraintName.trim();
    }


    /**
     * Extracts a referential action from a normalized constraint text.
     *
     * @param constraintText the uppercase constraint text without guaranteed whitespace
     * @param keyword the keyword to search for, e.g. ONDELETE or ONUPDATE
     * @return the extracted action or null when not found
     */
    private String extractReferentialAction(String constraintText, String keyword) {
        int keywordIndex = constraintText.indexOf(keyword);
        if (keywordIndex < 0) {
            return null;
        }

        String tail = constraintText.substring(keywordIndex + keyword.length());

        if (tail.startsWith("CASCADE")) {
            return "CASCADE";
        }
        if (tail.startsWith("SETNULL")) {
            return "SET NULL";
        }
        if (tail.startsWith("SETDEFAULT")) {
            return "SET DEFAULT";
        }
        if (tail.startsWith("RESTRICT")) {
            return "RESTRICT";
        }
        if (tail.startsWith("NOACTION")) {
            return "NO ACTION";
        }

        return null;
    }

    /**
     * Parses all CREATE TABLE statements and returns a map keyed by the generated Table name.
     */
    public Map<String, Table> parseAllTables(List<PostgreSQLParser.CreateTableStatementContext> createTableStatements) {
        log.info("parseAllTables() - START");

        Map<String, Table> tableMap = new HashMap<>();
        for (PostgreSQLParser.CreateTableStatementContext ctx : createTableStatements) {
            Table table = processCreateTable(ctx);
            tableMap.put(table.getName(), table);
            log.info("Added table '{}' to tableMap", table.getName());
        }

        log.info("parseAllTables() - END | Total tables parsed: {}", tableMap.size());
        return tableMap;
    }

    /**
     * Extracts table-level UNIQUE constraints and applies them to matching columns.
     * <p>
     * Single-column UNIQUE constraints are promoted to column uniqueness.
     * Multi-column UNIQUE constraints are stored as table-level unique constraints.
     *
     * @param ctx the CREATE TABLE parse context
     */
    public void extractUniqueConstraints(PostgreSQLParser.CreateTableStatementContext ctx) {
        log.info("extractUniqueConstraints() - START");

        if (ctx == null || ctx.tableConstraint() == null || ctx.tableConstraint().isEmpty()) {
            log.info("extractUniqueConstraints() - END (no table constraints)");
            return;
        }

        for (PostgreSQLParser.TableConstraintContext tableConstraintContext : ctx.tableConstraint()) {
            if (tableConstraintContext == null) {
                continue;
            }

            String constraintText = tableConstraintContext.getText();
            if (constraintText == null || constraintText.isBlank()) {
                continue;
            }

            String normalizedConstraintText = constraintText
                    .replace("\"", "")
                    .replaceAll("\\s+", "")
                    .toUpperCase(java.util.Locale.ROOT);

            if (!normalizedConstraintText.contains("UNIQUE")) {
                continue;
            }

            List<PostgreSQLParser.ColumnNameListContext> columnNameLists = tableConstraintContext.columnNameList();
            if (columnNameLists == null || columnNameLists.isEmpty()) {
                continue;
            }

            List<String> uniqueColumns = columnNameLists.getFirst().columnName().stream()
                    .map(columnNameContext -> columnNameContext.getText().replace("\"", "").trim())
                    .filter(columnName -> !columnName.isBlank())
                    .toList();

            String constraintName = extractConstraintName(constraintText);

            if (uniqueColumns.size() == 1) {
                String uniqueColumnName = uniqueColumns.getFirst();

                columnDefinitions.stream()
                        .filter(columnDefinition -> columnDefinition.getColumnName().equalsIgnoreCase(uniqueColumnName))
                        .findFirst()
                        .ifPresentOrElse(columnDefinition -> {
                            columnDefinition.setUnique(true);
                            log.info("UNIQUE applied to column: {}", uniqueColumnName);
                        }, () -> log.warn("Could not find column '{}' for UNIQUE constraint '{}'",
                                uniqueColumnName,
                                constraintText));

                continue;
            }

            UniqueConstraint uniqueConstraint = new UniqueConstraint(
                    constraintName,
                    new ArrayList<>(uniqueColumns)
            );

            this.compositeUniqueConstraints.add(uniqueConstraint);

            log.info("Composite UNIQUE detected: {} -> {}", constraintName, uniqueColumns);
        }

        log.info("extractUniqueConstraints() - END");
    }


    /**
     * Extracts the constraint name from a table constraint text.
     * <p>
     * Examples:
     * <ul>
     *     <li>CONSTRAINT uk_chamber_department UNIQUE (chamber_id, chamber_department_id)
     *     -> uk_chamber_department</li>
     *     <li>UNIQUE (email) -> generated fallback name</li>
     * </ul>
     *
     * @param constraintText raw constraint text
     * @return extracted constraint name or generated fallback name
     */
    private String extractConstraintName(String constraintText) {
        if (constraintText == null || constraintText.isBlank()) {
            return "uk_unknown";
        }

        String trimmedConstraintText = constraintText.replace("\"", "").trim();

        String upperConstraintText = trimmedConstraintText.toUpperCase(java.util.Locale.ROOT);
        int constraintIndex = upperConstraintText.indexOf("CONSTRAINT");
        int uniqueIndex = upperConstraintText.indexOf("UNIQUE");

        if (constraintIndex >= 0 && uniqueIndex > constraintIndex) {
            String extractedName = trimmedConstraintText
                    .substring(constraintIndex + "CONSTRAINT".length(), uniqueIndex)
                    .trim();

            if (!extractedName.isBlank()) {
                return extractedName;
            }
        }

        return "uk_" + GeneratorSupport.normalizeTableName(this.tableName);
    }





    /**
     * Converts the parsed CREATE TABLE definition into the internal table model.
     *
     * @return populated table model
     */
    public Table toTable() {
        log.info("toTable() - START | tableName={}", this.tableName);

        Table table = new Table();
        table.setName(this.tableName);

        List<Column> columns = buildResolvedColumns();
        table.setColumns(columns);

        table.addConstraints(this.constraints);
        table.setUniqueConstraints(new ArrayList<>(this.compositeUniqueConstraints));

        List<ColumnDefinition> primaryKeyColumns = getCompositePrimaryKeyColumns();
        if (primaryKeyColumns.size() > 1) {
            CompositeKeyDefinition compositeKey = new CompositeKeyDefinition();
            compositeKey.setName(table.getName() + "Key");
            compositeKey.setColumns(
                    primaryKeyColumns.stream()
                            .map(ColumnDefinition::toColumn)
                            .toList()
            );

            table.setCompositeKey(compositeKey);
        }

        log.info("toTable() - END | Table '{}' with {} columns and {} composite unique constraints.",
                table.getName(),
                columns.size(),
                table.getUniqueConstraints().size());

        return table;
    }

    /**
     * Builds table columns and resolves duplicate Java field names before the table model is used by generators.
     *
     * @return resolved columns
     */
    private List<Column> buildResolvedColumns() {
        List<Column> columns = this.columnDefinitions.stream()
                .map(ColumnDefinition::toColumn)
                .toList();

        resolveDuplicateFieldNames(columns);

        return columns;
    }

    /**
     * Resolves duplicate Java field names by keeping foreign key relation names unchanged
     * and renaming scalar duplicates.
     *
     * @param columns table columns
     */
    private void resolveDuplicateFieldNames(List<Column> columns) {
        Map<String, List<Column>> columnsByFieldName = columns.stream()
                .collect(Collectors.groupingBy(
                        Column::getFieldName,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        columnsByFieldName.values().stream()
                .filter(duplicates -> duplicates.size() > 1)
                .forEach(this::resolveDuplicateFieldNameGroup);
    }

    /**
     * Resolves one duplicate field-name group.
     *
     * @param duplicateColumns columns sharing the same Java field name
     */
    private void resolveDuplicateFieldNameGroup(List<Column> duplicateColumns) {
        for (Column column : duplicateColumns) {
            if (column.isForeignKey()) {
                continue;
            }

            String resolvedFieldName = column.getFieldName() + "Text";
            log.warn("Resolved duplicate field name '{}' for column '{}' as '{}'",
                    column.getFieldName(),
                    column.getName(),
                    resolvedFieldName);

            column.setFieldName(resolvedFieldName);
        }
    }

    /**
     * Detects if the table has a composite primary key.
     *
     * @return list of primary key columns when composite, otherwise empty list
     */
    private List<ColumnDefinition> getCompositePrimaryKeyColumns() {
        return columnDefinitions.stream()
                .filter(ColumnDefinition::isPrimaryKey)
                .toList();
    }
}
