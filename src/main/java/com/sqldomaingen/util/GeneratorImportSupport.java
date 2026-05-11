package com.sqldomaingen.util;

import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.Table;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Central utility responsible for resolving Java import statements
 * used across all generator components.
 * Ensures:
 * - No duplicated import logic
 * - One single source of truth for supported types
 * - Clean and predictable import generation
 */
public final class GeneratorImportSupport {

    /**
     * Supported Java simple types that may require imports.
     */
    private static final List<String> SUPPORTED_TYPES = List.of(
            // java.lang
            "String", "Integer", "Long", "Double", "Float",
            "Boolean", "Short", "Byte", "Character", "Object", "Enum",

            // java.math
            "BigDecimal", "BigInteger",

            // java.util
            "UUID", "List", "Set", "Map", "Collection",
            "ArrayList", "LinkedList", "HashSet", "LinkedHashSet", "TreeSet",
            "HashMap", "LinkedHashMap", "TreeMap", "Optional",

            // java.time
            "LocalDate", "LocalDateTime", "LocalTime",
            "Instant", "OffsetTime", "OffsetDateTime", "ZonedDateTime",

            // jakarta.persistence
            "Entity", "Table", "Id", "Column", "GeneratedValue", "GenerationType",
            "Basic", "FetchType", "CascadeType",
            "ManyToOne", "OneToMany", "OneToOne", "ManyToMany",
            "JoinColumn", "JoinColumns", "JoinTable",
            "Embeddable", "EmbeddedId", "Embedded", "MappedSuperclass",
            "Lob", "Enumerated", "EnumType", "Transient", "Version", "OrderBy",

            // validation
            "Valid", "NotNull", "NotBlank", "NotEmpty", "Size",
            "Email", "Pattern", "Min", "Max",
            "Positive", "PositiveOrZero", "Negative", "NegativeOrZero",
            "Past", "PastOrPresent", "Future", "FutureOrPresent",
            "DecimalMin", "DecimalMax",

            // jackson
            "JsonFormat", "JsonIgnore", "JsonInclude",
            "JsonProperty", "JsonIgnoreProperties",

            // lombok
            "Getter", "Setter", "Builder", "Data",
            "NoArgsConstructor", "AllArgsConstructor",
            "RequiredArgsConstructor", "EqualsAndHashCode", "ToString",

            // spring web
            "Service", "Component", "Repository",
            "RestController", "Controller",
            "RequestMapping", "GetMapping", "PostMapping",
            "PutMapping", "PatchMapping", "DeleteMapping",
            "PathVariable", "RequestParam", "RequestBody", "ResponseBody",

            // spring http
            "ResponseEntity", "HttpStatus",

            // DI
            "Autowired", "Qualifier",

            // hibernate
            "CreationTimestamp", "UpdateTimestamp",
            "JdbcTypeCode", "SqlTypes",

            // envers
            "Audited",

            // misc
            "Serializable"
    );

    /**
     * Prevent instantiation.
     */
    private GeneratorImportSupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Resolves all required imports for a given table.
     *
     * @param table table metadata
     * @param typeUsageEvaluator evaluator that decides if a type is used
     * @return set of import lines
     */
    public static Set<String> resolveImports(
            Table table,
            BiPredicate<Column, String> typeUsageEvaluator
    ) {
        Objects.requireNonNull(typeUsageEvaluator, "typeUsageEvaluator must not be null");

        Set<String> imports = new LinkedHashSet<>();

        if (table == null || table.getColumns() == null) {
            return imports;
        }

        for (Column column : table.getColumns()) {
            if (column == null) continue;

            for (String type : SUPPORTED_TYPES) {
                tryAddImport(imports, column, type, typeUsageEvaluator);
            }
        }

        return imports;
    }

    /**
     * Adds import if the type is used.
     */
    private static void tryAddImport(
            Set<String> imports,
            Column column,
            String type,
            BiPredicate<Column, String> evaluator
    ) {
        if (!evaluator.test(column, type)) {
            return;
        }

        String importLine = JavaTypeSupport.resolveImportLine(type);

        if (importLine != null && !importLine.isBlank()) {
            imports.add(importLine);
        }
    }

    /**
     * Returns supported types list.
     */
    public static List<String> getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    /**
     * Resolves imports directly from simple Java type names.
     *
     * @param typeNames simple Java type names
     * @return set of import lines
     */
    public static Set<String> resolveImportsFromTypes(Set<String> typeNames) {
        Set<String> imports = new LinkedHashSet<>();

        if (typeNames == null || typeNames.isEmpty()) {
            return imports;
        }

        for (String typeName : typeNames) {
            tryAddImportForType(imports, typeName);
        }

        return imports;
    }

    /**
     * Adds an import for the provided simple Java type name when supported.
     *
     * @param imports target import set
     * @param typeName simple Java type name
     */
    private static void tryAddImportForType(Set<String> imports, String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return;
        }

        String normalizedType = typeName.trim();

        if (!SUPPORTED_TYPES.contains(normalizedType)) {
            return;
        }

        String importLine = JavaTypeSupport.resolveImportLine(normalizedType);

        if (importLine != null && !importLine.isBlank()) {
            imports.add(importLine);
        }
    }

    /**
     * Adds framework imports required by generated REST controllers.
     *
     * <p>
     * Includes:
     * <ul>
     *     <li>Swagger/OpenAPI annotations</li>
     *     <li>Spring Web annotations and response types</li>
     *     <li>Jakarta validation annotations</li>
     *     <li>Lombok constructor support</li>
     *     <li>Java collection types used by controllers</li>
     * </ul>
     *
     * @param importCollector target import collector
     */
    public static void addControllerFrameworkImports(JavaImportCollector importCollector) {
        Objects.requireNonNull(importCollector, "importCollector must not be null");

        importCollector.addImport("import io.swagger.v3.oas.annotations.Operation;");
        importCollector.addImport("import io.swagger.v3.oas.annotations.tags.Tag;");
        importCollector.addImport("import jakarta.validation.Valid;");
        importCollector.addImport("import lombok.RequiredArgsConstructor;");
        importCollector.addImport("import org.springframework.http.HttpStatus;");
        importCollector.addImport("import org.springframework.http.ResponseEntity;");
        importCollector.addImport("import org.springframework.web.bind.annotation.*;");
        importCollector.addImport("import java.util.List;");
    }

    /**
     * Adds framework imports required by generated DTO classes.
     *
     * @param importCollector import collector
     */
    public static void addDtoFrameworkImports(JavaImportCollector importCollector) {
        Objects.requireNonNull(importCollector, "importCollector must not be null");

        importCollector.addImport("import com.fasterxml.jackson.annotation.JsonInclude;");
        importCollector.addImport("import lombok.AllArgsConstructor;");
        importCollector.addImport("import lombok.Builder;");
        importCollector.addImport("import lombok.Data;");
        importCollector.addImport("import lombok.NoArgsConstructor;");
    }
}