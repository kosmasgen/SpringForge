package com.sqldomaingen.util;

import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.Table;

import java.util.List;
import java.util.Objects;

/**
 * Utility class for resolving primary key metadata used by generators.
 *
 * <p>This class centralizes logic related to:
 * <ul>
 *     <li>detecting whether a table has a composite primary key</li>
 *     <li>collecting primary key columns</li>
 *     <li>resolving the generated primary key type</li>
 *     <li>resolving required import lines for primary key types</li>
 *     <li>building composite key class names</li>
 * </ul>
 *
 * <p>This class is intentionally generator-focused and does not modify table metadata.
 */
public final class PrimaryKeySupport {

    /**
     * Prevents instantiation.
     */
    private PrimaryKeySupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns true when the table has more than one primary key column.
     *
     * @param table source table metadata
     * @return true when the table uses composite primary key
     */
    public static boolean hasCompositePrimaryKey(Table table) {
        validateTable(table);

        return getPrimaryKeyColumns(table).size() > 1;
    }


    /**
     * Returns all primary key columns of the table in declaration order.
     *
     * @param table source table metadata
     * @return ordered list of primary key columns
     */
    public static List<Column> getPrimaryKeyColumns(Table table) {
        validateTable(table);

        return table.getColumns().stream()
                .filter(Objects::nonNull)
                .filter(Column::isPrimaryKey)
                .toList();
    }

    /**
     * Returns the single primary key column.
     *
     * @param table source table metadata
     * @return single primary key column
     */
    public static Column getSinglePrimaryKeyColumn(Table table) {
        List<Column> primaryKeyColumns = getPrimaryKeyColumns(table);

        if (primaryKeyColumns.isEmpty()) {
            throw new IllegalStateException("No primary key found for table: " + table.getName());
        }

        if (primaryKeyColumns.size() > 1) {
            throw new IllegalStateException("Composite primary key found for table: " + table.getName());
        }

        return primaryKeyColumns.getFirst();
    }

    /**
     * Builds the composite key class name for the given entity.
     *
     * @param entityName entity simple name
     * @return composite key class name
     */
    public static String buildCompositeKeyClassName(String entityName) {
        String normalizedEntityName = requireNonBlank(entityName, "entityName must not be blank");
        return normalizedEntityName + "Key";
    }

    /**
     * Resolves the primary key type reference for generator code.
     *
     * <p>For composite keys, this resolves to {@code EntityNameKey} plus import line.
     * For single-column keys, this resolves from the Java type of the PK column.
     *
     * @param table source table metadata
     * @param entityName entity simple name
     * @param entityPackage entity package name
     * @return resolved type reference
     */
    public static TypeRef resolvePrimaryKeyTypeRef(
            Table table,
            String entityName,
            String entityPackage
    ) {
        validateTable(table);
        String normalizedEntityName = requireNonBlank(entityName, "entityName must not be blank");
        String normalizedEntityPackage = requireNonBlank(entityPackage, "entityPackage must not be blank");

        if (hasCompositePrimaryKey(table)) {
            String compositeKeyClassName = buildCompositeKeyClassName(normalizedEntityName);
            return new TypeRef(
                    compositeKeyClassName,
                    "import " + normalizedEntityPackage + "." + compositeKeyClassName + ";"
            );
        }

        Column primaryKeyColumn = getSinglePrimaryKeyColumn(table);
        return resolveScalarPrimaryKeyTypeRef(primaryKeyColumn);
    }







    /**
     * Resolves the simple Java type for a primary key column.
     *
     * @param column primary key column
     * @return simple Java type name
     */
    public static String resolvePrimaryKeyColumnSimpleType(Column column) {
        if (column == null) {
            throw new IllegalArgumentException("column must not be null");
        }

        String rawJavaType = GeneratorSupport.trimToEmpty(column.getJavaType());

        if (rawJavaType.isEmpty()) {
            return "Long";
        }

        if ("long".equalsIgnoreCase(rawJavaType) || "java.lang.Long".equals(rawJavaType)) {
            return "Long";
        }

        if ("int".equalsIgnoreCase(rawJavaType) || "java.lang.Integer".equals(rawJavaType)) {
            return "Integer";
        }

        if ("short".equalsIgnoreCase(rawJavaType) || "java.lang.Short".equals(rawJavaType)) {
            return "Short";
        }

        if ("byte".equalsIgnoreCase(rawJavaType) || "java.lang.Byte".equals(rawJavaType)) {
            return "Byte";
        }

        if ("boolean".equalsIgnoreCase(rawJavaType) || "java.lang.Boolean".equals(rawJavaType)) {
            return "Boolean";
        }

        return JavaTypeSupport.resolveSimpleType(rawJavaType);
    }

    /**
     * Resolves the import line required by a primary key column type.
     *
     * @param column primary key column
     * @return import line or null when no import is required
     */
    public static String resolvePrimaryKeyColumnImportLine(Column column) {
        if (column == null) {
            throw new IllegalArgumentException("column must not be null");
        }

        String rawJavaType = GeneratorSupport.trimToEmpty(column.getJavaType());

        if (rawJavaType.isEmpty()) {
            return null;
        }

        return switch (rawJavaType) {
            case "BigDecimal" -> "import java.math.BigDecimal;";
            case "BigInteger" -> "import java.math.BigInteger;";
            case "UUID" -> "import java.util.UUID;";
            case "LocalDate" -> "import java.time.LocalDate;";
            case "LocalDateTime" -> "import java.time.LocalDateTime;";
            case "LocalTime" -> "import java.time.LocalTime;";
            default -> JavaTypeSupport.resolveImportLine(rawJavaType);
        };
    }

    /**
     * Resolves a scalar primary key type reference from a single PK column.
     *
     * @param primaryKeyColumn single primary key column
     * @return resolved type reference
     */
    private static TypeRef resolveScalarPrimaryKeyTypeRef(Column primaryKeyColumn) {
        String simpleType = resolvePrimaryKeyColumnSimpleType(primaryKeyColumn);
        String importLine = resolvePrimaryKeyColumnImportLine(primaryKeyColumn);

        return new TypeRef(simpleType, importLine);
    }

    /**
     * Validates table metadata.
     *
     * @param table source table metadata
     */
    private static void validateTable(Table table) {
        if (table == null) {
            throw new IllegalArgumentException("table must not be null");
        }

        if (table.getColumns() == null) {
            throw new IllegalArgumentException("table columns must not be null");
        }
    }

    /**
     * Returns the value when non-blank, otherwise throws.
     *
     * @param value source value
     * @param message exception message
     * @return validated value
     */
    private static String requireNonBlank(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }

        return value.trim();
    }

    /**
     * Holds resolved primary key type metadata for generated code.
     *
     * @param simpleName simple type name
     * @param importLine optional import line
     */
    public record TypeRef(String simpleName, String importLine) {
    }
}
