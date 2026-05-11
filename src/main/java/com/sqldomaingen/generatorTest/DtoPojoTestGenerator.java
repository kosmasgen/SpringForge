package com.sqldomaingen.generatorTest;

import com.sqldomaingen.generator.DtoFieldTypeResolver;
import com.sqldomaingen.model.Entity;
import com.sqldomaingen.model.Field;
import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.util.JavaImportCollector;
import com.sqldomaingen.util.NamingConverter;
import com.sqldomaingen.util.PackageResolver;
import lombok.extern.log4j.Log4j2;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Generates POJO-style test classes for generated DTO classes.
 */
@Log4j2
public class DtoPojoTestGenerator {



    /**
     * Appends package and import statements for the DTO test class.
     *
     * @param content target source builder
     * @param testPackage target test package
     * @param dtoPackage target DTO package
     * @param entityPackage target entity package
     * @param dtoName dto simple name
     * @param dtoFields actual generated dto fields
     */
    private void appendPackageAndImports(
            StringBuilder content,
            String testPackage,
            String dtoPackage,
            String entityPackage,
            String dtoName,
            List<Field> dtoFields
    ) {
        content.append("package ").append(testPackage).append(";\n\n");

        JavaImportCollector collector = new JavaImportCollector();

        if (!testPackage.equals(dtoPackage)) {
            collector.addImport("import " + dtoPackage + "." + dtoName + ";");
        }

        collector.addImport("import org.junit.jupiter.api.Test;");
        collector.addStaticImport("import static org.assertj.core.api.Assertions.assertThat;");

        for (Field field : dtoFields) {
            if (field == null || field.getType() == null) {
                continue;
            }

            String type = field.getType();
            String normalizedType = normalizeType(type);

            collector.addImportForComplexType(type);
            collectFieldTypeImports(collector, normalizedType, entityPackage);
        }

        for (String importLine : collector.getImports()) {
            content.append(importLine).append("\n");
        }

        content.append("\n");
    }


    /**
     * Collects imports required by a generated DTO test field type.
     *
     * @param collector target import collector
     * @param normalizedType normalized field type
     * @param entityPackage target entity package
     */
    private void collectFieldTypeImports(
            JavaImportCollector collector,
            String normalizedType,
            String entityPackage
    ) {
        if (normalizedType.contains("UUID")) {
            collector.addImport("import java.util.UUID;");
        }
        if (normalizedType.contains("BigDecimal")) {
            collector.addImport("import java.math.BigDecimal;");
        }
        if (normalizedType.contains("BigInteger")) {
            collector.addImport("import java.math.BigInteger;");
        }
        if (normalizedType.contains("LocalDateTime")) {
            collector.addImport("import java.time.LocalDateTime;");
        } else if (normalizedType.contains("LocalDate")) {
            collector.addImport("import java.time.LocalDate;");
        }
        if (normalizedType.contains("LocalTime")) {
            collector.addImport("import java.time.LocalTime;");
        }

        if (isListType(normalizedType)) {
            collector.addImport("import java.util.ArrayList;");
            collector.addImport("import java.util.List;");
        }
        if (isSetType(normalizedType)) {
            collector.addImport("import java.util.HashSet;");
            collector.addImport("import java.util.Set;");
        }
        if (isMapType(normalizedType)) {
            collector.addImport("import java.util.HashMap;");
            collector.addImport("import java.util.Map;");
        }

        if (isEntityKeyType(normalizedType)) {
            collector.addImport("import " + entityPackage + "." + normalizedType + ";");
        }
    }


    /**
     * Appends the generated DTO test class declaration.
     */
    private void appendClassDeclaration(StringBuilder content, String dtoName) {
        content.append("class ").append(dtoName).append("Test {\n\n");
    }

    /**
     * Appends the no-args constructor test.
     */
    private void appendNoArgsConstructorTest(
            StringBuilder content,
            String dtoName,
            List<Field> dtoFields
    ) {
        String dtoVar = "dto";

        content.append("    /**\n");
        content.append("     * Tests the ").append(dtoName).append(" no-args constructor\n");
        content.append("     */\n");
        content.append("    @Test\n");
        content.append("    void test").append(dtoName).append("NoArgsConstructor() {\n");
        content.append("        ").append(dtoName).append(" ").append(dtoVar)
                .append(" = new ").append(dtoName).append("();\n\n");

        for (Field field : dtoFields) {
            appendNoArgsAssertion(content, dtoVar, field);
        }

        content.append("    }\n\n");
    }


    /**
     * Appends the all-args constructor test.
     */
    private void appendAllArgsConstructorTest(
            StringBuilder content,
            String dtoName,
            List<Field> dtoFields
    ) {
        String dtoVar = "dto";

        content.append("    /**\n");
        content.append("     * Tests the ").append(dtoName).append(" all-args constructor\n");
        content.append("     */\n");
        content.append("    @Test\n");
        content.append("    void test").append(dtoName).append("AllArgsConstructor() {\n");

        for (Field field : dtoFields) {
            appendSampleVariableDeclaration(content, field);
        }

        if (!dtoFields.isEmpty()) {
            content.append("\n");
        }

        content.append("        ").append(dtoName).append(" ").append(dtoVar).append(" = new ")
                .append(dtoName).append("(").append(buildConstructorArgumentList(dtoFields)).append(");\n\n");

        for (Field field : dtoFields) {
            appendEqualityAssertion(content, dtoVar, field);
        }

        content.append("    }\n\n");
    }

    /**
     * Appends the setters and getters symmetry test.
     */
    private void appendSettersAndGettersTest(
            StringBuilder content,
            String dtoName,
            List<Field> dtoFields
    ) {
        String dtoVar = "dto";

        content.append("    /**\n");
        content.append("     * Tests setters / getters symmetry\n");
        content.append("     */\n");
        content.append("    @Test\n");
        content.append("    void test").append(dtoName).append("SettersAndGetters() {\n");
        content.append("        ").append(dtoName).append(" ").append(dtoVar)
                .append(" = new ").append(dtoName).append("();\n");

        if (!dtoFields.isEmpty()) {
            content.append("\n");
        }

        for (Field field : dtoFields) {
            appendSampleVariableDeclaration(content, field);
        }

        if (!dtoFields.isEmpty()) {
            content.append("\n");
        }

        for (Field field : dtoFields) {
            appendSetterInvocation(content, dtoVar, field);
        }

        if (!dtoFields.isEmpty()) {
            content.append("\n");
        }

        for (Field field : dtoFields) {
            appendEqualityAssertion(content, dtoVar, field);
        }

        content.append("    }\n\n");
    }


    /**
     * Appends default value assertions for a DTO field in the no-args constructor test.
     *
     * @param content target source builder
     * @param dtoVar DTO variable name
     * @param field generated field metadata
     */
    private void appendNoArgsAssertion(StringBuilder content, String dtoVar, Field field) {
        String getterCall = buildGetterInvocation(dtoVar, field);
        String fieldType = resolveGeneratedDtoFieldType(field);
        String normalizedType = normalizeType(fieldType);

        if ("boolean".equals(normalizedType)) {
            content.append("        assertThat(").append(getterCall).append(").isFalse();\n");
            return;
        }

        if (isPrimitiveNumericType(fieldType)) {
            content.append("        assertThat(")
                    .append(getterCall)
                    .append(").isEqualTo(")
                    .append(resolvePrimitiveDefaultValue(fieldType))
                    .append(");\n");
            return;
        }

        if (isPrimitiveCharType(fieldType)) {
            content.append("        assertThat(")
                    .append(getterCall)
                    .append(").isEqualTo('\\u0000');\n");
            return;
        }

        content.append("        assertThat(").append(getterCall).append(").isNull();\n");
    }

    /**
     * Appends a sample variable declaration for a DTO field used in tests.
     *
     * @param content target source builder
     * @param field generated field metadata
     */
    private void appendSampleVariableDeclaration(StringBuilder content, Field field) {
        String fieldType = resolveGeneratedDtoFieldType(field);

        content.append("        ")
                .append(fieldType)
                .append(" ")
                .append(field.getName())
                .append(" = ")
                .append(resolveSampleValue(fieldType))
                .append(";\n");
    }

    /**
     * Appends a setter invocation for a DTO field.
     *
     * @param content target source builder
     * @param dtoVar DTO variable name
     * @param field generated field metadata
     */
    private void appendSetterInvocation(StringBuilder content, String dtoVar, Field field) {
        content.append("        ")
                .append(dtoVar)
                .append(".")
                .append(resolveSetterName(field))
                .append("(")
                .append(field.getName())
                .append(");\n");
    }

    /**
     * Appends an equality assertion comparing DTO getter value with expected field value.
     *
     * @param content target source builder
     * @param dtoVar DTO variable name
     * @param field generated field metadata
     */
    private void appendEqualityAssertion(StringBuilder content, String dtoVar, Field field) {
        content.append("        assertThat(")
                .append(buildGetterInvocation(dtoVar, field))
                .append(").isEqualTo(")
                .append(field.getName())
                .append(");\n");
    }

    /**
     * Builds a comma-separated argument list for DTO all-args constructor.
     *
     * @param dtoFields actual generated dto fields
     * @return constructor argument list
     */
    private String buildConstructorArgumentList(List<Field> dtoFields) {
        return dtoFields.stream()
                .map(Field::getName)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    /**
     * Builds the getter method invocation string for a DTO field.
     *
     * @param dtoVar DTO variable name
     * @param field generated field metadata
     * @return getter invocation
     */
    private String buildGetterInvocation(String dtoVar, Field field) {
        return dtoVar + "." + resolveGetterName(field) + "()";
    }

    /**
     * Resolves the correct getter method name for a DTO field.
     *
     * @param field generated field metadata
     * @return getter method name
     */
    private String resolveGetterName(Field field) {
        String fieldName = field.getName();
        String fieldType = resolveGeneratedDtoFieldType(field);
        String normalizedType = normalizeType(fieldType);

        if ("boolean".equals(normalizedType)) {
            if (startsWithBooleanPrefix(fieldName)) {
                return fieldName;
            }
            return "is" + NamingConverter.toPascalCase(fieldName);
        }

        return "get" + NamingConverter.toPascalCase(fieldName);
    }

    /**
     * Resolves the correct setter method name for a DTO field.
     *
     * @param field generated field metadata
     * @return setter method name
     */
    private String resolveSetterName(Field field) {
        String fieldName = field.getName();
        String fieldType = resolveGeneratedDtoFieldType(field);
        String normalizedType = normalizeType(fieldType);

        if ("boolean".equals(normalizedType) && startsWithBooleanPrefix(fieldName)) {
            return "set" + fieldName.substring(2);
        }

        return "set" + NamingConverter.toPascalCase(fieldName);
    }

    /**
     * Resolves a sample Java value for the given field type used in mapper tests.
     *
     * @param fieldType normalized field type
     * @return sample value expression
     */
    private String resolveSampleValue(String fieldType) {
        String normalizedType = normalizeType(fieldType);

        if (isListType(normalizedType)) {
            return "new ArrayList<>()";
        }

        if (isSetType(normalizedType)) {
            return "new HashSet<>()";
        }

        if (isMapType(normalizedType)) {
            return "new HashMap<>()";
        }

        return switch (normalizedType) {
            case "String" -> "\"test-value\"";
            case "UUID" -> "UUID.fromString(\"123e4567-e89b-12d3-a456-426614174000\")";
            case "Long", "long" -> "1L";
            case "Integer", "int" -> "1";
            case "Double", "double" -> "1.0d";
            case "Float", "float" -> "1.0f";
            case "Boolean" -> "Boolean.TRUE";
            case "boolean" -> "true";
            case "Character", "char" -> "'A'";
            case "BigDecimal" -> "new BigDecimal(\"1.00\")";
            case "BigInteger" -> "new BigInteger(\"1\")";
            case "LocalDate" -> "LocalDate.of(2025, 1, 1)";
            case "LocalDateTime" -> "LocalDateTime.of(2025, 1, 1, 10, 0)";
            case "LocalTime" -> "LocalTime.of(10, 0)";
            default -> {
                if (isDtoType(normalizedType) || isEntityKeyType(normalizedType)) {
                    yield "new " + normalizedType + "()";
                }
                yield "null";
            }
        };
    }

    /**
     * Determines whether the provided field type represents a generated DTO type.
     *
     * @param fieldType normalized field type
     * @return true when the type is a DTO class
     */
    private boolean isDtoType(String fieldType) {
        String normalizedType = normalizeType(fieldType);

        return normalizedType.endsWith("Dto")
                && !normalizedType.startsWith("List<")
                && !normalizedType.startsWith("Set<")
                && !normalizedType.startsWith("Map<");
    }


    /**
     * Determines whether the provided field type represents an embedded key type.
     *
     * @param fieldType normalized field type
     * @return true when the type is an entity key class
     */
    private boolean isEntityKeyType(String fieldType) {
        String normalizedType = normalizeType(fieldType);

        return normalizedType.endsWith("Key")
                || normalizedType.endsWith("PK")
                || normalizedType.endsWith("Id");
    }
    /**
     * Resolves the default value for primitive numeric types.
     *
     * @param fieldType normalized field type
     * @return default primitive value
     */
    private String resolvePrimitiveDefaultValue(String fieldType) {
        return switch (normalizeType(fieldType)) {
            case "long" -> "0L";
            case "float" -> "0.0f";
            case "double" -> "0.0d";
            default -> "0";
        };
    }

    /**
     * Normalizes a Java type by removing package names and simplifying generics.
     *
     * @param fieldType raw field type
     * @return normalized type
     */
    private String normalizeType(String fieldType) {
        return DtoFieldTypeResolver.simplifyType(fieldType);
    }

    /**
     * Determines whether the provided field type represents a List.
     */
    private boolean isListType(String fieldType) {
        String t = normalizeType(fieldType);
        return t.equals("List") || t.startsWith("List<");
    }

    /**
     * Determines whether the provided field type represents a Set.
     */
    private boolean isSetType(String fieldType) {
        String t = normalizeType(fieldType);
        return t.equals("Set") || t.startsWith("Set<");
    }

    /**
     * Determines whether the provided field type represents a Map.
     */
    private boolean isMapType(String fieldType) {
        String t = normalizeType(fieldType);
        return t.equals("Map") || t.startsWith("Map<");
    }




    /**
     * Determines whether the provided field type is a primitive numeric type.
     */
    private boolean isPrimitiveNumericType(String fieldType) {
        String t = normalizeType(fieldType);
        return t.equals("int") || t.equals("long") || t.equals("double") || t.equals("float");
    }

    /**
     * Determines whether the provided field type is a primitive char.
     */
    private boolean isPrimitiveCharType(String fieldType) {
        return "char".equals(normalizeType(fieldType));
    }

    /**
     * Determines whether a field name follows boolean "isX" naming convention.
     */
    private boolean startsWithBooleanPrefix(String fieldName) {
        return fieldName != null
                && fieldName.startsWith("is")
                && fieldName.length() > 2
                && Character.isUpperCase(fieldName.charAt(2));
    }

    /**
     * Resolves the exact Java type used in the generated DTO field.
     *
     * @param field generated dto field metadata
     * @return resolved type
     */
    private String resolveGeneratedDtoFieldType(Field field) {
        return field.getType();
    }


    /**
     * Generates DTO POJO test classes for all generated entities.
     *
     * @param entities generated entity metadata
     * @param outputDir project root output directory
     * @param basePackage base Java package
     * @param overwrite overwrite existing files when true
     */
    public void generateDtoPojoTests(
            List<Entity> entities,
            String outputDir,
            String basePackage,
            boolean overwrite
    ) {
        Objects.requireNonNull(entities, "entities must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        Path dtoTestDir = GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(outputDir, basePackage, "dto", true)
        );
        String testPackage = PackageResolver.resolvePackageName(basePackage, "dto");
        String dtoPackage = PackageResolver.resolvePackageName(basePackage, "dto");
        String entityPackage = PackageResolver.resolvePackageName(basePackage, "entity");

        for (Entity entity : entities) {
            if (entity == null || GeneratorSupport.trimToEmpty(entity.getName()).isBlank()) {
                continue;
            }

            String dtoName = entity.getName() + "Dto";
            String testName = dtoName + "Test";
            List<Field> dtoFields = loadGeneratedDtoFields(outputDir, basePackage, dtoName);
            String content = generateDtoPojoTestContent(
                    testPackage,
                    dtoPackage,
                    entityPackage,
                    dtoName,
                    dtoFields
            );

            GeneratorSupport.writeFile(dtoTestDir.resolve(testName + ".java"), content, overwrite);
        }

        log.debug("DTO POJO test classes generated under: {}", dtoTestDir.toAbsolutePath());
    }

    /**
     * Generates the complete source code for one DTO POJO test class.
     *
     * @param testPackage target test package
     * @param dtoPackage target DTO package
     * @param entityPackage target entity package
     * @param dtoName DTO simple name
     * @param dtoFields actual generated DTO fields
     * @return generated Java source code
     */
    private String generateDtoPojoTestContent(
            String testPackage,
            String dtoPackage,
            String entityPackage,
            String dtoName,
            List<Field> dtoFields
    ) {
        StringBuilder content = new StringBuilder();

        appendPackageAndImports(
                content,
                testPackage,
                dtoPackage,
                entityPackage,
                dtoName,
                dtoFields
        );
        appendClassDeclaration(content, dtoName);
        appendNoArgsConstructorTest(content, dtoName, dtoFields);
        appendAllArgsConstructorTest(content, dtoName, dtoFields);
        appendSettersAndGettersTest(content, dtoName, dtoFields);



        content.append("}\n");

        return content.toString();
    }

    /**
     * Loads actual generated DTO fields from the written DTO source file.
     *
     * @param outputDir project root output directory
     * @param basePackage base Java package
     * @param dtoName dto simple name
     * @return actual dto fields in declaration order
     */
    private List<Field> loadGeneratedDtoFields(
            String outputDir,
            String basePackage,
            String dtoName
    ) {
        Path dtoPath = PackageResolver.resolvePath(outputDir, basePackage, "dto")
                .resolve(dtoName + ".java");

        if (!java.nio.file.Files.exists(dtoPath)) {
            return List.of();
        }

        List<Field> dtoFields = new java.util.ArrayList<>();
        java.util.regex.Pattern fieldPattern =
                java.util.regex.Pattern.compile("^\\s*private\\s+(.+?)\\s+(\\w+)\\s*;\\s*$");

        try {
            for (String line : java.nio.file.Files.readAllLines(dtoPath)) {
                java.util.regex.Matcher matcher = fieldPattern.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }

                String fieldType = matcher.group(1).trim();
                String fieldName = matcher.group(2).trim();

                dtoFields.add(Field.builder()
                        .name(fieldName)
                        .type(fieldType)
                        .build());
            }
        } catch (java.io.IOException exception) {
            log.warn("Could not read DTO file for DTO POJO test generation: {}", dtoPath, exception);
        }

        return dtoFields;
    }


}