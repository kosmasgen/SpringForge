package com.sqldomaingen.generator;

import com.sqldomaingen.model.Entity;
import com.sqldomaingen.model.Field;
import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.util.JavaImportCollector;
import com.sqldomaingen.util.JavaTypeSupport;
import com.sqldomaingen.util.PackageResolver;
import lombok.extern.log4j.Log4j2;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Generates DTO classes for parsed entities.
 */
@Log4j2
public class DTOGenerator {

    /**
     * Generates DTO classes under:
     * {outputDir}/src/main/java/{basePackagePath}/dto
     *
     * @param entities entity models
     * @param outputDir project root output directory
     * @param basePackage base Java package
     */
    public void generateDTOs(List<Entity> entities, String outputDir, String basePackage) {
        Objects.requireNonNull(entities, "entities must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        String dtoPackage = PackageResolver.resolvePackageName(basePackage, "dto");
        Path dtoDir = GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(outputDir, basePackage, "dto")
        );

        log.info("Starting DTO generation...");

        for (Entity entity : entities) {
            log.info("Generating DTO for entity: {}", entity.getName());

            String dtoContent = createDtoContent(entity, dtoPackage);
            Path outputPath = dtoDir.resolve(entity.getName() + "Dto.java");
            GeneratorSupport.writeFile(outputPath, dtoContent);
        }

        log.info("DTO generation complete. Output directory: {}", dtoDir.toAbsolutePath());
    }


    /**
     * Creates the full DTO source code for the given entity.
     *
     * @param entity source entity metadata
     * @param dtoPackage target DTO package name
     * @return generated DTO source code
     */
    private String createDtoContent(Entity entity, String dtoPackage) {
        StringBuilder builder = new StringBuilder();

        addPackageAndImports(builder, dtoPackage, entity);
        addClassDefinition(builder, entity);

        return builder.toString();
    }


    /**
     * Adds the package declaration and required imports to the DTO source.
     *
     * @param builder target source builder
     * @param dtoPackage target DTO package name
     * @param entity source entity metadata
     */
    private void addPackageAndImports(
            StringBuilder builder,
            String dtoPackage,
            Entity entity
    ) {
        builder.append("package ").append(dtoPackage).append(";\n\n");

        JavaImportCollector collector = new JavaImportCollector();
        addFixedImports(collector);
        addFieldBasedImports(collector, entity, dtoPackage);

        for (String importLine : collector.getImports()) {
            builder.append(importLine).append("\n");
        }

        builder.append("\n");
    }

    /**
     * Adds the always-required imports for generated DTO classes.
     *
     * @param collector import collector
     */
    private void addFixedImports(JavaImportCollector collector) {
        collector.addImport("import com.fasterxml.jackson.annotation.JsonInclude;");
        collector.addImport("import lombok.AllArgsConstructor;");
        collector.addImport("import lombok.Builder;");
        collector.addImport("import lombok.Data;");
        collector.addImport("import lombok.NoArgsConstructor;");
    }

    /**
     * Adds the field-based imports for the generated DTO class.
     *
     * <p>
     * Rules:
     * <ul>
     *     <li>Nested DTO relation types are imported from the dto package</li>
     *     <li>Key classes are imported only from the entity package</li>
     *     <li>Scalar/supporting Java types are imported through the import collector</li>
     * </ul>
     *
     * @param collector import collector
     * @param entity source entity metadata
     * @param dtoPackage dto package name
     */
    private void addFieldBasedImports(
            JavaImportCollector collector,
            Entity entity,
            String dtoPackage
    ) {
        String entityPackage = resolveEntityPackageFromDtoPackage(dtoPackage);

        for (Field field : getEntityFields(entity)) {
            String fieldType = DtoFieldTypeResolver.resolveDtoFieldType(field);

            if (isEmbeddedIdField(field)) {
                collector.addImport("import " + entityPackage + "." + fieldType + ";");
                continue;
            }

            addProjectTypeImports(collector, fieldType, entityPackage);
            collector.addImportForComplexType(fieldType);

            if (shouldAddNotNull(field)) {
                collector.addImportForType("NotNull");
            }

            if (shouldAddSize(field, fieldType)) {
                collector.addImportForType("Size");
            }

            if (shouldAddJsonFormat(fieldType)) {
                collector.addImportForType("JsonFormat");
            }
        }
    }

    /**
     * Adds project-specific imports for DTO field types.
     *
     * @param collector import collector
     * @param fieldType resolved field type
     * @param entityPackage entity package name
     */
    private void addProjectTypeImports(
            JavaImportCollector collector,
            String fieldType,
            String entityPackage
    ) {
        for (String simpleType : extractProjectSimpleTypes(fieldType)) {

            if (simpleType.endsWith("Dto")) {
                continue;
            }

            if (shouldImportEntityKeyType(simpleType)) {
                collector.addImport("import " + entityPackage + "." + simpleType + ";");
            }
        }
    }


    /**
     * Extracts project-specific simple types from a possibly generic field type.
     *
     * <p>
     * Examples:
     * <ul>
     *     <li>BusinessLocationDto -> BusinessLocationDto</li>
     *     <li>List&lt;LanguagesDto&gt; -> LanguagesDto</li>
     *     <li>BusinessLocationI18nKey -> BusinessLocationI18nKey</li>
     * </ul>
     *
     * @param fieldType resolved field type
     * @return extracted project-specific simple types
     */
    private List<String> extractProjectSimpleTypes(String fieldType) {
        String normalizedType = GeneratorSupport.trimToEmpty(fieldType);

        if (normalizedType.isBlank()) {
            return List.of();
        }

        String cleanedType = normalizedType
                .replace("<", " ")
                .replace(">", " ")
                .replace(",", " ")
                .replace("[", " ")
                .replace("]", " ");

        return Arrays.stream(cleanedType.split("\\s+"))
                .filter(token -> !token.isBlank())
                .filter(token -> !JavaTypeSupport.isScalarType(token))
                .filter(token -> !token.equals("List"))
                .filter(token -> !token.equals("Set"))
                .filter(token -> !token.equals("Map"))
                .toList();
    }




    /**
     * Returns whether the resolved field type should be imported from the entity package.
     *
     * @param fieldType resolved DTO field type
     * @return true when the type is an entity key type
     */
    private boolean shouldImportEntityKeyType(String fieldType) {
        String normalizedType = GeneratorSupport.trimToEmpty(fieldType);

        return normalizedType.endsWith("Key")
                || normalizedType.endsWith("PK");
    }


    /**
     * Resolves the entity package from a dto package.
     *
     * @param dtoPackage dto package name
     * @return entity package name
     */
    private String resolveEntityPackageFromDtoPackage(String dtoPackage) {
        String normalizedPackage = GeneratorSupport.trimToEmpty(dtoPackage);

        if (normalizedPackage.isBlank()) {
            throw new IllegalStateException("DTO package must not be blank");
        }

        if (!normalizedPackage.endsWith(".dto")) {
            throw new IllegalStateException("DTO package must end with .dto: " + dtoPackage);
        }

        String basePackage = normalizedPackage.substring(0, normalizedPackage.length() - ".dto".length());
        return PackageResolver.resolvePackageName(basePackage, "entity");
    }


    /**
     * Determines whether @JsonFormat import is required for the given field type.
     *
     * @param fieldType resolved DTO field type
     * @return true when JsonFormat import is required
     */
    private boolean shouldAddJsonFormat(String fieldType) {
        return fieldType.contains("LocalDateTime")
                || fieldType.contains("LocalDate")
                || fieldType.contains("LocalTime");
    }

    /**
     * Determines whether @Size should be generated for a DTO field.
     *
     * @param field source field metadata
     * @param fieldType resolved DTO field type
     * @return true when @Size should be added
     */
    private boolean shouldAddSize(Field field, String fieldType) {
        return field != null
                && field.getLength() != null
                && field.getLength() > 0
                && isStringType(fieldType);
    }

    /**
     * Adds field-level DTO annotations.
     * Emits @NotNull, @JsonFormat, and @Size when applicable.
     *
     * @param builder target source builder
     * @param field source field metadata
     */
    private void addFieldAnnotations(
            StringBuilder builder,
            Field field
    ) {
        if (field == null) {
            return;
        }

        String fieldType = DtoFieldTypeResolver.resolveDtoFieldType(field);

        if (shouldAddNotNull(field)) {
            builder.append("    @NotNull\n");
        }

        if (fieldType.contains("LocalDateTime")) {
            builder.append("    @JsonFormat(pattern = \"yyyy-MM-dd HH:mm:ss\")\n");
        } else if (fieldType.contains("LocalDate")) {
            builder.append("    @JsonFormat(pattern = \"yyyy-MM-dd\")\n");
        } else if (fieldType.contains("LocalTime")) {
            builder.append("    @JsonFormat(pattern = \"HH:mm:ss\")\n");
        }

        if (shouldAddSize(field, fieldType)) {
            builder.append("    @Size(max = ").append(field.getLength()).append(")\n");
        }
    }

    /**
     * Determines whether @NotNull should be generated for a DTO field.
     *
     * @param field source field metadata
     * @return true when the DTO field should be annotated with @NotNull
     */
    private boolean shouldAddNotNull(Field field) {
        if (field == null) {
            return false;
        }

        String fieldName = GeneratorSupport.trimToEmpty(field.getName());

        return !field.isNullable()
                && !field.isPrimaryKey()
                && !"dateCreated".equals(fieldName)
                && !"lastUpdated".equals(fieldName);
    }

    /**
     * Determines whether the given type represents a String.
     *
     * @param type resolved Java type
     * @return true when the type is String
     */
    private static boolean isStringType(String type) {
        return "String".equals(type);
    }

    /**
     * Adds the DTO class definition and fields to the source builder.
     *
     * @param builder target source builder
     * @param entity source entity metadata
     */
    private void addClassDefinition(StringBuilder builder, Entity entity) {
        builder.append("/**\n");
        builder.append(" * Data transfer object for ").append(entity.getName()).append(".\n");
        builder.append(" */\n");
        builder.append("@Data\n");
        builder.append("@Builder\n");
        builder.append("@NoArgsConstructor\n");
        builder.append("@AllArgsConstructor\n");
        builder.append("@JsonInclude(JsonInclude.Include.NON_NULL)\n");
        builder.append("public class ").append(entity.getName()).append("Dto {\n\n");

        for (Field field : getEntityFields(entity)) {
            if (entity.getCompositeKey() != null && field.isPrimaryKey() && !isEmbeddedIdField(field)) {
                continue;
            }

            addDtoField(builder, field);
        }

        builder.append("}\n");
    }



    /**
     * Determines whether the field represents an embedded key field.
     *
     * @param field source field
     * @return true when the field is an embedded key reference
     */
    private boolean isEmbeddedIdField(Field field) {
        if (field == null) {
            return false;
        }

        String fieldName = GeneratorSupport.trimToEmpty(field.getName());
        String fieldType = GeneratorSupport.trimToEmpty(field.getType());

        return "id".equals(fieldName)
                && (fieldType.endsWith("Key") || fieldType.endsWith("PK"));
    }


    /**
     * Adds a DTO field based on the resolved DTO generation strategy.
     *
     * @param builder target source builder
     * @param field source field
     */
    private void addDtoField(
            StringBuilder builder,
            Field field
    ) {
        if (field == null) {
            return;
        }

        String fieldType = DtoFieldTypeResolver.resolveDtoFieldType(field);
        String fieldName = resolveDtoFieldName(field);

        addFieldAnnotations(builder, field);

        builder.append("    private ")
                .append(fieldType)
                .append(" ")
                .append(fieldName)
                .append(";\n\n");
    }

    /**
     * Resolves DTO field name.
     * Keeps original field names for full DTO mode.
     *
     * @param field source field
     * @return DTO field name
     */
    private String resolveDtoFieldName(Field field) {
        if (field == null) {
            return null;
        }

        return field.getName();
    }

    /**
     * Returns the entity fields or an empty list when no fields exist.
     *
     * @param entity source entity metadata
     * @return entity field list
     */
    private List<Field> getEntityFields(Entity entity) {
        return entity == null || entity.getFields() == null ? List.of() : entity.getFields();
    }

}