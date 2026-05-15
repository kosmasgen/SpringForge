package com.sqldomaingen.generator;

import com.sqldomaingen.liquibase.LiquibaseDependencyGraphBuilder;
import com.sqldomaingen.liquibase.TableDependencySorter;
import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.IndexDefinition;
import com.sqldomaingen.model.Table;
import com.sqldomaingen.util.Constants;
import com.sqldomaingen.util.GeneratorSupport;
import lombok.extern.log4j.Log4j2;

import java.nio.file.Path;
import java.util.*;


/**
 * Generates the base Liquibase folder structure and root changelog files.
 */
@Log4j2
public class LiquibaseGenerator {

    private final List<String> generationWarnings = new ArrayList<>();

    /**
     * Returns generation warnings collected during Liquibase generation.
     *
     * @return immutable generation warnings
     */
    public List<String> getGenerationWarnings() {
        return List.copyOf(generationWarnings);
    }

    /**
     * Generates the initial Liquibase structure without table includes.
     *
     * @param outputDir generated project root directory
     * @param overwrite true to overwrite existing files
     */
    public void generateLiquibaseFiles(String outputDir, List<Table> tables, boolean overwrite) {
        generateLiquibaseFiles(outputDir, tables, overwrite, null);
    }

    /**
     * Generates the Liquibase structure and writes ordered table includes into version main.xml.
     *
     * @param outputDir generated project root directory
     * @param tables parsed tables
     * @param overwrite true to overwrite existing files
     * @param author liquibase author
     */
    public void generateLiquibaseFiles(String outputDir, List<Table> tables, boolean overwrite, String author) {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(tables, "tables must not be null");

        Path migrationRoot = resolveMigrationRoot(outputDir);
        Path versionDir = resolveVersionDirectory(migrationRoot);

        List<String> orderedChangelogFiles = buildOrderedChangelogFiles(tables);

        GeneratorSupport.writeFile(
                versionDir.resolve("audit.xml"),
                buildAuditChangelogContent(author,tables),
                overwrite
        );

        boolean extensionsRequired = requiresExtensionsChangelog(tables);

        if (extensionsRequired) {
            GeneratorSupport.writeFile(
                    versionDir.resolve("extensions.xml"),
                    buildExtensionsChangelogContent(author, tables),
                    overwrite
            );
        }

        generateTableChangelogFiles(versionDir, tables, overwrite, author);

        GeneratorSupport.writeFile(
                versionDir.resolve("main.xml"),
                buildVersionMainContent(orderedChangelogFiles, extensionsRequired),
                overwrite
        );

        GeneratorSupport.writeFile(
                migrationRoot.resolve("changelog-master.xml"),
                buildMasterChangelogContent(),
                overwrite
        );

        log.info(
                "Liquibase base structure generated under: {}. Planned table changelog includes: {}",
                versionDir.toAbsolutePath(),
                orderedChangelogFiles.size()
        );
    }


    /**
     * Returns whether the extensions changelog must be generated.
     *
     * @param tables parsed tables
     * @return true when at least one PostgreSQL extension or helper function is required
     */
    private boolean requiresExtensionsChangelog(List<Table> tables) {
        return requiresPgTrgmExtension(tables)
                || requiresUnaccentExtension(tables);
    }


    /**
     * Builds the Liquibase changelog that installs required PostgreSQL extensions.
     *
     * @param author liquibase author
     * @param tables parsed tables
     * @return generated extensions changelog XML
     */
    private String buildExtensionsChangelogContent(String author, List<Table> tables) {
        String resolvedAuthor = resolveLiquibaseAuthor(author);
        String extensionSchema = resolveExtensionSchema(tables);

        boolean requiresPgTrgm = requiresPgTrgmExtension(tables);
        boolean requiresUnaccent = requiresUnaccentExtension(tables);

        StringBuilder builder = new StringBuilder();

        builder.append("""
<?xml version="1.0" encoding="utf-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.11.xsd">

""");

        if (requiresPgTrgm) {
            builder.append("""
    <changeSet id="create_pg_trgm_extension" author="%s">
        <sql>
            CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA %s;
        </sql>
    </changeSet>

""".formatted(resolvedAuthor, extensionSchema));
        }

        if (requiresUnaccent) {
            builder.append("""
    <changeSet id="create_unaccent_extension" author="%s">
        <sql>
            CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA %s;
        </sql>
    </changeSet>

    <changeSet id="create_f_unaccent_ci_function" author="%s">
        <sql splitStatements="false"><![CDATA[
            CREATE OR REPLACE FUNCTION %s.f_unaccent_ci(input text)
            RETURNS text
            LANGUAGE sql
            IMMUTABLE
            PARALLEL SAFE
            AS $$
                SELECT lower(%s.unaccent(input));
            $$;
        ]]></sql>
    </changeSet>

""".formatted(
                    resolvedAuthor,
                    extensionSchema,
                    resolvedAuthor,
                    extensionSchema,
                    extensionSchema
            ));
        }

        builder.append("""
</databaseChangeLog>
""");

        return builder.toString();
    }


    /**
     * Determines whether the generated Liquibase changelog requires the pg_trgm extension.
     *
     * @param tables parsed tables
     * @return true when any parsed index uses trigram operator classes
     */
    private boolean requiresPgTrgmExtension(List<Table> tables) {
        return containsIndexColumnExpression(tables, "gin_trgm_ops")
                || containsIndexColumnExpression(tables, "gist_trgm_ops");
    }

    /**
     * Determines whether the generated Liquibase changelog requires the unaccent extension.
     *
     * @param tables parsed tables
     * @return true when any parsed index uses unaccent-based expressions
     */
    private boolean requiresUnaccentExtension(List<Table> tables) {
        return containsIndexColumnExpression(tables, "unaccent(")
                || containsIndexColumnExpression(tables, "f_unaccent_ci(");
    }


    /**
     * Checks whether any parsed index column expression contains the provided token.
     *
     * @param tables parsed tables
     * @param token expression token to search for
     * @return true when at least one index column expression contains the token
     */
    private boolean containsIndexColumnExpression(List<Table> tables, String token) {
        if (tables == null || tables.isEmpty() || token == null || token.isBlank()) {
            return false;
        }

        String normalizedToken = token.toLowerCase(Locale.ROOT);

        for (Table table : tables) {
            if (table == null || table.getIndexes() == null || table.getIndexes().isEmpty()) {
                continue;
            }

            for (IndexDefinition index : table.getIndexes()) {
                if (index == null || index.getColumns() == null || index.getColumns().isEmpty()) {
                    continue;
                }

                for (String columnExpression : index.getColumns()) {
                    if (columnExpression == null || columnExpression.isBlank()) {
                        continue;
                    }

                    if (columnExpression.toLowerCase(Locale.ROOT).contains(normalizedToken)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Generates one Liquibase changelog file per parsed table.
     *
     * @param versionDir version-specific changelog directory
     * @param tables parsed tables
     * @param overwrite true to overwrite existing files
     * @param author liquibase author
     */
    private void generateTableChangelogFiles(Path versionDir, List<Table> tables, boolean overwrite, String author) {
        Set<String> availableTableReferences = buildAvailableTableReferences(tables);

        for (Table table : tables) {
            String fileName = toTableChangelogFileName(table.getName());

            GeneratorSupport.writeFile(
                    versionDir.resolve(fileName),
                    buildTableChangelogContent(table, author, availableTableReferences),
                    overwrite
            );
        }
    }


    /**
     * Builds the Liquibase changelog content for a single table and its audit table.
     *
     * @param table parsed table
     * @param author liquibase author
     * @param availableTableReferences available parsed table references
     * @return generated table changelog XML
     */
    private String buildTableChangelogContent(
            Table table,
            String author,
            Set<String> availableTableReferences
    ) {
        String resolvedAuthor = resolveLiquibaseAuthor(author);
        String tableName = GeneratorSupport.normalizeTableName(table.getName());
        String changelogFileName = toTableChangelogFileName(table.getName());
        String auditTableName = tableName + "_aud";
        String changeSetId = buildCreateTableAndAuditChangeSetId(tableName);

        StringBuilder builder = new StringBuilder();

        builder.append("""
<?xml version="1.0" encoding="utf-8"?>
<databaseChangeLog
        logicalFilePath="%s/%s"
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.11.xsd">

""".formatted(Constants.DEFAULT_VERSION, changelogFileName));

        builder.append("""
    <changeSet id="%s" author="%s">
""".formatted(changeSetId, resolvedAuthor));

        StringBuilder changeSetBuilder = new StringBuilder();

        appendMainTableCreateBlock(changeSetBuilder, tableName, table);
        appendSectionSpacing(changeSetBuilder);
        appendGeneratedStoredColumnsBlock(changeSetBuilder, tableName, table);
        appendSectionSpacing(changeSetBuilder);
        appendMainTableCheckConstraints(changeSetBuilder, table);
        appendSectionSpacing(changeSetBuilder);
        appendMainTableUniqueConstraints(changeSetBuilder, tableName, table);
        appendSectionSpacing(changeSetBuilder);
        appendMainTableIndexes(changeSetBuilder, tableName, table);
        appendSectionSpacing(changeSetBuilder);
        appendMainTableForeignKeys(changeSetBuilder, tableName, table, availableTableReferences);
        appendSectionSpacing(changeSetBuilder);
        appendAuditTableCreateBlock(changeSetBuilder, auditTableName, table);
        appendSectionSpacing(changeSetBuilder);
        appendAuditPrimaryKeyBlock(changeSetBuilder, auditTableName, table);
        appendSectionSpacing(changeSetBuilder);
        appendAuditForeignKeyBlock(changeSetBuilder, tableName, auditTableName);

        builder.append(normalizeLiquibaseSectionSpacing(changeSetBuilder.toString()));

        builder.append("""

    </changeSet>

</databaseChangeLog>
""");

        return builder.toString();
    }

    /**
     * Normalizes spacing between Liquibase blocks inside a changeSet.
     *
     * @param content raw generated changeSet content
     * @return content with exactly two empty lines between XML blocks
     */
    private String normalizeLiquibaseSectionSpacing(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }

        return content
                .replaceAll("\\R{3,}", "\n\n\n")
                .stripTrailing();
    }

    /**
     * Appends visual spacing between Liquibase sections.
     *
     * @param builder target builder
     */
    private void appendSectionSpacing(StringBuilder builder) {
        builder.append("\n\n");
    }

    /**
     * Appends table-level CHECK constraints for the main table.
     *
     * @param builder XML builder
     * @param table parsed table
     */
    private void appendMainTableCheckConstraints(StringBuilder builder, Table table) {
        if (table == null || table.getConstraints() == null || table.getConstraints().isEmpty()) {
            return;
        }

        String qualifiedTableName = buildQualifiedTableName(table.getName());

        for (String constraint : table.getConstraints()) {
            if (constraint == null || constraint.isBlank()) {
                continue;
            }

            String normalizedConstraint = constraint
                    .replaceAll("\\s+", " ")
                    .trim();

            if (!normalizedConstraint.toUpperCase(java.util.Locale.ROOT).contains("CHECK")) {
                continue;
            }

            String cleanedConstraint = normalizedConstraint
                    .replaceFirst("(?i)\\bCHECK\\s+CHECK\\b", "CHECK")
                    .trim();

            builder.append("""
        <sql><![CDATA[
            ALTER TABLE %s
            ADD %s;
        ]]></sql>
""".formatted(
                    qualifiedTableName,
                    cleanedConstraint
            ));
        }
    }

    /**
     * Builds the fully qualified table name when a schema exists.
     *
     * @param rawTableName raw table name, optionally schema-qualified
     * @return schema-qualified table name when available, otherwise plain table name
     */
    private String buildQualifiedTableName(String rawTableName) {
        String schemaName = extractSchemaNameFromTable(rawTableName);
        String tableName = GeneratorSupport.normalizeTableName(rawTableName);

        if (schemaName == null || schemaName.isBlank()) {
            return tableName;
        }

        return schemaName + "." + tableName;
    }


    /**
     * Appends foreign key constraints for the main table.
     *
     * <p>This method preserves the raw table name so schema-qualified table names
     * can be used when building Liquibase foreign key attributes such as
     * {@code baseTableSchemaName}.
     *
     * @param builder XML builder
     * @param tableName physical table name without schema
     * @param table parsed table
     * @param availableTableReferences available parsed table references
     */
    private void appendMainTableForeignKeys(
            StringBuilder builder,
            String tableName,
            Table table,
            Set<String> availableTableReferences
    ) {
        String rawTableName = table.getName();

        for (Column column : table.getColumns()) {
            if (!column.isForeignKey()) {
                continue;
            }

            appendMainTableForeignKey(
                    builder,
                    tableName,
                    rawTableName,
                    column,
                    availableTableReferences
            );
        }
    }

    /**
     * Builds the set of available parsed table references.
     *
     * <p>Both schema-qualified and plain table names are stored so foreign key checks
     * can match references declared with or without schema prefixes.
     *
     * @param tables parsed tables
     * @return available table references
     */
    private Set<String> buildAvailableTableReferences(List<Table> tables) {
        Set<String> availableTableReferences = new java.util.LinkedHashSet<>();

        if (tables == null) {
            return availableTableReferences;
        }

        for (Table table : tables) {
            if (table == null || table.getName() == null || table.getName().isBlank()) {
                continue;
            }

            String canonicalReference = canonicalizeTableReference(table.getName());
            String plainTableName = normalizeIdentifier(GeneratorSupport.normalizeTableName(table.getName()));

            if (!canonicalReference.isBlank()) {
                availableTableReferences.add(canonicalReference);
            }

            if (!plainTableName.isBlank()) {
                availableTableReferences.add(plainTableName);
            }
        }

        return availableTableReferences;
    }

    /**
     * Checks whether the referenced table exists in the parsed schema.
     *
     * @param referencedTableRaw referenced raw table name
     * @param availableTableReferences available parsed table references
     * @return true when the referenced table exists
     */
    private boolean isReferencedTableAvailable(String referencedTableRaw, Set<String> availableTableReferences) {
        if (referencedTableRaw == null || referencedTableRaw.isBlank()) {
            return false;
        }

        if (availableTableReferences == null || availableTableReferences.isEmpty()) {
            return false;
        }

        String canonicalReference = canonicalizeTableReference(referencedTableRaw);
        String plainTableName = normalizeIdentifier(extractTableNameOnly(referencedTableRaw));

        return (!canonicalReference.isBlank() && availableTableReferences.contains(canonicalReference))
                || (!plainTableName.isBlank() && availableTableReferences.contains(plainTableName));
    }

    /**
     * Canonicalizes a table reference so comparisons remain stable.
     *
     * @param tableReference raw table reference
     * @return canonical table reference
     */
    private String canonicalizeTableReference(String tableReference) {
        if (tableReference == null || tableReference.isBlank()) {
            return "";
        }

        String trimmedTableReference = tableReference.trim();
        int dotIndex = trimmedTableReference.lastIndexOf('.');

        if (dotIndex <= 0) {
            return normalizeIdentifier(trimmedTableReference);
        }

        String schemaName = normalizeIdentifier(trimmedTableReference.substring(0, dotIndex));
        String tableName = normalizeIdentifier(trimmedTableReference.substring(dotIndex + 1));

        if (schemaName.isBlank()) {
            return tableName;
        }

        if (tableName.isBlank()) {
            return "";
        }

        return schemaName + "." + tableName;
    }

    /**
     * Appends one foreign key constraint for the main table.
     *
     * <p>This method uses the raw table name to preserve schema information for the
     * base table, while using the normalized table name for the baseTableName
     * Liquibase attribute.
     *
     * <p>If the referenced table does not exist in the parsed schema, the column is
     * still generated in the createTable block, but the foreign key relation is skipped.
     *
     * @param builder XML builder
     * @param tableName physical table name without schema
     * @param rawTableName raw table name, optionally schema-qualified
     * @param column parsed foreign key column
     * @param availableTableReferences available parsed table references
     */
    private void appendMainTableForeignKey(
            StringBuilder builder,
            String tableName,
            String rawTableName,
            Column column,
            Set<String> availableTableReferences
    ) {
        String baseColumnName = normalizeIdentifier(column.getName());
        String referencedTableRaw = column.getReferencedTable();
        String referencedColumnName = normalizeIdentifier(resolveReferencedColumnName(column));

        if (referencedTableRaw == null || referencedTableRaw.isBlank() || referencedColumnName.isBlank()) {
            return;
        }

        if (!isReferencedTableAvailable(referencedTableRaw, availableTableReferences)) {
            String warningMessage = String.format(
                    "Skipping foreign key for table '%s' column '%s' because referenced table '%s' was not found in parsed schema.",
                    rawTableName,
                    baseColumnName,
                    referencedTableRaw
            );

            generationWarnings.add(warningMessage);
            log.warn(warningMessage);
            return;
        }

        String referencedSchemaName = extractSchemaName(referencedTableRaw);
        String referencedTableName = normalizeIdentifier(extractTableNameOnly(referencedTableRaw));
        String constraintName = resolveForeignKeyConstraintName(tableName, baseColumnName, column);

        builder.append("        <addForeignKeyConstraint")
                .append("\n                                 baseTableName=\"")
                .append(escapeXml(tableName))
                .append("\"")
                .append("\n                                 baseColumnNames=\"")
                .append(escapeXml(baseColumnName))
                .append("\"")
                .append("\n                                 constraintName=\"")
                .append(escapeXml(constraintName))
                .append("\"")
                .append("\n                                 referencedTableName=\"")
                .append(escapeXml(referencedTableName))
                .append("\"")
                .append("\n                                 referencedColumnNames=\"")
                .append(escapeXml(referencedColumnName))
                .append("\"");

        String baseTableSchemaAttribute = buildBaseTableSchemaAttribute(rawTableName);
        if (!baseTableSchemaAttribute.isBlank()) {
            builder.append("\n                                ")
                    .append(baseTableSchemaAttribute.trim());
        }

        String referencedTableSchemaAttribute = buildReferencedTableSchemaAttribute(referencedSchemaName);
        if (!referencedTableSchemaAttribute.isBlank()) {
            builder.append("\n                                ")
                    .append(referencedTableSchemaAttribute.trim());
        }

        String onDeleteAttribute = buildOnDeleteAttribute(column);
        if (!onDeleteAttribute.isBlank()) {
            builder.append("\n                                ")
                    .append(onDeleteAttribute.trim());
        }

        String onUpdateAttribute = buildOnUpdateAttribute(column);
        if (!onUpdateAttribute.isBlank()) {
            builder.append("\n                                ")
                    .append(onUpdateAttribute.trim());
        }

        builder.append("/>\n");
    }

    /**
     * Resolves the foreign key constraint name for the given column.
     *
     * <p>When the parser has preserved the original database constraint name,
     * that value is used. Otherwise, a deterministic fallback name is generated.
     *
     * @param tableName physical table name without schema
     * @param columnName physical base column name
     * @param column parsed foreign key column
     * @return resolved foreign key constraint name
     */
    private String resolveForeignKeyConstraintName(String tableName, String columnName, Column column) {
        if (column != null
                && column.getForeignKeyConstraintName() != null
                && !column.getForeignKeyConstraintName().isBlank()) {
            return normalizeIdentifier(column.getForeignKeyConstraintName());
        }

        return buildForeignKeyConstraintName(tableName, columnName);
    }

    /**
     * Appends the createTable block for the main table, including schemaName when the
     * table is schema-qualified.
     *
     * <p>If the source table name contains a schema prefix, the generated Liquibase
     * createTable element includes the corresponding schemaName attribute. Otherwise,
     * the table is created without an explicit schema and Liquibase uses the default schema.
     *
     * <p>Generated stored columns are skipped here and must be appended separately
     * with raw SQL after the createTable block.
     *
     * @param builder XML builder
     * @param tableName physical table name without schema
     * @param table parsed table
     */
    private void appendMainTableCreateBlock(StringBuilder builder, String tableName, Table table) {
        String schemaName = extractSchemaNameFromTable(table.getName());

        if (schemaName == null || schemaName.isBlank()) {
            builder.append("""
                <createTable tableName="%s">
        """.formatted(tableName));
        } else {
            builder.append("""
                <createTable tableName="%s" schemaName="%s">
        """.formatted(
                    escapeXml(tableName),
                    escapeXml(schemaName)
            ));
        }

        for (Column column : table.getColumns()) {
            if (column.getGeneratedAs() != null && !column.getGeneratedAs().isBlank()) {
                continue;
            }

            appendMainTableColumn(builder, column);
        }

        builder.append("""
                </createTable>
        """);
    }

    /**
     * Appends the createTable block for the audit table.
     *
     * @param builder XML builder
     * @param auditTableName audit table name
     * @param table parsed table
     */
    private void appendAuditTableCreateBlock(StringBuilder builder, String auditTableName, Table table) {
        builder.append("""
                    <createTable tableName="%s" schemaName="audit">
            """.formatted(auditTableName));

        for (var column : table.getColumns()) {
            appendAuditTableColumn(builder, column);
        }

        appendAuditRevisionColumn(builder);
        appendAuditRevisionTypeColumn(builder);

        builder.append("""
                    </createTable>
            """);
    }

    /**
     * Appends one main-table column definition.
     *
     * @param builder XML builder
     * @param column parsed column
     */
    private void appendMainTableColumn(StringBuilder builder, Column column) {
        String columnName = normalizeIdentifier(column.getName());
        String columnType = normalizeLiquibaseType(column.getSqlType());
        boolean nullable = column.isNullable();
        boolean primaryKey = column.isPrimaryKey();
        boolean unique = column.isUnique();
        boolean autoIncrement = column.isIdentity();
        String primaryKeyConstraintName = resolvePrimaryKeyConstraintName(column);

        String defaultAttribute = buildDefaultValueAttribute(column);

        boolean hasConstraints = primaryKey || unique || !nullable;

        builder.append("            <column name=\"")
                .append(escapeXml(columnName))
                .append("\" type=\"")
                .append(escapeXml(columnType))
                .append("\"");

        if (autoIncrement) {
            builder.append(" autoIncrement=\"true\"");
        }

        builder.append(defaultAttribute);

        if (!hasConstraints) {
            builder.append("/>\n");
            return;
        }

        builder.append(">\n");

        builder.append("                <constraints");

        if (primaryKey) {
            builder.append(" primaryKey=\"true\"");

            if (primaryKeyConstraintName != null && !primaryKeyConstraintName.isBlank()) {
                builder.append(" primaryKeyName=\"")
                        .append(escapeXml(primaryKeyConstraintName))
                        .append("\"");
            }
        }

        if (unique) {
            builder.append(" unique=\"true\"");
        }

        if (!nullable) {
            builder.append(" nullable=\"false\"");
        }

        builder.append("/>\n");

        builder.append("            </column>\n");
    }

    /**
     * Resolves the primary key constraint name for the given column.
     *
     * <p>When the parser has preserved the original database constraint name,
     * that value is used. Otherwise, no explicit primary key name is emitted
     * inside the createTable column constraints block.
     *
     * @param column parsed column
     * @return resolved primary key constraint name or {@code null}
     */
    private String resolvePrimaryKeyConstraintName(Column column) {
        if (column == null
                || column.getPrimaryKeyConstraintName() == null
                || column.getPrimaryKeyConstraintName().isBlank()) {
            return null;
        }

        return normalizeIdentifier(column.getPrimaryKeyConstraintName());
    }



    /**
     * Appends one audit-table column definition.
     *
     * @param builder XML builder
     * @param column parsed column
     */
    private void appendAuditTableColumn(StringBuilder builder, Column column) {
        String columnName = normalizeIdentifier(column.getName());
        String columnType = normalizeLiquibaseType(column.getSqlType());

        builder.append("""
            <column name="%s" type="%s"/>
""".formatted(
                escapeXml(columnName),
                escapeXml(columnType)
        ));
    }

    /**
     * Appends the audit revision column.
     *
     * @param builder XML builder
     */
    private void appendAuditRevisionColumn(StringBuilder builder) {
        builder.append("""
                        <column name="rev" type="BIGINT">
                            <constraints nullable="false"/>
                        </column>
            """);
    }

    /**
     * Appends the audit revision type column.
     *
     * @param builder XML builder
     */
    private void appendAuditRevisionTypeColumn(StringBuilder builder) {
        builder.append("""
                        <column name="revtype" type="SMALLINT"/>
            """);
    }

    /**
     * Appends the composite primary key for the audit table based on the source table
     * primary key columns plus the revision column.
     *
     * <p>This method does not assume that every table uses a single {@code id} column.
     * Instead, it collects all primary key columns from the source table and appends
     * {@code rev} as the last column in the audit primary key definition.
     *
     * <p>Examples:
     * <ul>
     *     <li>{@code id} -> {@code id, rev}</li>
     *     <li>{@code company_id, status_id} -> {@code company_id, status_id, rev}</li>
     * </ul>
     *
     * @param builder XML builder
     * @param auditTableName audit table name
     * @param table source table definition
     */
    private void appendAuditPrimaryKeyBlock(StringBuilder builder, String auditTableName, Table table) {
        String primaryKeyColumns = table.getColumns().stream()
                .filter(Column::isPrimaryKey)
                .map(Column::getName)
                .map(this::normalizeIdentifier)
                .collect(java.util.stream.Collectors.joining(", "));

        if (primaryKeyColumns.isBlank()) {
            primaryKeyColumns = "rev";
        } else {
            primaryKeyColumns = primaryKeyColumns + ", rev";
        }

        builder.append("""
                <addPrimaryKey tableName="%s"
                               columnNames="%s"
                               constraintName="pk_%s"
                               schemaName="audit"/>
        """.formatted(
                auditTableName,
                primaryKeyColumns,
                auditTableName
        ));
    }

    /**
     * Appends the foreign key from audit table to audit.REVINFO.
     *
     * @param builder XML builder
     * @param tableName physical table name
     * @param auditTableName audit table name
     */
    private void appendAuditForeignKeyBlock(StringBuilder builder, String tableName, String auditTableName) {
        builder.append("""
                    <addForeignKeyConstraint baseTableName="%s"
                                             baseColumnNames="rev"
                                             constraintName="fk_%s_aud_revinfo"
                                             referencedTableName="REVINFO"
                                             referencedColumnNames="REV"
                                             baseTableSchemaName="audit"
                                             referencedTableSchemaName="audit"/>
            """.formatted(
                auditTableName,
                tableName
        ));
    }

    /**
     * Builds the create-table-and-audit changeSet id.
     *
     * @param tableName physical table name
     * @return changeSet id
     */
    private String buildCreateTableAndAuditChangeSetId(String tableName) {
        return "create-" + tableName.replace('_', '-') + "-table-and-audit";
    }

    /**
     * Escapes XML-sensitive text.
     *
     * @param value raw text
     * @return XML-safe text
     */
    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * Builds ordered changelog file names from parsed tables.
     *
     * @param tables parsed tables
     * @return ordered changelog file names
     */
    private List<String> buildOrderedChangelogFiles(List<Table> tables) {
        LiquibaseDependencyGraphBuilder dependencyGraphBuilder = new LiquibaseDependencyGraphBuilder();
        TableDependencySorter tableDependencySorter = new TableDependencySorter();

        Map<String, Set<String>> dependencyGraph = dependencyGraphBuilder.buildDependencyGraph(tables);
        List<String> orderedTableNames = tableDependencySorter.sortTables(dependencyGraph);

        return orderedTableNames.stream()
                .map(this::toTableChangelogFileName)
                .toList();
    }



    /**
     * Resolves the Liquibase migration root directory.
     *
     * @param outputDir generated project root directory
     * @return migration root directory path
     */
    private Path resolveMigrationRoot(String outputDir) {
        return GeneratorSupport.ensureDirectory(
                Path.of(outputDir, "src", "main", "resources", "db", "migration")
        );
    }

    /**
     * Resolves the default changelog directory.
     *
     * @param migrationRoot Liquibase migration root directory
     * @return changelog directory path
     */
    private Path resolveVersionDirectory(Path migrationRoot) {
        return GeneratorSupport.ensureDirectory(
                migrationRoot.resolve("changelogs").resolve(Constants.DEFAULT_VERSION)
        );
    }

    /**
     * Builds the Liquibase master changelog content.
     *
     * @return generated master changelog XML
     */
    private String buildMasterChangelogContent() {
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <databaseChangeLog
                    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.11.xsd">

                <include file="changelogs/%s/main.xml" relativeToChangelogFile="true" />

            </databaseChangeLog>
            """.formatted(Constants.DEFAULT_VERSION);
    }

    /**
     * Builds the version main changelog content.
     *
     * @param changelogFiles ordered included changelog files
     * @param extensionsRequired true when extensions.xml must be included
     * @return generated version main changelog XML
     */
    private String buildVersionMainContent(
            List<String> changelogFiles,
            boolean extensionsRequired
    ) {
        StringBuilder builder = new StringBuilder();

        builder.append("""
<?xml version="1.0" encoding="utf-8"?>
<databaseChangeLog
        logicalFilePath="%s/main.xml"
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.11.xsd">

""".formatted(Constants.DEFAULT_VERSION));

        builder.append("    <include file=\"audit.xml\" relativeToChangelogFile=\"true\" />\n");

        if (extensionsRequired) {
            builder.append("    <include file=\"extensions.xml\" relativeToChangelogFile=\"true\" />\n");
        }

        for (String changelogFile : changelogFiles) {
            builder.append("    <include file=\"")
                    .append(changelogFile)
                    .append("\" relativeToChangelogFile=\"true\" />\n");
        }

        builder.append("\n</databaseChangeLog>\n");

        return builder.toString();
    }

    /**
     * Converts a physical table name to a Liquibase changelog file name.
     *
     * <p>Examples:
     * <ul>
     *     <li>pep_schema.audit_trail -> auditTrail.xml</li>
     *     <li>company_status -> companyStatus.xml</li>
     * </ul>
     *
     * @param tableName physical table name
     * @return changelog file name
     */
    private String toTableChangelogFileName(String tableName) {
        String normalizedTableName = GeneratorSupport.normalizeTableName(tableName);
        return toCamelCase(normalizedTableName) + ".xml";
    }



    /**
     * Converts a snake_case table name to camelCase.
     *
     * @param rawName schema-free table name
     * @return camelCase file base name
     */
    private String toCamelCase(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "";
        }

        String[] parts = rawName.trim().toLowerCase().split("_+");
        if (parts.length == 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder(parts[0]);

        for (int index = 1; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty()) {
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
     * Builds the Liquibase audit changelog content.
     *
     * <p>This changelog creates only the {@code audit} schema and the Envers
     * infrastructure objects required for audit revision tracking.
     *
     * @param author liquibase author
     * @param tables parsed tables
     * @return generated audit changelog XML
     */
    private String buildAuditChangelogContent(String author, List<Table> tables) {
        String resolvedAuthor = resolveLiquibaseAuthor(author);
        String revisionSequenceSchema = resolveRevisionSequenceSchema(tables);

        return """
<?xml version="1.0" encoding="utf-8"?>
<databaseChangeLog
        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.11.xsd">

    <changeSet id="create_audit_schema" author="%s">
        <sql>
            CREATE SCHEMA IF NOT EXISTS audit;
        </sql>
    </changeSet>

    <changeSet id="create_revision_info_table" author="%s">
        <createTable tableName="REVINFO" schemaName="audit">
            <column name="REV" type="INT">
                <constraints primaryKey="true" nullable="false"/>
            </column>
            <column name="REVTSTMP" type="BIGINT"/>
        </createTable>
    </changeSet>

    <changeSet id="create_revinfo_sequence" author="%s">
        <createSequence sequenceName="revinfo_seq"
                        schemaName="%s"
                        startValue="1"
                        incrementBy="50"/>
    </changeSet>

</databaseChangeLog>
""".formatted(
                resolvedAuthor,
                resolvedAuthor,
                resolvedAuthor,
                revisionSequenceSchema
        );
    }

    /**
     * Resolves the schema where Hibernate Envers expects the default revision sequence.
     *
     * @param tables parsed tables
     * @return schema name for the Envers revision sequence
     */
    private String resolveRevisionSequenceSchema(List<Table> tables) {
        if (tables == null || tables.isEmpty()) {
            return "public";
        }

        for (Table table : tables) {
            if (table == null || table.getName() == null || table.getName().isBlank()) {
                continue;
            }

            String schemaName = extractSchemaNameFromTable(table.getName());

            if (schemaName != null && !schemaName.isBlank()) {
                return schemaName;
            }
        }

        return "public";
    }

    /**
     * Resolves the schema where PostgreSQL extensions and helper functions are created.
     *
     * @param tables parsed tables
     * @return schema name used for extensions and helper functions
     */
    private String resolveExtensionSchema(List<Table> tables) {
        String schemaName = resolveRevisionSequenceSchema(tables);

        if (schemaName.isBlank()) {
            return "public";
        }

        return schemaName;
    }

    /**
     * Resolves the Liquibase author.
     * Priority:
     * 1. CLI parameter (-a)
     * 2. Fallback to system user (no domain injection)
     *
     * @param author explicit Liquibase author value
     * @return Liquibase author
     */
    private String resolveLiquibaseAuthor(String author) {
        if (author != null && !author.isBlank()) {
            return author.trim();
        }

        String systemUserName = System.getProperty("user.name");

        if (systemUserName == null || systemUserName.isBlank()) {
            return "system";
        }

        return systemUserName.trim();
    }


    /**
     * Builds the Liquibase default value attribute based on the parsed SQL default value.
     *
     * @param column parsed column
     * @return Liquibase default attribute or empty string when no default exists
     */
    private String buildDefaultValueAttribute(Column column) {
        String defaultValue = column.getDefaultValue();

        if (defaultValue == null || defaultValue.isBlank()) {
            return "";
        }

        String trimmedDefaultValue = defaultValue.trim();
        String normalized = trimmedDefaultValue.toLowerCase(java.util.Locale.ROOT);

        if (normalized.equals("'::charactervarying'::character varying")
                || normalized.equals("'::charactervarying'::charactervarying")) {
            return " defaultValue=\"\"";
        }

        if (normalized.matches("^'.*'::jsonb$")
                || normalized.matches("^'.*'::json$")) {
            return " defaultValueComputed=\"" + escapeXml(trimmedDefaultValue) + "\"";
        }

        if (normalized.matches("^\\[.*]::jsonb$")
                || normalized.matches("^\\{.*}::jsonb$")
                || normalized.matches("^\\[.*]::json$")
                || normalized.matches("^\\{.*}::json$")) {
            String castType = normalized.endsWith("::jsonb") ? "jsonb" : "json";
            String literalValue = trimmedDefaultValue.substring(0, trimmedDefaultValue.lastIndexOf("::"));
            return " defaultValueComputed=\"'" + escapeXml(literalValue) + "'::" + castType + "\"";
        }

        if (normalized.equals("true") || normalized.equals("false")) {
            return " defaultValueBoolean=\"" + normalized + "\"";
        }

        if (normalized.matches("-?\\d+(\\.\\d+)?")) {
            return " defaultValueNumeric=\"" + normalized + "\"";
        }

        if (normalized.matches("^'?[^']*'?::(charactervarying|character varying|varchar|text)$")) {
            String literalValue = extractStringLiteralValue(trimmedDefaultValue);
            return " defaultValue=\"" + escapeXml(literalValue) + "\"";
        }

        if (trimmedDefaultValue.matches("^'.*'$")) {
            String literalValue = trimmedDefaultValue.substring(1, trimmedDefaultValue.length() - 1);
            return " defaultValue=\"" + escapeXml(literalValue) + "\"";
        }

        if (normalized.contains("(")
                || normalized.equals("current_timestamp")
                || normalized.equals("current_date")
                || normalized.equals("current_time")
                || normalized.equals("localtimestamp")
                || normalized.equals("localtime")
                || column.isDefaultExpression()) {
            return " defaultValueComputed=\"" + escapeXml(trimmedDefaultValue) + "\"";
        }

        return " defaultValue=\"" + escapeXml(trimmedDefaultValue) + "\"";
    }

    /**
     * Extracts the literal string value from a PostgreSQL text/varchar cast default.
     *
     * @param trimmedDefaultValue raw trimmed SQL default value
     * @return extracted literal value
     */
    private String extractStringLiteralValue(String trimmedDefaultValue) {
        String literalValue = trimmedDefaultValue
                .replaceFirst("(?i)::(charactervarying|character varying|varchar|text)$", "")
                .trim();

        if (literalValue.startsWith("'") && literalValue.endsWith("'") && literalValue.length() >= 2) {
            literalValue = literalValue.substring(1, literalValue.length() - 1);
        }

        if (literalValue.equals("::charactervarying")) {
            return "";
        }

        return literalValue;
    }

    /**
     * Resolves the referenced column name for a foreign key.
     *
     * @param column parsed foreign key column
     * @return referenced column name
     */
    private String resolveReferencedColumnName(Column column) {
        if (column.getReferencedColumn() != null && !column.getReferencedColumn().isBlank()) {
            return normalizeIdentifier(column.getReferencedColumn());
        }

        return "id";
    }

    /**
     * Extracts the schema name from a schema-qualified table reference.
     *
     * @param qualifiedTableName schema-qualified or plain table name
     * @return schema name or null
     */
    private String extractSchemaName(String qualifiedTableName) {
        if (qualifiedTableName == null || qualifiedTableName.isBlank()) {
            return null;
        }

        String trimmedTableName = qualifiedTableName.trim();
        int dotIndex = trimmedTableName.lastIndexOf('.');

        if (dotIndex <= 0) {
            return null;
        }

        return trimmedTableName.substring(0, dotIndex);
    }

    /**
     * Extracts the physical table name without schema.
     *
     * @param qualifiedTableName schema-qualified or plain table name
     * @return plain table name
     */
    private String extractTableNameOnly(String qualifiedTableName) {
        return GeneratorSupport.normalizeTableName(qualifiedTableName);
    }

    /**
     * Builds the foreign key constraint name.
     *
     * @param tableName physical table name
     * @param columnName physical column name
     * @return foreign key constraint name
     */
    private String buildForeignKeyConstraintName(String tableName, String columnName) {
        return "fk_" + tableName + "_" + columnName;
    }

    /**
     * Builds the baseTableSchemaName XML attribute for a schema-qualified table name.
     *
     * <p>When the provided table name contains a schema prefix, this method returns
     * the corresponding Liquibase XML attribute fragment. When no schema exists,
     * it returns an empty string.
     *
     * <p>Examples:
     * <ul>
     *     <li>pep_schema.company_status -> {@code  baseTableSchemaName="pep_schema"}</li>
     *     <li>company_status -> empty string</li>
     * </ul>
     *
     * @param tableName raw table name, optionally schema-qualified
     * @return formatted XML attribute fragment or empty string when no schema exists
     */
    private String buildBaseTableSchemaAttribute(String tableName) {
        String schemaName = extractSchemaName(tableName);

        if (schemaName == null || schemaName.isBlank()) {
            return "";
        }

        return " baseTableSchemaName=\"" + escapeXml(schemaName) + "\"";
    }

    /**
     * Builds the referencedTableSchemaName attribute when a referenced schema exists.
     *
     * @param referencedSchemaName referenced schema name
     * @return formatted XML attribute fragment or empty string
     */
    private String buildReferencedTableSchemaAttribute(String referencedSchemaName) {
        if (referencedSchemaName == null || referencedSchemaName.isBlank()) {
            return "";
        }

        return " referencedTableSchemaName=\"" + escapeXml(referencedSchemaName) + "\"";
    }

    /**
     * Builds the onDelete attribute when present.
     *
     * @param column parsed foreign key column
     * @return formatted XML attribute fragment or empty string
     */
    private String buildOnDeleteAttribute(Column column) {
        if (column.getOnDelete() == null || column.getOnDelete().isBlank()) {
            return "";
        }

        return " onDelete=\"" + escapeXml(column.getOnDelete()) + "\"";
    }

    /**
     * Normalizes a SQL identifier by trimming whitespace and removing surrounding double quotes.
     *
     * @param identifier raw SQL identifier
     * @return normalized identifier without surrounding double quotes
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
     * Appends table-level unique constraints for the main table, including schemaName
     * when the table is schema-qualified.
     *
     * <p>If the source table belongs to a specific schema, the generated
     * addUniqueConstraint element includes the corresponding schemaName attribute.
     * Otherwise, Liquibase uses the default schema.
     *
     * @param builder XML builder
     * @param tableName physical table name without schema
     * @param table parsed table
     */
    private void appendMainTableUniqueConstraints(StringBuilder builder, String tableName, Table table) {
        if (table.getUniqueConstraints() == null || table.getUniqueConstraints().isEmpty()) {
            return;
        }

        String schemaName = extractSchemaNameFromTable(table.getName());

        for (var unique : table.getUniqueConstraints()) {
            if (unique.getColumns() == null || unique.getColumns().isEmpty()) {
                continue;
            }

            String columnNames = unique.getColumns().stream()
                    .map(this::normalizeIdentifier)
                    .collect(java.util.stream.Collectors.joining(", "));

            String constraintName = unique.getName();

            builder.append("        <addUniqueConstraint tableName=\"")
                    .append(escapeXml(tableName))
                    .append("\"");

            if (schemaName != null && !schemaName.isBlank()) {
                builder.append(" schemaName=\"")
                        .append(escapeXml(schemaName))
                        .append("\"");
            }

            builder.append(" columnNames=\"")
                    .append(escapeXml(columnNames))
                    .append("\"");

            if (constraintName != null && !constraintName.isBlank()) {
                builder.append(" constraintName=\"")
                        .append(escapeXml(constraintName))
                        .append("\"");
            }

            builder.append("/>\n");
        }
    }


    /**
     * Builds the onUpdate attribute when present.
     *
     * @param column parsed foreign key column
     * @return formatted XML attribute fragment or empty string
     */
    private String buildOnUpdateAttribute(Column column) {
        if (column.getOnUpdate() == null || column.getOnUpdate().isBlank()) {
            return "";
        }

        return " onUpdate=\"" + escapeXml(column.getOnUpdate()) + "\"";
    }


    /**
     * Extracts the schema name from a schema-qualified table name.
     *
     * <p>If the provided table name contains a schema prefix, this method returns
     * the part before the last dot. If the table name is null, blank, or does not
     * contain a valid schema qualifier, it returns {@code null}.
     *
     * <p>Examples:
     * <ul>
     *     <li>{@code pep_schema.company_status} -> {@code pep_schema}</li>
     *     <li>{@code company_status} -> {@code null}</li>
     * </ul>
     *
     * @param tableName raw table name, optionally schema-qualified
     * @return schema name or {@code null} when no schema exists
     */
    private String extractSchemaNameFromTable(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return null;
        }

        int dotIndex = tableName.lastIndexOf('.');
        if (dotIndex <= 0) {
            return null;
        }

        return tableName.substring(0, dotIndex);
    }

    /**
     * Appends index definitions for the main table, including schemaName when the
     * table is schema-qualified.
     *
     * @param builder XML builder
     * @param tableName physical table name without schema
     * @param table parsed table
     */
    private void appendMainTableIndexes(StringBuilder builder, String tableName, Table table) {
        if (table.getIndexes() == null || table.getIndexes().isEmpty()) {
            return;
        }

        String schemaName = extractSchemaNameFromTable(table.getName());
        String qualifiedTableName = buildQualifiedTableName(table.getName());

        for (IndexDefinition index : table.getIndexes()) {
            if (index == null || index.getColumns() == null || index.getColumns().isEmpty()) {
                continue;
            }

            boolean requiresRawSql = requiresRawSqlIndexDefinition(index);

            if (requiresRawSql) {
                appendRawSqlIndex(builder, index, qualifiedTableName, resolveExtensionSchema(List.of(table)));
                continue;
            }

            appendLiquibaseCreateIndex(builder, index, tableName, schemaName);
        }
    }

    private boolean requiresRawSqlIndexDefinition(IndexDefinition index) {
        if (index.getWhereClause() != null && !index.getWhereClause().isBlank()) {
            return true;
        }

        return index.getColumns().stream()
                .filter(Objects::nonNull)
                .anyMatch(this::requiresRawSqlIndex);
    }

    private void appendRawSqlIndex(
            StringBuilder builder,
            IndexDefinition index,
            String qualifiedTableName,
            String extensionSchema
    ) {
        String columnList = index.getColumns().stream()
                .filter(Objects::nonNull)
                .map(columnExpression -> toIndexSqlColumnExpression(columnExpression, extensionSchema))
                .filter(columnExpression -> !columnExpression.isBlank())
                .collect(java.util.stream.Collectors.joining(", "));

        if (columnList.isBlank()) {
            return;
        }

        builder.append("""
        <sql><![CDATA[
            CREATE %sINDEX %s ON %s USING %s (%s)%s;
        ]]></sql>
""".formatted(
                index.isUnique() ? "UNIQUE " : "",
                index.getName(),
                qualifiedTableName,
                resolveIndexMethod(index),
                columnList,
                buildIndexWhereClause(index)
        ));
    }

    private void appendLiquibaseCreateIndex(
            StringBuilder builder,
            IndexDefinition index,
            String tableName,
            String schemaName
    ) {
        builder.append("        <createIndex tableName=\"")
                .append(escapeXml(tableName))
                .append("\"");

        if (schemaName != null && !schemaName.isBlank()) {
            builder.append(" schemaName=\"")
                    .append(escapeXml(schemaName))
                    .append("\"");
        }

        builder.append(" indexName=\"")
                .append(escapeXml(index.getName()))
                .append("\"");

        if (index.isUnique()) {
            builder.append(" unique=\"true\"");
        }

        builder.append(">\n");

        for (String columnName : index.getColumns()) {
            String normalizedColumnName = normalizeIndexColumnName(columnName);

            if (normalizedColumnName.isBlank()) {
                continue;
            }

            builder.append("            <column name=\"")
                    .append(escapeXml(normalizedColumnName))
                    .append("\"/>\n");
        }

        builder.append("        </createIndex>\n");
    }

    private String resolveIndexMethod(IndexDefinition index) {
        if (index.getUsingMethod() != null && !index.getUsingMethod().isBlank()) {
            return index.getUsingMethod().trim();
        }

        return resolveIndexMethod(index.getColumns());
    }

    private String buildIndexWhereClause(IndexDefinition index) {
        if (index.getWhereClause() == null || index.getWhereClause().isBlank()) {
            return "";
        }

        return " WHERE " + index.getWhereClause().trim();
    }

    /**
     * Resolves the PostgreSQL index method from index column expressions.
     *
     * @param columnNames raw index column names or expressions
     * @return PostgreSQL index method
     */
    private String resolveIndexMethod(List<String> columnNames) {
        if (columnNames == null || columnNames.isEmpty()) {
            return "btree";
        }

        boolean usesTrigramOperatorClass = columnNames.stream()
                .filter(Objects::nonNull)
                .map(columnName -> columnName.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(columnName -> columnName.contains("gin_trgm_ops")
                        || columnName.contains("gist_trgm_ops"));

        if (usesTrigramOperatorClass) {
            return "gin";
        }

        return "btree";
    }

    /**
     * Determines whether an index column requires raw SQL generation.
     *
     * @param columnName raw index column name or expression
     * @return true when the index cannot be represented safely with Liquibase createIndex
     */
    private boolean requiresRawSqlIndex(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            return false;
        }

        String normalizedColumnName = columnName.toLowerCase(java.util.Locale.ROOT).trim();

        return isQuotedIdentifier(columnName)
                || normalizedColumnName.contains("gin_trgm_ops")
                || normalizedColumnName.contains("gist_trgm_ops")
                || normalizedColumnName.contains("(")
                || normalizedColumnName.contains(")")
                || normalizedColumnName.matches(".*\\s+(asc|desc)$");
    }


    /**
     * Normalizes an index column name by removing optional ASC or DESC direction.
     *
     * @param columnName raw index column name
     * @return normalized index column name without direction
     */
    private String normalizeIndexColumnName(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            return "";
        }

        String normalizedColumnName = columnName.trim()
                .replaceFirst("(?i)\\s*DESC$", "")
                .replaceFirst("(?i)\\s*ASC$", "")
                .trim();

        return normalizeIdentifier(normalizedColumnName);
    }

    /**
     * Checks whether the provided SQL identifier is explicitly quoted.
     *
     * @param identifier raw SQL identifier
     * @return true when the identifier is surrounded by double quotes
     */
    private boolean isQuotedIdentifier(String identifier) {
        if (identifier == null) {
            return false;
        }

        String trimmedIdentifier = identifier.trim();

        return trimmedIdentifier.length() >= 2
                && trimmedIdentifier.startsWith("\"")
                && trimmedIdentifier.endsWith("\"");
    }

    /**
     * Converts a raw index column identifier to the SQL expression that should be
     * written inside a raw CREATE INDEX statement.
     *
     * @param rawIdentifier raw SQL identifier from the parsed index definition
     * @return SQL-safe column expression for the index body
     */
    private String toIndexSqlColumnExpression(String rawIdentifier, String extensionSchema) {
        String normalizedIdentifier = normalizeIdentifier(rawIdentifier);

        if (normalizedIdentifier.isBlank()) {
            return "";
        }

        if (isQuotedIdentifier(rawIdentifier)) {
            return "\"" + normalizedIdentifier + "\"";
        }

        String expression = normalizedIdentifier
                .replaceAll("(?i)\\)\\s*(gin_trgm_ops|gist_trgm_ops)$", ") $1");

        if (extensionSchema != null && !extensionSchema.isBlank()) {
            expression = expression.replaceAll(
                    "(?i)(?<![\\w.])f_unaccent_ci\\s*\\(",
                    extensionSchema + ".f_unaccent_ci("
            );
        }

        return expression;
    }



    private static final Map<String, String> LIQUIBASE_TYPE_MAPPINGS = Map.ofEntries(
            Map.entry("bigserial", "BIGINT"),
            Map.entry("int8", "BIGINT"),

            Map.entry("serial", "INTEGER"),
            Map.entry("int4", "INTEGER"),

            Map.entry("smallserial", "SMALLINT"),
            Map.entry("int2", "SMALLINT"),

            Map.entry("float8", "DOUBLE"),
            Map.entry("float4", "REAL"),

            Map.entry("bool", "BOOLEAN"),

            Map.entry("bpchar", "CHAR"),
            Map.entry("char", "CHAR"),
            Map.entry("character", "CHAR"),

            Map.entry("text", "TEXT")
    );

    /**
     * Normalizes a PostgreSQL SQL type into a Liquibase-safe type.
     *
     * @param sqlType raw SQL type
     * @return Liquibase-safe normalized type
     */
    private String normalizeLiquibaseType(String sqlType) {
        if (sqlType == null || sqlType.isBlank()) {
            return "";
        }

        String normalized = sqlType.trim().toLowerCase(Locale.ROOT);

        String mappedType = LIQUIBASE_TYPE_MAPPINGS.get(normalized);

        if (mappedType != null) {
            return mappedType;
        }

        if (normalized.startsWith("bpchar(")) {
            return normalized.replaceFirst("bpchar", "char").toUpperCase();
        }

        if (normalized.startsWith("char(")) {
            return normalized.toUpperCase();
        }

        if (normalized.startsWith("character(")) {
            return normalized.replaceFirst("character", "char").toUpperCase();
        }

        if (normalized.startsWith("character varying")) {
            return normalized.replaceFirst("character varying", "varchar").toUpperCase();
        }

        if (normalized.startsWith("charactervarying")) {
            return normalized.replaceFirst("charactervarying", "varchar").toUpperCase();
        }

        if (normalized.startsWith("timestamp")) {
            return "TIMESTAMP";
        }

        return normalized.toUpperCase();
    }


    /**
     * Appends raw SQL statements for generated stored columns after the main table
     * has been created.
     *
     * <p>Liquibase does not support PostgreSQL generated stored columns directly
     * inside createTable, so they are emitted as ALTER TABLE statements.
     *
     * @param builder XML builder
     * @param tableName physical table name without schema
     * @param table parsed table
     */
    private void appendGeneratedStoredColumnsBlock(StringBuilder builder, String tableName, Table table) {
        String schemaName = extractSchemaNameFromTable(table.getName());
        String qualifiedTableName = (schemaName == null || schemaName.isBlank())
                ? tableName
                : schemaName + "." + tableName;

        for (Column column : table.getColumns()) {
            if (column.getGeneratedAs() == null || column.getGeneratedAs().isBlank()) {
                continue;
            }

            String columnName = normalizeIdentifier(column.getName());
            String columnType = normalizeLiquibaseType(column.getSqlType());
            String generatedClause = column.getGeneratedAs().trim();

            builder.append("""
                <sql><![CDATA[
                    ALTER TABLE %s
                    ADD COLUMN %s %s %s;
                ]]></sql>
        """.formatted(
                    qualifiedTableName,
                    columnName,
                    columnType,
                    generatedClause
            ));
        }
    }
}