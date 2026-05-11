package com.sqldomaingen.parser;

import com.sqldomaingen.util.NamingConverter;
import com.sqldomaingen.util.TypeMapper;
import com.sqldomaingen.model.Column;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Log4j2
public class ColumnDefinition extends PostgreSQLBaseListener {

    private String columnName;
    private String sqlType;
    private String javaType;
    private int length;
    private boolean primaryKey = false;
    private boolean nullable = true;
    private boolean unique = false;
    private String defaultValue;
    private String checkConstraint;
    private String referencedTable;
    private String referencedColumn;
    private boolean foreignKey = false;
    private int precision;
    private int scale;
    private String onUpdate;
    private String onDelete;
    private String mappedBy;
    private String generatedAs;
    private boolean manyToMany = false;
    private boolean isIdentity = false;
    private String identityGeneration;
    private String primaryKeyConstraintName;
    private String foreignKeyConstraintName;

    /**
     * Builds a {@link ColumnDefinition} by walking the ANTLR parse context with this listener.
     * <p>
     * The resulting object contains:
     * <ul>
     *   <li>column name</li>
     *   <li>raw SQL type + extracted attributes (length/precision/scale)</li>
     *   <li>basic column constraints (PK/nullable/unique/default/check)</li>
     *   <li>FK metadata if the column contains REFERENCES</li>
     * </ul>
     *
     * @param ctx ANTLR column definition context
     * @return populated {@link ColumnDefinition}
     */
    public static ColumnDefinition fromContext(PostgreSQLParser.ColumnDefContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("ColumnDefContext is null");
        }

        log.debug("Parsing column definition: {}", ctx.getText());

        ColumnDefinition columnDefinition = new ColumnDefinition();
        ParseTreeWalker.DEFAULT.walk(columnDefinition, ctx);

        Column column = columnDefinition.toColumn();
        columnDefinition.javaType = TypeMapper.mapToJavaType(column);

        log.debug("Parsed column -> name='{}', sqlType='{}', javaType='{}', pk={}",
                columnDefinition.getColumnName(),
                columnDefinition.getSqlType(),
                columnDefinition.getJavaType(),
                columnDefinition.isPrimaryKey());

        return columnDefinition;
    }

    /**
     * Captures the column name when entering a column definition.
     */
    @Override
    public void enterColumnDef(PostgreSQLParser.ColumnDefContext ctx) {
        columnName = ctx.columnName().getText();
        log.debug("Column name extracted: {}", columnName);
    }

    /**
     * Captures the SQL data type and extracts size attributes.
     * <p>
     * Examples:
     * <ul>
     *   <li>VARCHAR(100) -> length=100</li>
     *   <li>DECIMAL(12,2) -> precision=12, scale=2</li>
     * </ul>
     */
    @Override
    public void enterDataType(PostgreSQLParser.DataTypeContext ctx) {
        if (this.sqlType != null && !this.sqlType.isBlank()) {
            return;
        }

        sqlType = ctx.getText().toUpperCase(java.util.Locale.ROOT);

        if ("BIGSERIAL".equals(sqlType) || "SERIAL".equals(sqlType) || "SMALLSERIAL".equals(sqlType)) {
            this.isIdentity = true;
            this.identityGeneration = "BY DEFAULT";
        }

        String baseSqlType = getBaseSqlType();

        if ("DECIMAL".equals(baseSqlType) || "NUMERIC".equals(baseSqlType)) {
            extractPrecisionAndScale(sqlType);
            this.length = 0;
        } else {
            this.length = extractLength(sqlType);
        }

        log.debug("SQL type extracted: '{}' | precision={} | scale={} | length={}",
                sqlType, precision, scale, length);
    }

    private void extractPrecisionAndScale(String typeText) {
        if (typeText == null || typeText.isBlank()) {
            this.precision = 0;
            this.scale = 0;
            return;
        }

        int open = typeText.indexOf('(');
        int close = typeText.indexOf(')', open + 1);

        if (open < 0 || close < 0 || close <= open + 1) {
            this.precision = 0;
            this.scale = 0;
            return;
        }

        try {
            String insideParentheses = typeText.substring(open + 1, close);
            String[] parts = insideParentheses.split(",");

            this.precision = parts.length >= 1 ? Integer.parseInt(parts[0].trim()) : 0;
            this.scale = parts.length >= 2 ? Integer.parseInt(parts[1].trim()) : 0;
        } catch (NumberFormatException exception) {
            log.warn("Invalid numeric precision/scale format in SQL type '{}'. Using precision=0, scale=0.", typeText);
            this.precision = 0;
            this.scale = 0;
        }
    }

    /**
     * Handles a column-level constraint and updates column metadata.
     *
     * @param ctx the constraint context
     */
    @Override
    public void enterConstraint(PostgreSQLParser.ConstraintContext ctx) {
        String rawConstraintText = ctx.getText();
        String normalizedConstraintText = rawConstraintText
                .toUpperCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", "");

        String constraintName = extractConstraintName(rawConstraintText);

        if (normalizedConstraintText.contains("PRIMARYKEY")) {
            this.primaryKey = true;
            this.nullable = false;

            if (constraintName != null && !constraintName.isBlank()) {
                this.primaryKeyConstraintName = constraintName;
            }

            log.info("Column '{}' marked as PRIMARY KEY (constraintName={})",
                    this.columnName,
                    this.primaryKeyConstraintName);
        }

        if (normalizedConstraintText.contains("NOTNULL")) {
            this.nullable = false;
            log.debug("Column '{}' marked as NOT NULL", this.columnName);
        }

        if (normalizedConstraintText.contains("UNIQUE")) {
            this.unique = true;
        }

        if (normalizedConstraintText.contains("MANYTOMANY")) {
            this.manyToMany = true;
        }

        if (normalizedConstraintText.contains("DEFAULT")) {
            this.defaultValue = extractDefaultValue(ctx);
        }

        if (normalizedConstraintText.contains("CHECK")) {
            this.checkConstraint = extractCheckConstraint(ctx);
        }

        if (normalizedConstraintText.contains("REFERENCES") || normalizedConstraintText.contains("FOREIGNKEY")) {
            this.foreignKey = true;

            if (constraintName != null && !constraintName.isBlank()) {
                this.foreignKeyConstraintName = constraintName;
            }

            extractForeignKeyDetails(ctx);
        }

        log.debug(
                "Constraints extracted for '{}' -> pk={}, pkName={}, nullable={}, unique={}, manyToMany={}, default={}, check={}, fk={}, fkName={}",
                this.columnName,
                this.primaryKey,
                this.primaryKeyConstraintName,
                this.nullable,
                this.unique,
                this.manyToMany,
                this.defaultValue,
                this.checkConstraint,
                this.foreignKey,
                this.foreignKeyConstraintName
        );
    }

    /**
     * Extracts an optional SQL constraint name from the raw constraint text.
     *
     * <p>This method supports compact parser text where whitespace may be removed,
     * for example:
     * <ul>
     *     <li>{@code CONSTRAINTpk_audit_trailPRIMARYKEY(id)}</li>
     *     <li>{@code CONSTRAINTfk_audit_trail_companyREFERENCESpep_schema.company(id)}</li>
     *     <li>{@code CONSTRAINT"fk audit trail company"REFERENCESpep_schema.company(id)}</li>
     * </ul>
     *
     * @param rawConstraintText raw parser constraint text
     * @return resolved constraint name or {@code null} when no explicit name exists
     */
    private String extractConstraintName(String rawConstraintText) {
        if (rawConstraintText == null || rawConstraintText.isBlank()) {
            return null;
        }

        String compactConstraintText = rawConstraintText.trim().replaceAll("\\s+", "");

        Matcher quotedMatcher = Pattern.compile("(?i)^CONSTRAINT\"([^\"]+)\"").matcher(compactConstraintText);
        if (quotedMatcher.find()) {
            return quotedMatcher.group(1).trim();
        }

        Matcher unquotedMatcher = Pattern.compile(
                "(?i)^CONSTRAINT([A-Za-z0-9_\\.]+?)(PRIMARYKEY|FOREIGNKEY|REFERENCES|UNIQUE|CHECK|NOTNULL|DEFAULT)"
        ).matcher(compactConstraintText);

        if (unquotedMatcher.find()) {
            return unquotedMatcher.group(1).trim();
        }

        return null;
    }

    /**
     * Returns the SQL base type without parameters (e.g. VARCHAR(20) -> VARCHAR).
     */
    public String getBaseSqlType() {
        if (sqlType == null || sqlType.isBlank()) {
            return "";
        }
        return sqlType.contains("(") ? sqlType.substring(0, sqlType.indexOf("(")) : sqlType;
    }

    @Override
    public void enterColumnTypeModifier(PostgreSQLParser.ColumnTypeModifierContext ctx) {
        if (ctx == null) {
            return;
        }

        String modifierText = ctx.getText(); // e.g. "(1000)" or "(10,2)"
        if (modifierText == null || modifierText.isBlank()) {
            return;
        }

        if (this.sqlType == null || this.sqlType.isBlank()) {
            this.sqlType = modifierText.toUpperCase();
        } else {
            this.sqlType = (this.sqlType + modifierText).toUpperCase();
        }

        // Recalculate length after appending modifier
        this.length = extractLength(this.sqlType);

        // If numeric/decimal precision-scale were not captured in enterDataType, recover them here
        String baseType = getBaseSqlType();
        if ((baseType.equals("DECIMAL") || baseType.equals("NUMERIC")) && modifierText.startsWith("(") && modifierText.endsWith(")")) {
            try {
                String inside = modifierText.substring(1, modifierText.length() - 1);
                String[] parts = inside.split(",");

                if (parts.length >= 1) {
                    this.precision = Integer.parseInt(parts[0].trim());
                }
                if (parts.length >= 2) {
                    this.scale = Integer.parseInt(parts[1].trim());
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid numeric precision/scale format in modifier '{}'", modifierText);
            }
        }

        log.debug("SQL type modifier extracted: '{}' | sqlType now='{}' | precision={} | scale={} | length={}",
                modifierText, this.sqlType, this.precision, this.scale, this.length);
    }

    @Override
    public void enterIdentityColumn(PostgreSQLParser.IdentityColumnContext ctx) {
        String text = ctx.getText().toUpperCase(java.util.Locale.ROOT);

        this.isIdentity = true;

        if (text.contains("BYDEFAULT")) {
            this.identityGeneration = "BY DEFAULT";
        } else if (text.contains("ALWAYS")) {
            this.identityGeneration = "ALWAYS";
        }

        log.info("Column '{}' marked as IDENTITY ({})",
                this.columnName,
                this.identityGeneration);
    }


    /**
     * Extracts the "length" argument from SQL types that include size (e.g. VARCHAR(255)).
     * Returns 0 when not available or not parseable.
     */
    private int extractLength(String typeText) {
        if (typeText == null || typeText.isBlank()) {
            return 0;
        }

        int open = typeText.indexOf('(');
        int close = typeText.indexOf(')', open + 1);

        if (open < 0 || close < 0 || close <= open + 1) {
            return 0;
        }

        try {
            String insideParentheses = typeText.substring(open + 1, close);
            String[] parts = insideParentheses.split(",");
            return Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid length format in SQL type '{}'. Returning 0 (no explicit length).", typeText);
            return 0;
        }
    }

    /**
     * Extracts the DEFAULT value from the constraint context.
     */
    private String extractDefaultValue(PostgreSQLParser.ConstraintContext ctx) {
        if (ctx.value() != null) {
            return ctx.value().getText().replaceAll("[\"']", "").trim();
        }
        return null;
    }

    /**
     * Extracts the CHECK constraint expression and normalizes basic operator spacing.
     */
    private String extractCheckConstraint(PostgreSQLParser.ConstraintContext ctx) {
        if (ctx.getText().contains("CHECK")) {
            int start = ctx.getText().indexOf("CHECK") + 5;
            int openParen = ctx.getText().indexOf("(", start);
            int closeParen = ctx.getText().lastIndexOf(")");

            if (openParen != -1 && closeParen != -1 && closeParen > openParen) {
                String constraint = ctx.getText().substring(openParen + 1, closeParen).trim();

                // Add spaces around common operators to make the expression readable.
                constraint = constraint.replaceAll("\\s*(>=|<=|>|<|=|AND|OR|BETWEEN)\\s*", " $1 ");

                return "(" + constraint + ")";
            }
        }
        return null;
    }

    /**
     * Extracts FK target schema/table/column from a REFERENCES clause.
     * <p>
     * This method preserves schema-qualified targets, e.g.
     * {@code REFERENCES config_schema.chamber(id)} so cross-schema foreign keys
     * are never downgraded to table-only names.
     */
    private void extractForeignKeyDetails(PostgreSQLParser.ConstraintContext ctx) {
        log.debug("Extracting FOREIGN KEY details from: {}", ctx.getText());

        String referencedTableName = null;
        String referencedSchemaName = null;
        String referencedColumnName = null;

        if (ctx.tableName() != null) {
            referencedTableName = ctx.tableName().getText().replace("\"", "").trim();
        } else {
            log.warn("FK extraction: tableName is null.");
        }

        if (ctx.schemaName() != null) {
            referencedSchemaName = ctx.schemaName().getText().replace("\"", "").trim();
        }

        if (ctx.columnName() != null) {
            referencedColumnName = ctx.columnName().getText().replace("\"", "").trim();
        } else {
            log.warn("FK extraction: columnName is null.");
        }

        if (referencedTableName != null && !referencedTableName.isBlank()) {
            referencedTable = referencedSchemaName != null && !referencedSchemaName.isBlank()
                    ? referencedSchemaName + "." + referencedTableName
                    : referencedTableName;
        } else {
            referencedTable = null;
        }

        referencedColumn = referencedColumnName;

        log.info("FK extracted -> referencedTable='{}', referencedColumn='{}'",
                referencedTable, referencedColumn);
    }

    @Override
    public void enterGeneratedColumn(PostgreSQLParser.GeneratedColumnContext ctx) {
        String generatedColumnText = ctx.getText();
        String normalizedGeneratedColumnText = generatedColumnText.toUpperCase(java.util.Locale.ROOT);

        if (normalizedGeneratedColumnText.contains("GENERATED")
                && normalizedGeneratedColumnText.contains("AS")
                && normalizedGeneratedColumnText.contains("STORED")) {
            String normalizedGeneratedAs = generatedColumnText
                    .replaceAll("(?i)generatedalwaysas", "GENERATED ALWAYS AS")
                    .replaceAll("(?i)generatedbydefaultas", "GENERATED BY DEFAULT AS")
                    .replaceAll("(?i)\\)stored", ") STORED")
                    .replaceAll("AS\\(", "AS (");

            this.generatedAs = normalizedGeneratedAs;

            log.info("Column '{}' marked as GENERATED STORED column: {}",
                    this.columnName,
                    this.generatedAs);
        }

        if (normalizedGeneratedColumnText.contains("IDENTITY")) {
            this.isIdentity = true;

            if (normalizedGeneratedColumnText.contains("BYDEFAULT")) {
                this.identityGeneration = "BY DEFAULT";
            } else if (normalizedGeneratedColumnText.contains("ALWAYS")) {
                this.identityGeneration = "ALWAYS";
            }

            log.debug("Column '{}' marked as IDENTITY ({})",
                    this.columnName,
                    this.identityGeneration);
        }
    }



    /**
     * Converts this definition to the internal {@link Column} model used by the generator.
     *
     * @return populated {@link Column}
     */
    public Column toColumn() {
        log.debug("Converting ColumnDefinition -> Column | name='{}'", this.columnName);

        Column column = new Column();
        column.setName(this.columnName);
        column.setFieldName(NamingConverter.toJavaFieldName(this.columnName));
        column.setJavaType(this.javaType);
        column.setSqlType(this.sqlType);
        column.setLength(this.length);
        column.setPrecision(this.precision);
        column.setScale(this.scale);
        column.setPrimaryKey(this.primaryKey);
        column.setPrimaryKeyConstraintName(this.primaryKeyConstraintName);
        column.setNullable(this.nullable);
        column.setDefaultValue(this.defaultValue);
        column.setUnique(this.unique);
        column.setManyToMany(this.manyToMany);
        column.setCheckConstraint(this.checkConstraint);
        column.setOnDelete(this.onDelete);
        column.setOnUpdate(this.onUpdate);
        column.setMappedBy(this.mappedBy);
        column.setIdentity(this.isIdentity);
        column.setIdentityGeneration(this.identityGeneration);
        column.setGeneratedAs(this.generatedAs);

        if (this.foreignKey) {
            column.setForeignKey(true);
            column.setForeignKeyConstraintName(this.foreignKeyConstraintName);
            column.setReferencedTable(this.referencedTable);
            column.setReferencedColumn(this.referencedColumn);

            log.debug("FK column mapped: '{}' -> {}.{} (fkConstraintName={}, mappedBy={})",
                    this.columnName,
                    this.referencedTable,
                    this.referencedColumn,
                    this.foreignKeyConstraintName,
                    this.mappedBy);

            log.debug("Column is a FOREIGN KEY: {} -> {}.{} (constraintName={})",
                    column.getName(),
                    column.getReferencedTable(),
                    column.getReferencedColumn(),
                    column.getForeignKeyConstraintName());
        }

        return column;
    }
}
