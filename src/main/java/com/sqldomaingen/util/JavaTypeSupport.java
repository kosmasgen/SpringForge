package com.sqldomaingen.util;

import java.util.Set;

/**
 * Utility class for working with resolved Java types inside generators.
 *
 * <p>This class does not map SQL types to Java types. That responsibility belongs
 * to {@link TypeMapper}. This class only provides helpers for:
 * <ul>
 *     <li>simplifying fully qualified Java type names</li>
 *     <li>resolving import lines</li>
 *     <li>checking common Java type categories</li>
 *     <li>detecting collection and scalar types</li>
 * </ul>
 */
public final class JavaTypeSupport {

    private static final Set<String> SCALAR_TYPES = Set.of(
            "Object",
            "String",
            "Long",
            "Integer",
            "Boolean",
            "Double",
            "Float",
            "Short",
            "Byte",
            "Character",
            "long",
            "int",
            "boolean",
            "double",
            "float",
            "short",
            "byte",
            "char",
            "UUID",
            "BigDecimal",
            "BigInteger",
            "LocalDate",
            "LocalTime",
            "LocalDateTime",
            "OffsetTime",
            "OffsetDateTime",
            "Instant",
            "ZonedDateTime"
    );


    /**
     * Prevents instantiation of utility class.
     */
    private JavaTypeSupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Resolves the simple Java type name from a raw type.
     *
     * <p>Examples:
     * <ul>
     *     <li>{@code java.util.UUID -> UUID}</li>
     *     <li>{@code java.math.BigInteger -> BigInteger}</li>
     *     <li>{@code String -> String}</li>
     * </ul>
     *
     * @param rawType raw Java type
     * @return simple Java type name, or {@code Object} when blank
     */
    public static String resolveSimpleType(String rawType) {
        String normalizedType = GeneratorSupport.trimToEmpty(rawType);

        if (normalizedType.isEmpty()) {
            return "Object";
        }

        if (normalizedType.endsWith("[]")) {
            String elementType = normalizedType.substring(0, normalizedType.length() - 2);
            return resolveSimpleType(elementType) + "[]";
        }

        int genericStartIndex = normalizedType.indexOf('<');
        if (genericStartIndex > 0) {
            String outerType = normalizedType.substring(0, genericStartIndex).trim();
            return resolveSimpleType(outerType);
        }

        if (normalizedType.contains(".")) {
            return normalizedType.substring(normalizedType.lastIndexOf('.') + 1);
        }

        return normalizedType;
    }

    /**
     * Resolves the import line required for the given raw Java type.
     *
     * @param rawType raw Java type
     * @return import line, or {@code null} when no import is required
     */
    public static String resolveImportLine(String rawType) {
        String normalizedType = GeneratorSupport.trimToEmpty(rawType);

        if (normalizedType.isEmpty()) {
            return null;
        }

        String simpleType = resolveSimpleType(normalizedType);

        return switch (simpleType) {

            // --- Java util ---
            case "UUID" -> "import java.util.UUID;";
            case "List" -> "import java.util.List;";
            case "Set" -> "import java.util.Set;";
            case "Map" -> "import java.util.Map;";
            case "Collection" -> "import java.util.Collection;";
            case "ArrayList" -> "import java.util.ArrayList;";
            case "LinkedList" -> "import java.util.LinkedList;";
            case "HashSet" -> "import java.util.HashSet;";
            case "LinkedHashSet" -> "import java.util.LinkedHashSet;";
            case "TreeSet" -> "import java.util.TreeSet;";
            case "HashMap" -> "import java.util.HashMap;";
            case "LinkedHashMap" -> "import java.util.LinkedHashMap;";
            case "TreeMap" -> "import java.util.TreeMap;";
            case "Optional" -> "import java.util.Optional;";

            // --- Java math ---
            case "BigDecimal" -> "import java.math.BigDecimal;";
            case "BigInteger" -> "import java.math.BigInteger;";

            // --- Java time ---
            case "LocalDate" -> "import java.time.LocalDate;";
            case "LocalDateTime" -> "import java.time.LocalDateTime;";
            case "LocalTime" -> "import java.time.LocalTime;";
            case "OffsetTime" -> "import java.time.OffsetTime;";
            case "OffsetDateTime" -> "import java.time.OffsetDateTime;";
            case "Instant" -> "import java.time.Instant;";
            case "ZonedDateTime" -> "import java.time.ZonedDateTime;";

            // --- Jakarta persistence ---
            case "Entity" -> "import jakarta.persistence.Entity;";
            case "Table" -> "import jakarta.persistence.Table;";
            case "Id" -> "import jakarta.persistence.Id;";
            case "Column" -> "import jakarta.persistence.Column;";
            case "GeneratedValue" -> "import jakarta.persistence.GeneratedValue;";
            case "GenerationType" -> "import jakarta.persistence.GenerationType;";
            case "Basic" -> "import jakarta.persistence.Basic;";
            case "FetchType" -> "import jakarta.persistence.FetchType;";
            case "CascadeType" -> "import jakarta.persistence.CascadeType;";
            case "ManyToOne" -> "import jakarta.persistence.ManyToOne;";
            case "OneToMany" -> "import jakarta.persistence.OneToMany;";
            case "OneToOne" -> "import jakarta.persistence.OneToOne;";
            case "ManyToMany" -> "import jakarta.persistence.ManyToMany;";
            case "JoinColumn" -> "import jakarta.persistence.JoinColumn;";
            case "JoinColumns" -> "import jakarta.persistence.JoinColumns;";
            case "JoinTable" -> "import jakarta.persistence.JoinTable;";
            case "Embeddable" -> "import jakarta.persistence.Embeddable;";
            case "EmbeddedId" -> "import jakarta.persistence.EmbeddedId;";
            case "Embedded" -> "import jakarta.persistence.Embedded;";
            case "MappedSuperclass" -> "import jakarta.persistence.MappedSuperclass;";
            case "Lob" -> "import jakarta.persistence.Lob;";
            case "Enumerated" -> "import jakarta.persistence.Enumerated;";
            case "EnumType" -> "import jakarta.persistence.EnumType;";
            case "Transient" -> "import jakarta.persistence.Transient;";
            case "Version" -> "import jakarta.persistence.Version;";
            case "OrderBy" -> "import jakarta.persistence.OrderBy;";

            // --- Validation ---
            case "Valid" -> "import jakarta.validation.Valid;";
            case "NotNull" -> "import jakarta.validation.constraints.NotNull;";
            case "NotBlank" -> "import jakarta.validation.constraints.NotBlank;";
            case "NotEmpty" -> "import jakarta.validation.constraints.NotEmpty;";
            case "Size" -> "import jakarta.validation.constraints.Size;";
            case "Email" -> "import jakarta.validation.constraints.Email;";
            case "Pattern" -> "import jakarta.validation.constraints.Pattern;";
            case "Min" -> "import jakarta.validation.constraints.Min;";
            case "Max" -> "import jakarta.validation.constraints.Max;";
            case "Positive" -> "import jakarta.validation.constraints.Positive;";
            case "PositiveOrZero" -> "import jakarta.validation.constraints.PositiveOrZero;";
            case "Negative" -> "import jakarta.validation.constraints.Negative;";
            case "NegativeOrZero" -> "import jakarta.validation.constraints.NegativeOrZero;";
            case "Past" -> "import jakarta.validation.constraints.Past;";
            case "PastOrPresent" -> "import jakarta.validation.constraints.PastOrPresent;";
            case "Future" -> "import jakarta.validation.constraints.Future;";
            case "FutureOrPresent" -> "import jakarta.validation.constraints.FutureOrPresent;";
            case "DecimalMin" -> "import jakarta.validation.constraints.DecimalMin;";
            case "DecimalMax" -> "import jakarta.validation.constraints.DecimalMax;";

            // --- Jackson ---
            case "JsonFormat" -> "import com.fasterxml.jackson.annotation.JsonFormat;";
            case "JsonIgnore" -> "import com.fasterxml.jackson.annotation.JsonIgnore;";
            case "JsonInclude" -> "import com.fasterxml.jackson.annotation.JsonInclude;";
            case "JsonProperty" -> "import com.fasterxml.jackson.annotation.JsonProperty;";
            case "JsonIgnoreProperties" -> "import com.fasterxml.jackson.annotation.JsonIgnoreProperties;";

            // --- Lombok ---
            case "Getter" -> "import lombok.Getter;";
            case "Setter" -> "import lombok.Setter;";
            case "Builder" -> "import lombok.Builder;";
            case "Data" -> "import lombok.Data;";
            case "NoArgsConstructor" -> "import lombok.NoArgsConstructor;";
            case "AllArgsConstructor" -> "import lombok.AllArgsConstructor;";
            case "RequiredArgsConstructor" -> "import lombok.RequiredArgsConstructor;";
            case "EqualsAndHashCode" -> "import lombok.EqualsAndHashCode;";
            case "ToString" -> "import lombok.ToString;";

            // --- Spring ---
            case "Service" -> "import org.springframework.stereotype.Service;";
            case "Component" -> "import org.springframework.stereotype.Component;";
            case "Repository" -> "import org.springframework.stereotype.Repository;";
            case "RestController" -> "import org.springframework.web.bind.annotation.RestController;";
            case "Controller" -> "import org.springframework.stereotype.Controller;";
            case "RequestMapping" -> "import org.springframework.web.bind.annotation.RequestMapping;";
            case "GetMapping" -> "import org.springframework.web.bind.annotation.GetMapping;";
            case "PostMapping" -> "import org.springframework.web.bind.annotation.PostMapping;";
            case "PutMapping" -> "import org.springframework.web.bind.annotation.PutMapping;";
            case "PatchMapping" -> "import org.springframework.web.bind.annotation.PatchMapping;";
            case "DeleteMapping" -> "import org.springframework.web.bind.annotation.DeleteMapping;";
            case "PathVariable" -> "import org.springframework.web.bind.annotation.PathVariable;";
            case "RequestParam" -> "import org.springframework.web.bind.annotation.RequestParam;";
            case "RequestBody" -> "import org.springframework.web.bind.annotation.RequestBody;";
            case "ResponseBody" -> "import org.springframework.web.bind.annotation.ResponseBody;";
            case "ResponseEntity" -> "import org.springframework.http.ResponseEntity;";
            case "HttpStatus" -> "import org.springframework.http.HttpStatus;";
            case "Autowired" -> "import org.springframework.beans.factory.annotation.Autowired;";
            case "Qualifier" -> "import org.springframework.beans.factory.annotation.Qualifier;";

            // --- Hibernate ---
            case "CreationTimestamp" -> "import org.hibernate.annotations.CreationTimestamp;";
            case "UpdateTimestamp" -> "import org.hibernate.annotations.UpdateTimestamp;";
            case "JdbcTypeCode" -> "import org.hibernate.annotations.JdbcTypeCode;";
            case "SqlTypes" -> "import org.hibernate.type.SqlTypes;";

            // --- Envers ---
            case "Audited" -> "import org.hibernate.envers.Audited;";

            // --- Misc ---
            case "Serializable" -> "import java.io.Serializable;";

            default -> {
                if (!normalizedType.contains(".")) {
                    yield null;
                }

                if (normalizedType.startsWith("java.lang.")) {
                    yield null;
                }

                if (normalizedType.contains("<") || normalizedType.contains(">")) {
                    yield null;
                }

                yield "import " + normalizedType + ";";
            }
        };
    }

    /**
     * Returns true when the provided raw type is a UUID type.
     *
     * @param rawType raw Java type
     * @return true when the type is UUID
     */
    public static boolean isUuidType(String rawType) {
        String simpleType = resolveSimpleType(rawType);
        return "UUID".equals(simpleType);
    }

    /**
     * Returns true when the provided raw type is a BigDecimal type.
     *
     * @param rawType raw Java type
     * @return true when the type is BigDecimal
     */
    public static boolean isBigDecimalType(String rawType) {
        String simpleType = resolveSimpleType(rawType);
        return "BigDecimal".equals(simpleType);
    }

    /**
     * Returns true when the provided raw type is a BigInteger type.
     *
     * @param rawType raw Java type
     * @return true when the type is BigInteger
     */
    public static boolean isBigIntegerType(String rawType) {
        String simpleType = resolveSimpleType(rawType);
        return "BigInteger".equals(simpleType);
    }

    /**
     * Returns true when the provided raw type is a LocalDate type.
     *
     * @param rawType raw Java type
     * @return true when the type is LocalDate
     */
    public static boolean isLocalDateType(String rawType) {
        String simpleType = resolveSimpleType(rawType);
        return "LocalDate".equals(simpleType);
    }

    /**
     * Returns true when the provided raw type is a LocalDateTime type.
     *
     * @param rawType raw Java type
     * @return true when the type is LocalDateTime
     */
    public static boolean isLocalDateTimeType(String rawType) {
        String simpleType = resolveSimpleType(rawType);
        return "LocalDateTime".equals(simpleType);
    }

    /**
     * Returns true when the provided raw type is a LocalTime type.
     *
     * @param rawType raw Java type
     * @return true when the type is LocalTime
     */
    public static boolean isLocalTimeType(String rawType) {
        String simpleType = resolveSimpleType(rawType);
        return "LocalTime".equals(simpleType);
    }

    /**
     * Returns true when the provided raw type is a temporal type supported by the generator.
     *
     * @param rawType raw Java type
     * @return true when the type is temporal
     */
    public static boolean isTemporalType(String rawType) {
        String simpleType = resolveSimpleType(rawType);

        return "LocalDate".equals(simpleType)
                || "LocalTime".equals(simpleType)
                || "LocalDateTime".equals(simpleType)
                || "OffsetTime".equals(simpleType)
                || "OffsetDateTime".equals(simpleType)
                || "Instant".equals(simpleType)
                || "ZonedDateTime".equals(simpleType);
    }

    /**
     * Returns true when the provided raw type is a collection type.
     *
     * @param rawType raw Java type
     * @return true when the type is List, Set, or Map
     */
    public static boolean isCollectionType(String rawType) {
        String normalizedType = GeneratorSupport.trimToEmpty(rawType);

        return normalizedType.startsWith("List<")
                || normalizedType.startsWith("Set<")
                || normalizedType.startsWith("Map<")
                || normalizedType.startsWith("java.util.List<")
                || normalizedType.startsWith("java.util.Set<")
                || normalizedType.startsWith("java.util.Map<")
                || "List".equals(normalizedType)
                || "Set".equals(normalizedType)
                || "Map".equals(normalizedType)
                || "java.util.List".equals(normalizedType)
                || "java.util.Set".equals(normalizedType)
                || "java.util.Map".equals(normalizedType);
    }

    /**
     * Returns true when the provided raw type is a List declaration.
     *
     * @param rawType raw Java type
     * @return true when the type is List
     */
    public static boolean isListType(String rawType) {
        String normalizedType = GeneratorSupport.trimToEmpty(rawType);

        return normalizedType.startsWith("List<")
                || normalizedType.startsWith("java.util.List<")
                || "List".equals(normalizedType)
                || "java.util.List".equals(normalizedType);
    }

    /**
     * Returns true when the provided raw type is a Set declaration.
     *
     * @param rawType raw Java type
     * @return true when the type is Set
     */
    public static boolean isSetType(String rawType) {
        String normalizedType = GeneratorSupport.trimToEmpty(rawType);

        return normalizedType.startsWith("Set<")
                || normalizedType.startsWith("java.util.Set<")
                || "Set".equals(normalizedType)
                || "java.util.Set".equals(normalizedType);
    }

    /**
     * Returns true when the provided type should be treated as a scalar Java type.
     *
     * @param rawType raw Java type
     * @return true when the type is scalar
     */
    public static boolean isScalarType(String rawType) {
        String simpleType = resolveSimpleType(rawType);
        return SCALAR_TYPES.contains(simpleType);
    }

    /**
     * Extracts the inner generic type from a generic declaration.
     *
     * <p>Examples:
     * <ul>
     *     <li>{@code List<String> -> String}</li>
     *     <li>{@code java.util.Set<java.util.UUID> -> java.util.UUID}</li>
     * </ul>
     *
     * @param rawType raw Java type
     * @return inner generic type, or {@code Object} when not present
     */
    public static String extractGenericInnerType(String rawType) {
        String normalizedType = GeneratorSupport.trimToEmpty(rawType);
        int leftBracketIndex = normalizedType.indexOf('<');
        int rightBracketIndex = normalizedType.lastIndexOf('>');

        if (leftBracketIndex > 0 && rightBracketIndex > leftBracketIndex) {
            return normalizedType.substring(leftBracketIndex + 1, rightBracketIndex).trim();
        }

        return "Object";
    }

    /**
     * Returns true when the raw type contains the given simple type.
     *
     * @param rawType raw Java type
     * @param simpleType simple Java type to look for
     * @return true when the type matches directly or appears inside the raw type
     */
    public static boolean containsType(String rawType, String simpleType) {
        String normalizedRawType = GeneratorSupport.trimToEmpty(rawType);
        String normalizedSimpleType = GeneratorSupport.trimToEmpty(simpleType);

        if (normalizedRawType.isEmpty() || normalizedSimpleType.isEmpty()) {
            return false;
        }

        String resolvedSimpleType = resolveSimpleType(normalizedRawType);
        if (resolvedSimpleType.equals(normalizedSimpleType)) {
            return true;
        }

        return normalizedRawType.contains(normalizedSimpleType);
    }
}