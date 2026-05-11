package com.sqldomaingen.generatorTest;

import com.sqldomaingen.model.Entity;
import com.sqldomaingen.model.Field;
import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.util.JavaImportCollector;
import com.sqldomaingen.util.NamingConverter;
import com.sqldomaingen.util.PackageResolver;
import lombok.extern.log4j.Log4j2;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Generates POJO-style test classes for generated entity classes.
 */
@Log4j2
public class EntityPojoTestGenerator {

    /**
     * Generates entity POJO test classes for all generated entities.
     *
     * @param entities generated entity metadata
     * @param outputDir project root output directory
     * @param basePackage base Java package
     * @param overwrite overwrite existing files when true
     */
    public void generateEntityPojoTests(
            List<Entity> entities,
            String outputDir,
            String basePackage,
            boolean overwrite
    ) {
        Objects.requireNonNull(entities, "entities must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        Path entityTestDir = GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(outputDir, basePackage, "entity", true)
        );
        String testPackage = PackageResolver.resolvePackageName(basePackage, "entity");
        String entityPackage = PackageResolver.resolvePackageName(basePackage, "entity");

        for (Entity entity : entities) {
            if (entity == null || GeneratorSupport.trimToEmpty(entity.getName()).isBlank()) {
                continue;
            }

            String testName = entity.getName() + "EntityTest";
            String content = generateEntityPojoTestContent(entity, testPackage, entityPackage);
            GeneratorSupport.writeFile(entityTestDir.resolve(testName + ".java"), content, overwrite);
        }

        log.debug("Entity POJO test classes generated under: {}", entityTestDir.toAbsolutePath());
    }

    /**
     * Generates the full Java source code for a single entity POJO test class.
     *
     * @param entity generated entity metadata
     * @param testPackage target test package
     * @param entityPackage target entity package
     * @return generated Java source code
     */
    private String generateEntityPojoTestContent(
            Entity entity,
            String testPackage,
            String entityPackage
    ) {
        StringBuilder content = new StringBuilder();

        appendPackageAndImports(content, testPackage, entityPackage, entity);
        appendClassDeclaration(content, entity);
        appendNoArgsConstructorTest(content, entity);
        appendAllArgsConstructorTest(content, entity);
        appendSettersAndGettersTest(content, entity);
        content.append("}\n");

        return content.toString();
    }

    /**
     * Appends the package declaration and imports for the generated entity test class.
     *
     * @param content target source builder
     * @param testPackage target test package
     * @param entityPackage target entity package
     * @param entity generated entity metadata
     */
    private void appendPackageAndImports(
            StringBuilder content,
            String testPackage,
            String entityPackage,
            Entity entity
    ) {
        content.append("package ").append(testPackage).append(";\n\n");

        JavaImportCollector collector = new JavaImportCollector();

        if (!Objects.equals(testPackage, entityPackage)) {
            collector.addImport("import " + entityPackage + "." + entity.getName() + ";");
        }

        collector.addImport("import org.junit.jupiter.api.Test;");
        collector.addStaticImport("import static org.assertj.core.api.Assertions.assertThat;");

        for (Field field : getEntityFields(entity)) {
            collector.addImportForComplexType(normalizeType(field.getType()));
        }

        for (String importLine : collector.getImports()) {
            content.append(importLine).append("\n");
        }

        content.append("\n");
    }

    /**
     * Appends the generated entity test class declaration.
     *
     * @param content target source builder
     * @param entity generated entity metadata
     */
    private void appendClassDeclaration(StringBuilder content, Entity entity) {
        content.append("class ").append(entity.getName()).append("EntityTest {\n\n");
    }

    private void appendNoArgsConstructorTest(StringBuilder content, Entity entity) {
        String entityName = entity.getName();
        String entityVar = "entity";

        content.append("    /**\n");
        content.append("     * Tests the ").append(entityName).append(" no-args constructor.\n");
        content.append("     */\n");
        content.append("    @Test\n");
        content.append("    void test").append(entityName).append("NoArgsConstructor() {\n");
        content.append("        ").append(entityName).append(" ").append(entityVar)
                .append(" = new ").append(entityName).append("();\n\n");

        for (Field field : getEntityFields(entity)) {
            content.append("        assertThat(")
                    .append(entityVar)
                    .append(".")
                    .append(resolveGetterName(field))
                    .append("()).isNull();\n");
        }

        content.append("    }\n\n");
    }

    /**
     * Appends the all-args constructor test.
     *
     * @param content target source builder
     * @param entity generated entity metadata
     */
    private void appendAllArgsConstructorTest(StringBuilder content, Entity entity) {
        String entityName = entity.getName();
        String entityVar = "entity";

        content.append("    /**\n");
        content.append("     * Tests the ").append(entityName).append(" all-args constructor.\n");
        content.append("     */\n");
        content.append("    @Test\n");
        content.append("    void test").append(entityName).append("AllArgsConstructor() {\n");

        for (Field field : getEntityFields(entity)) {
            appendSampleVariableDeclaration(content, field);
        }

        if (!getEntityFields(entity).isEmpty()) {
            content.append("\n");
        }

        content.append("        ").append(entityName).append(" ").append(entityVar).append(" = new ")
                .append(entityName).append("(").append(buildConstructorArgumentList(entity)).append(");\n\n");

        for (Field field : getEntityFields(entity)) {
            appendEqualityAssertion(content, entityVar, field);
        }

        content.append("    }\n\n");
    }

    /**
     * Appends the setters and getters symmetry test.
     *
     * @param content target source builder
     * @param entity generated entity metadata
     */
    private void appendSettersAndGettersTest(StringBuilder content, Entity entity) {
        String entityName = entity.getName();
        String entityVar = "entity";

        content.append("    /**\n");
        content.append("     * Tests setters and getters symmetry.\n");
        content.append("     */\n");
        content.append("    @Test\n");
        content.append("    void test").append(entityName).append("SettersAndGetters() {\n");
        content.append("        ").append(entityName).append(" ").append(entityVar)
                .append(" = new ").append(entityName).append("();\n");

        if (!getEntityFields(entity).isEmpty()) {
            content.append("\n");
        }

        for (Field field : getEntityFields(entity)) {
            appendSampleVariableDeclaration(content, field);
        }

        if (!getEntityFields(entity).isEmpty()) {
            content.append("\n");
        }

        for (Field field : getEntityFields(entity)) {
            appendSetterInvocation(content, entityVar, field);
        }

        if (!getEntityFields(entity).isEmpty()) {
            content.append("\n");
        }

        for (Field field : getEntityFields(entity)) {
            appendEqualityAssertion(content, entityVar, field);
        }

        content.append("    }\n\n");
    }




    /**
     * Appends a sample variable declaration for a field.
     *
     * @param content target source builder
     * @param field generated field metadata
     */
    private void appendSampleVariableDeclaration(StringBuilder content, Field field) {
        String fieldType = normalizeType(field.getType());
        String fieldName = field.getName();

        content.append("        ")
                .append(fieldType)
                .append(" ")
                .append(fieldName)
                .append(" = ")
                .append(resolveSampleValue(fieldType))
                .append(";\n");
    }

    /**
     * Appends a setter invocation for a field.
     *
     * @param content target source builder
     * @param entityVar entity variable name
     * @param field generated field metadata
     */
    private void appendSetterInvocation(
            StringBuilder content,
            String entityVar,
            Field field
    ) {
        content.append("        ")
                .append(entityVar)
                .append(".")
                .append(resolveSetterName(field))
                .append("(")
                .append(field.getName())
                .append(");\n");
    }

    /**
     * Appends an equality assertion for a field.
     *
     * @param content target source builder
     * @param entityVar entity variable name
     * @param field generated field metadata
     */
    private void appendEqualityAssertion(
            StringBuilder content,
            String entityVar,
            Field field
    ) {
        content.append("        ")
                .append("assertThat(")
                .append(buildGetterInvocation(entityVar, field))
                .append(").isEqualTo(")
                .append(field.getName())
                .append(");\n");
    }

    /**
     * Builds the constructor argument list for the all-args constructor.
     *
     * @param entity generated entity metadata
     * @return comma-separated constructor argument list
     */
    private String buildConstructorArgumentList(Entity entity) {
        return getEntityFields(entity).stream()
                .map(Field::getName)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    /**
     * Builds the getter invocation for a field.
     *
     * @param entityVar entity variable name
     * @param field generated field metadata
     * @return getter invocation source
     */
    private String buildGetterInvocation(String entityVar, Field field) {
        return entityVar + "." + resolveGetterName(field) + "()";
    }

    /**
     * Resolves the getter name for a field.
     *
     * @param field generated field metadata
     * @return getter method name
     */
    private String resolveGetterName(Field field) {
        String fieldName = field.getName();
        String fieldType = normalizeType(field.getType());

        if (isPrimitiveBooleanType(fieldType)) {
            if (startsWithBooleanPrefix(fieldName)) {
                return fieldName;
            }
            return "is" + NamingConverter.toPascalCase(fieldName);
        }

        return "get" + NamingConverter.toPascalCase(fieldName);
    }

    /**
     * Resolves the setter name for a field.
     *
     * @param field generated field metadata
     * @return setter method name
     */
    private String resolveSetterName(Field field) {
        String fieldName = field.getName();
        String fieldType = normalizeType(field.getType());

        if (isPrimitiveBooleanType(fieldType) && startsWithBooleanPrefix(fieldName)) {
            return "set" + fieldName.substring(2);
        }

        return "set" + NamingConverter.toPascalCase(fieldName);
    }

    /**
     * Resolves a sample Java value for the provided field type.
     *
     * @param fieldType normalized field type
     * @return sample Java literal or constructor expression
     */
    private String resolveSampleValue(String fieldType) {
        String normalizedType = normalizeType(fieldType);

        if (isListType(normalizedType)) {
            return "new ArrayList<>()";
        }
        if (isSetType(normalizedType)) {
            return "new java.util.HashSet<>()";
        }
        if (isMapType(normalizedType)) {
            return "new java.util.HashMap<>()";
        }

        return switch (normalizedType) {
            case "String" -> "\"test-value\"";
            case "UUID" -> "UUID.fromString(\"123e4567-e89b-12d3-a456-426614174000\")";
            case "Long", "long" -> "1L";
            case "Integer", "int" -> "1";
            case "Short", "short" -> "(short) 1";
            case "Byte", "byte" -> "(byte) 1";
            case "Double", "double" -> "1.0d";
            case "Float", "float" -> "1.0f";
            case "Boolean" -> "Boolean.TRUE";
            case "boolean" -> "true";
            case "Character", "char" -> "'A'";
            case "BigDecimal" -> "new BigDecimal(\"1.00\")";
            case "BigInteger" -> "new BigInteger(\"1\")";
            case "LocalDate" -> "LocalDate.of(2025, 1, 1)";
            case "LocalDateTime" -> "LocalDateTime.of(2025, 1, 1, 10, 0, 0)";
            case "LocalTime" -> "LocalTime.of(10, 0, 0)";
            case "Instant" -> "Instant.parse(\"2025-01-01T10:00:00Z\")";
            case "OffsetDateTime" -> "OffsetDateTime.parse(\"2025-01-01T10:00:00Z\")";
            case "ZonedDateTime" -> "ZonedDateTime.parse(\"2025-01-01T10:00:00Z\")";
            case "byte[]" -> "new byte[]{1}";
            case "Byte[]" -> "new Byte[]{1}";
            default -> "new " + extractRawSimpleType(normalizedType) + "()";
        };
    }


    /**
     * Normalizes a Java type by removing package prefixes and preserving simple generics.
     *
     * @param fieldType raw field type
     * @return normalized type
     */
    private String normalizeType(String fieldType) {
        String trimmedType = GeneratorSupport.trimToEmpty(fieldType);

        if (trimmedType.isBlank()) {
            return "Object";
        }

        if (trimmedType.endsWith("[]")) {
            String elementType = trimmedType.substring(0, trimmedType.length() - 2);
            return normalizeType(elementType) + "[]";
        }

        int genericStart = trimmedType.indexOf('<');
        int genericEnd = trimmedType.lastIndexOf('>');

        if (genericStart > 0 && genericEnd > genericStart) {
            String outerType = trimmedType.substring(0, genericStart).trim();
            String genericContent = trimmedType.substring(genericStart + 1, genericEnd).trim();

            List<String> normalizedArguments = splitGenericArguments(genericContent).stream()
                    .map(this::normalizeType)
                    .toList();

            return extractRawSimpleType(outerType) + "<" + String.join(", ", normalizedArguments) + ">";
        }

        return extractRawSimpleType(trimmedType);
    }

    /**
     * Splits top-level generic arguments while preserving nested generic sections.
     *
     * @param genericContent generic content without surrounding angle brackets
     * @return split generic arguments
     */
    private List<String> splitGenericArguments(String genericContent) {
        List<String> arguments = new ArrayList<>();

        if (GeneratorSupport.trimToEmpty(genericContent).isBlank()) {
            return arguments;
        }

        StringBuilder currentArgument = new StringBuilder();
        int depth = 0;

        for (int index = 0; index < genericContent.length(); index++) {
            char currentChar = genericContent.charAt(index);

            if (currentChar == '<') {
                depth++;
                currentArgument.append(currentChar);
                continue;
            }

            if (currentChar == '>') {
                depth--;
                currentArgument.append(currentChar);
                continue;
            }

            if (currentChar == ',' && depth == 0) {
                arguments.add(currentArgument.toString().trim());
                currentArgument.setLength(0);
                continue;
            }

            currentArgument.append(currentChar);
        }

        if (!currentArgument.isEmpty()) {
            arguments.add(currentArgument.toString().trim());
        }

        return arguments;
    }

    /**
     * Extracts the simple Java type name from a raw type.
     *
     * @param rawType raw Java type
     * @return simple type name
     */
    private String extractRawSimpleType(String rawType) {
        String trimmedType = GeneratorSupport.trimToEmpty(rawType);

        if (trimmedType.contains(".")) {
            return trimmedType.substring(trimmedType.lastIndexOf('.') + 1);
        }

        return trimmedType;
    }

    /**
     * Determines whether the provided field type is a List.
     *
     * @param fieldType normalized field type
     * @return true when the field type is a List
     */
    private boolean isListType(String fieldType) {
        String normalizedType = normalizeType(fieldType);
        return normalizedType.equals("List") || normalizedType.startsWith("List<");
    }

    /**
     * Determines whether the provided field type is a Set.
     *
     * @param fieldType normalized field type
     * @return true when the field type is a Set
     */
    private boolean isSetType(String fieldType) {
        String normalizedType = normalizeType(fieldType);
        return normalizedType.equals("Set") || normalizedType.startsWith("Set<");
    }

    /**
     * Determines whether the provided field type is a Map.
     *
     * @param fieldType normalized field type
     * @return true when the field type is a Map
     */
    private boolean isMapType(String fieldType) {
        String normalizedType = normalizeType(fieldType);
        return normalizedType.equals("Map") || normalizedType.startsWith("Map<");
    }

    /**
     * Determines whether the provided field type is a primitive boolean.
     *
     * @param fieldType normalized field type
     * @return true when the field type is primitive boolean
     */
    private boolean isPrimitiveBooleanType(String fieldType) {
        return "boolean".equals(normalizeType(fieldType));
    }


    /**
     * Determines whether a field name starts with the boolean "is" prefix.
     *
     * @param fieldName field name
     * @return true when the field name starts with a boolean prefix
     */
    private boolean startsWithBooleanPrefix(String fieldName) {
        return fieldName != null
                && fieldName.startsWith("is")
                && fieldName.length() > 2
                && Character.isUpperCase(fieldName.charAt(2));
    }

    /**
     * Returns the entity fields or an empty list when no fields exist.
     *
     * @param entity generated entity metadata
     * @return entity field list
     */
    private List<Field> getEntityFields(Entity entity) {
        return entity.getFields() == null ? List.of() : entity.getFields();
    }
}