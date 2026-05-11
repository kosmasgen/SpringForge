package com.sqldomaingen.util;

import com.sqldomaingen.model.Column;
import lombok.extern.log4j.Log4j2;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class responsible for mapping SQL column types to Java types.
 */
@Log4j2
public class TypeMapper {


    private static final Map<String, String> sqlToJavaTypeMap = new HashMap<>();

    /**
     * Prevents instantiation of the utility class.
     */
    public TypeMapper() {
        throw new AssertionError("Utility class - instantiation not allowed.");
    }

    static {
        sqlToJavaTypeMap.put("BIGINT", "Long");
        sqlToJavaTypeMap.put("LONG", "Long");
        sqlToJavaTypeMap.put("INT", "Integer");
        sqlToJavaTypeMap.put("INTEGER", "Integer");
        sqlToJavaTypeMap.put("SMALLINT", "Short");
        sqlToJavaTypeMap.put("TINYINT", "Byte");

        sqlToJavaTypeMap.put("INT4", "Integer");
        sqlToJavaTypeMap.put("INT8", "Long");
        sqlToJavaTypeMap.put("INT2", "Short");

        sqlToJavaTypeMap.put("SERIAL4", "Integer");
        sqlToJavaTypeMap.put("SERIAL8", "Long");
        sqlToJavaTypeMap.put("SERIAL2", "Short");

        sqlToJavaTypeMap.put("SERIAL", "Integer");
        sqlToJavaTypeMap.put("BIGSERIAL", "Long");
        sqlToJavaTypeMap.put("SMALLSERIAL", "Short");

        sqlToJavaTypeMap.put("DECIMAL", Constants.JAVA_BIG_DECIMAL);
        sqlToJavaTypeMap.put("NUMERIC", Constants.JAVA_BIG_DECIMAL);
        sqlToJavaTypeMap.put("FLOAT", Constants.JAVA_BIG_DECIMAL);
        sqlToJavaTypeMap.put("DOUBLE", Constants.JAVA_BIG_DECIMAL);
        sqlToJavaTypeMap.put("DOUBLE PRECISION", Constants.JAVA_BIG_DECIMAL);
        sqlToJavaTypeMap.put("REAL", "Double");

        sqlToJavaTypeMap.put("CHAR", Constants.JAVA_STRING);
        sqlToJavaTypeMap.put("BPCHAR", Constants.JAVA_STRING);
        sqlToJavaTypeMap.put("VARCHAR", Constants.JAVA_STRING);
        sqlToJavaTypeMap.put("TEXT", Constants.JAVA_STRING);
        sqlToJavaTypeMap.put("JSON", Constants.JAVA_STRING);
        sqlToJavaTypeMap.put("JSONB", Constants.JAVA_STRING);

        sqlToJavaTypeMap.put("DATE", "java.time.LocalDate");
        sqlToJavaTypeMap.put("TIME", "java.time.LocalTime");
        sqlToJavaTypeMap.put("TIME WITHOUT TIME ZONE", "java.time.LocalTime");
        sqlToJavaTypeMap.put("TIME WITH TIME ZONE", "java.time.OffsetTime");
        sqlToJavaTypeMap.put("TIMESTAMP", Constants.JAVA_LOCAL_DATE_TIME);
        sqlToJavaTypeMap.put("TIMESTAMP WITHOUT TIME ZONE", Constants.JAVA_LOCAL_DATE_TIME);
        sqlToJavaTypeMap.put("TIMESTAMP WITH TIME ZONE", "java.time.OffsetDateTime");
        sqlToJavaTypeMap.put("TIMESTAMPTZ", "java.time.OffsetDateTime");
        sqlToJavaTypeMap.put("TIMETZ", "java.time.OffsetTime");
        sqlToJavaTypeMap.put("DATETIME", Constants.JAVA_LOCAL_DATE_TIME);

        sqlToJavaTypeMap.put("BLOB", "byte[]");
        sqlToJavaTypeMap.put("BYTEA", "byte[]");

        sqlToJavaTypeMap.put("BOOL", "Boolean");
        sqlToJavaTypeMap.put("BOOLEAN", "Boolean");
        sqlToJavaTypeMap.put("BIT", "Boolean");

        sqlToJavaTypeMap.put("UUID", "java.util.UUID");
        sqlToJavaTypeMap.put("ARRAY", "java.util.List<?>");
        sqlToJavaTypeMap.put("MONEY", Constants.JAVA_BIG_DECIMAL);
        sqlToJavaTypeMap.put("ENUM", Constants.JAVA_STRING);
        sqlToJavaTypeMap.put("CITEXT", Constants.JAVA_STRING);
        sqlToJavaTypeMap.put("TSVECTOR", Constants.JAVA_STRING);
        sqlToJavaTypeMap.put("INET", Constants.JAVA_STRING);
        sqlToJavaTypeMap.put("CIDR", Constants.JAVA_STRING);
        sqlToJavaTypeMap.put("MACADDR", Constants.JAVA_STRING);
        sqlToJavaTypeMap.put("XML", Constants.JAVA_STRING);
        sqlToJavaTypeMap.put("PG_LSN", Constants.JAVA_STRING);
        sqlToJavaTypeMap.put("VARBIT", Constants.JAVA_STRING);
        sqlToJavaTypeMap.put("TRIGGER", Constants.JAVA_STRING);
        sqlToJavaTypeMap.put("INTERVAL", Constants.JAVA_STRING);
    }


    /**
     * Maps a column definition to a Java type.
     *
     * @param column the column metadata
     * @return the Java type
     */
    public static String mapToJavaType(Column column) {
        if (column == null) {
            throw new IllegalArgumentException("Column cannot be null");
        }

        String sqlType = column.getSqlType();
        if (sqlType == null || sqlType.isBlank()) {
            throw new IllegalArgumentException("SQL type cannot be null or empty");
        }

        String normalizedSqlType = sqlType.trim().toUpperCase().replaceAll("\\s+", " ");
        String baseType = normalizeBaseType(normalizedSqlType);

        if ("NUMERIC".equals(baseType) || "DECIMAL".equals(baseType)) {
            return mapNumericColumn(column, normalizedSqlType);
        }

        if ("CHAR".equals(baseType) || "BPCHAR".equals(baseType)) {
            int length = column.getLength();

            if (length == 0 && normalizedSqlType.contains("(") && normalizedSqlType.contains(")")) {
                try {
                    String charArguments = normalizedSqlType.substring(
                            normalizedSqlType.indexOf('(') + 1,
                            normalizedSqlType.indexOf(')')
                    );
                    length = Integer.parseInt(charArguments.trim());
                } catch (Exception exception) {
                    log.warn("Failed to parse char length from sqlType '{}'", column.getSqlType(), exception);
                }
            }

            return length == 1 ? "Character" : Constants.JAVA_STRING;
        }

        String javaType = sqlToJavaTypeMap.getOrDefault(baseType, Constants.JAVA_STRING);

        if (!sqlToJavaTypeMap.containsKey(baseType)) {
            log.warn("No specific mapping found for SQL type '{}'. Defaulting to 'String'.", baseType);
        } else {
            log.debug("Mapping SQL type '{}' to Java type '{}'", baseType, javaType);
        }

        return javaType;
    }

    /**
     * Returns true when the provided SQL type represents a JSON column.
     *
     * @param sqlType the SQL type
     * @return true when the type is JSON or JSONB
     */
    public static boolean isJsonType(String sqlType) {
        if (sqlType == null || sqlType.isBlank()) {
            return false;
        }

        String baseType = normalizeBaseType(sqlType);
        return "JSON".equals(baseType) || "JSONB".equals(baseType);
    }

    /**
     * Returns true when the provided column represents a JSON column.
     *
     * @param column the column metadata
     * @return true when the column is JSON or JSONB
     */
    public static boolean isJsonType(Column column) {
        if (column == null || column.getSqlType() == null || column.getSqlType().isBlank()) {
            return false;
        }

        return isJsonType(column.getSqlType());
    }

    /**
     * Returns the SQL column definition to be used in JPA for JSON columns.
     *
     * @param column the column metadata
     * @return the normalized SQL column definition
     */
    public static String getJsonColumnDefinition(Column column) {
        if (!isJsonType(column)) {
            throw new IllegalArgumentException("Column is not a JSON type");
        }

        String baseType = normalizeBaseType(column.getSqlType());
        return baseType.toLowerCase();
    }

    /**
     * Normalizes an SQL type to its base form without losing important multi-word types.
     *
     * @param sqlType the SQL type
     * @return the normalized base SQL type
     */
    private static String normalizeBaseType(String sqlType) {
        String normalizedSqlType = sqlType.trim().toUpperCase().replaceAll("\\s+", " ");

        // Remove precision
        String baseType = normalizedSqlType.split("\\(")[0].trim();

        // Handle known multi-word types FIRST
        if (baseType.startsWith("TIMESTAMP")) {
            return baseType.contains("WITH TIME ZONE") ? "TIMESTAMP WITH TIME ZONE"
                    : baseType.contains("WITHOUT TIME ZONE") ? "TIMESTAMP WITHOUT TIME ZONE"
                      : "TIMESTAMP";
        }

        if (baseType.startsWith("TIME")) {
            return baseType.contains("WITH TIME ZONE") ? "TIME WITH TIME ZONE"
                    : baseType.contains("WITHOUT TIME ZONE") ? "TIME WITHOUT TIME ZONE"
                      : "TIME";
        }

        // Remove array
        baseType = baseType.replaceAll("\\[]", "");

        // Remove identity
        if (baseType.contains(" GENERATED")) {
            baseType = baseType.split("\\s+GENERATED\\s+")[0].trim();
        }

        // LAST fallback (simple types only)
        return baseType.split("\\s+")[0];
    }

    /**
     * Maps NUMERIC and DECIMAL columns to the safest Java numeric type.
     *
     * @param column the column metadata
     * @param normalizedSqlType the normalized SQL type
     * @return the mapped Java numeric type
     */
    private static String mapNumericColumn(Column column, String normalizedSqlType) {
        int scale = column.getScale();

        if (scale == 0 && normalizedSqlType.contains("(") && normalizedSqlType.contains(")")) {
            try {
                String numericArguments = normalizedSqlType.substring(
                        normalizedSqlType.indexOf('(') + 1,
                        normalizedSqlType.indexOf(')')
                );

                String[] numericParts = numericArguments.split(",");

                if (numericParts.length > 1 && !numericParts[1].trim().isEmpty()) {
                    scale = Integer.parseInt(numericParts[1].trim());
                }
            } catch (Exception exception) {
                log.warn("Failed to parse scale from sqlType '{}'", column.getSqlType(), exception);
            }
        }

        if (scale > 0) {
            return "java.math.BigDecimal";
        }

        return "java.math.BigInteger";
    }
}