package com.sqldomaingen.generatorTest;

import com.sqldomaingen.generator.DtoFieldTypeResolver;
import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.Entity;
import com.sqldomaingen.model.Field;
import com.sqldomaingen.model.Table;
import com.sqldomaingen.util.*;
import lombok.extern.log4j.Log4j2;

import java.nio.file.Path;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Generates mapper test classes for generated mappers.
 * <p>
 * The generated tests use the real mapper implementation with a real
 * {@code ModelMapper} instance and verify:
 * <ul>
 *     <li>entity to DTO mapping</li>
 *     <li>DTO to entity mapping</li>
 *     <li>list mapping in both directions</li>
 *     <li>full partial update behavior from {@code BaseMapper}</li>
 * </ul>
 */
@Log4j2
public class MapperTestGenerator {

    /**
     * Generates mapper test classes for all parsed tables.
     *
     * @param tables parsed SQL tables
     * @param entities generated entity metadata
     * @param outputDir project root output directory
     * @param basePackage base Java package
     * @param overwrite overwrite existing files when true
     */
    public void generateMapperTests(
            List<Table> tables,
            List<Entity> entities,
            String outputDir,
            String basePackage,
            boolean overwrite
    ) {
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(entities, "entities must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        Path mapperTestDir = GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(outputDir, basePackage, "mapper", true)
        );

        String testPackage = PackageResolver.resolvePackageName(basePackage, "mapper");
        String mapperPackage = PackageResolver.resolvePackageName(basePackage, "mapper");
        String entityPackage = PackageResolver.resolvePackageName(basePackage, "entity");
        String dtoPackage = PackageResolver.resolvePackageName(basePackage, "dto");

        for (Table table : tables) {
            if (table == null || GeneratorSupport.trimToEmpty(table.getName()).isBlank()) {
                continue;
            }

            if (table.isPureJoinTable()) {
                log.info("Skipping mapper test generation for pure join table: {}", table.getName());
                continue;
            }

            String entityName = NamingConverter.toPascalCase(
                    GeneratorSupport.normalizeTableName(table.getName())
            );
            Entity entityMetadata = findEntityMetadata(entities, entityName);

            if (entityMetadata == null) {
                throw new IllegalStateException(
                        "No generated Entity metadata found for mapper test generation: " + entityName
                );
            }

            String dtoName = entityName + "Dto";
            String mapperName = entityName + "Mapper";
            String testName = mapperName + "Test";
            List<Field> dtoFields = loadGeneratedDtoFields(outputDir, basePackage, dtoName);

            String content = generateMapperTestContent(
                    table,
                    entities,
                    entityMetadata,
                    dtoFields,
                    testPackage,
                    mapperPackage,
                    entityPackage,
                    dtoPackage,
                    entityName,
                    dtoName,
                    mapperName
            );

            GeneratorSupport.writeFile(
                    mapperTestDir.resolve(testName + ".java"),
                    content,
                    overwrite
            );
        }

        log.debug("Mapper test classes generated under: {}", mapperTestDir.toAbsolutePath());
    }

    /**
     * Generates the full Java source code for a single mapper test class.
     *
     * @param table current table
     * @param entities all generated entity metadata
     * @param entityMetadata generated entity metadata
     * @param dtoFields actual generated DTO fields
     * @param testPackage target test package
     * @param mapperPackage target mapper package
     * @param entityPackage target entity package
     * @param dtoPackage target dto package
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param mapperName mapper simple name
     * @return generated Java source code
     */
    private String generateMapperTestContent(
            Table table,
            List<Entity> entities,
            Entity entityMetadata,
            List<Field> dtoFields,
            String testPackage,
            String mapperPackage,
            String entityPackage,
            String dtoPackage,
            String entityName,
            String dtoName,
            String mapperName
    ) {
        StringBuilder content = new StringBuilder();

        appendPackageAndImports(
                content,
                entities,
                entityMetadata,
                dtoFields,
                testPackage,
                mapperPackage,
                entityPackage,
                dtoPackage,
                entityName,
                dtoName,
                mapperName
        );

        appendClassHeader(content, mapperName);
        appendEntityToDtoTest(content, entityName, dtoName);
        appendDtoToEntityTest(content, entityName, dtoName);
        appendEntityListToDtoListTest(content, entityName, dtoName);
        appendDtoListToEntityListTest(content, entityName, dtoName);
        appendPartialUpdateTest(content, table, entityMetadata, entityName, dtoName);
        appendNullAndEmptyListTests(content, entityName, dtoName);
        appendNullPartialUpdateTest(content, entityName);
        appendRootFixtureMethods(content, entities, entityMetadata, dtoFields, entityName, dtoName, table);
        content.append("}\n");

        return content.toString();
    }

    /**
     * Appends package and import statements for the generated mapper test class.
     *
     * @param content target source builder
     * @param entities all generated entity metadata
     * @param entityMetadata generated entity metadata
     * @param dtoFields actual generated DTO fields
     * @param testPackage target test package
     * @param mapperPackage target mapper package
     * @param entityPackage target entity package
     * @param dtoPackage target dto package
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param mapperName mapper simple name
     */
    private void appendPackageAndImports(
            StringBuilder content,
            List<Entity> entities,
            Entity entityMetadata,
            List<Field> dtoFields,
            String testPackage,
            String mapperPackage,
            String entityPackage,
            String dtoPackage,
            String entityName,
            String dtoName,
            String mapperName
    ) {
        content.append("package ").append(testPackage).append(";\n\n");

        Set<String> imports = new TreeSet<>();

        if (!testPackage.equals(mapperPackage)) {
            imports.add("import " + mapperPackage + "." + mapperName + ";");
        }

        imports.add("import " + entityPackage + "." + entityName + ";");
        imports.add("import " + dtoPackage + "." + dtoName + ";");
        imports.add("import java.util.List;");
        imports.add("import org.junit.jupiter.api.BeforeEach;");
        imports.add("import org.junit.jupiter.api.Test;");
        imports.add("import org.modelmapper.ModelMapper;");

        collectDirectFixtureImports(imports, entities, getEntityFields(entityMetadata), entityPackage, dtoPackage, false);
        collectDirectFixtureImports(imports, entities, dtoFields, entityPackage, dtoPackage, true);

        for (String importLine : imports) {
            content.append(importLine).append("\n");
        }

        content.append("\n");
        content.append("import static org.assertj.core.api.Assertions.assertThat;\n\n");
    }

    /**
     * Collects imports required only by direct fixture fields generated in this mapper test.
     *
     * @param imports target import set
     * @param entities all generated entity metadata
     * @param fields direct fields used by the generated fixture
     * @param entityPackage target entity package
     * @param dtoPackage target dto package
     * @param dtoMode true when collecting DTO fixture imports, false for entity fixture imports
     */
    private void collectDirectFixtureImports(
            Set<String> imports,
            List<Entity> entities,
            List<Field> fields,
            String entityPackage,
            String dtoPackage,
            boolean dtoMode
    ) {
        for (Field field : fields) {
            if (field == null) {
                continue;
            }

            String fieldType = normalizeType(field.getType());
            collectRequiredImports(imports, fieldType, entityPackage, dtoPackage);

            if (!isProjectType(fieldType)) {
                continue;
            }

            String nestedType = extractPrimarySimpleType(fieldType);
            String importLine = ProjectTypeImportSupport.resolveImportLine(nestedType, entityPackage, dtoPackage);

            if (!importLine.isBlank()) {
                imports.add(importLine);
            }

            Entity nestedMetadata = findNestedProjectMetadata(entities, nestedType, dtoMode);

            if (nestedMetadata == null) {
                continue;
            }

            for (Field nestedField : getEntityFields(nestedMetadata)) {
                if (nestedField == null) {
                    continue;
                }

                String nestedFieldType = normalizeType(nestedField.getType());

                if (!isProjectType(nestedFieldType)) {
                    collectRequiredImports(imports, nestedFieldType, entityPackage, dtoPackage);
                }
            }
        }
    }

    /**
     * Collects only the imports that are actually required by the provided field type.
     *
     * @param imports target import set
     * @param fieldType normalized field type
     * @param entityPackage target entity package
     * @param dtoPackage target dto package
     */
    private void collectRequiredImports(
            Set<String> imports,
            String fieldType,
            String entityPackage,
            String dtoPackage
    ) {
        String normalizedType = normalizeType(fieldType);

        for (String type : extractReferencedProjectTypes(normalizedType)) {
            String importLine = ProjectTypeImportSupport.resolveImportLine(type, entityPackage, dtoPackage);
            if (!importLine.isBlank()) {
                imports.add(importLine);
            }
        }

        if (normalizedType.contains("UUID")) {
            imports.add("import java.util.UUID;");
        }
        if (normalizedType.contains("BigDecimal")) {
            imports.add("import java.math.BigDecimal;");
        }
        if (normalizedType.contains("BigInteger")) {
            imports.add("import java.math.BigInteger;");
        }
        if (normalizedType.contains("Instant")) {
            imports.add("import java.time.Instant;");
        }
        if (normalizedType.contains("OffsetDateTime")) {
            imports.add("import java.time.OffsetDateTime;");
        }
        if (normalizedType.contains("ZonedDateTime")) {
            imports.add("import java.time.ZonedDateTime;");
        }
        if (normalizedType.contains("LocalDateTime")) {
            imports.add("import java.time.LocalDateTime;");
        } else if (normalizedType.contains("LocalDate")) {
            imports.add("import java.time.LocalDate;");
        }
        if (normalizedType.contains("LocalTime")) {
            imports.add("import java.time.LocalTime;");
        }
    }


    /**
     * Appends the test class header.
     *
     * @param content target source builder
     * @param mapperName mapper simple name
     */
    private void appendClassHeader(StringBuilder content, String mapperName) {
        String mapperVar = NamingConverter.decapitalizeFirstLetter(mapperName);

        content.append("class ").append(mapperName).append("Test {\n\n");
        content.append("    private ").append(mapperName).append(" ").append(mapperVar).append(";\n\n");
        content.append("    @BeforeEach\n");
        content.append("    void setUp() {\n");
        content.append("        ModelMapper modelMapper = new ModelMapper();\n");
        content.append("        ").append(mapperVar).append(" = new ").append(mapperName).append("(modelMapper);\n");
        content.append("    }\n\n");
    }



    /**
     * Appends the entity to DTO mapping test.
     *
     * @param content target source builder
     * @param entityName entity simple name
     * @param dtoName dto simple name
     */
    private void appendEntityToDtoTest(StringBuilder content, String entityName, String dtoName) {
        String mapperVar = NamingConverter.decapitalizeFirstLetter(entityName) + "Mapper";

        content.append("    /**\n");
        content.append("     * Verifies full entity to DTO mapping, including nested project types.\n");
        content.append("     */\n");
        content.append("    @Test\n");
        content.append("    void shouldMap").append(entityName).append("To").append(dtoName).append("() {\n");
        content.append("        ").append(entityName).append(" entity = createSample").append(entityName).append("Entity();\n");
        content.append("        ").append(dtoName).append(" expectedDto = createSample").append(entityName).append("Dto();\n\n");
        content.append("        ").append(dtoName).append(" actualDto = ").append(mapperVar).append(".toDTO(entity);\n\n");
        content.append("        assertThat(actualDto)\n");
        content.append("                .usingRecursiveComparison()\n");
        content.append("                .isEqualTo(expectedDto);\n");
        content.append("    }\n\n");
    }

    /**
     * Appends the DTO to entity mapping test.
     *
     * @param content target source builder
     * @param entityName entity simple name
     * @param dtoName dto simple name
     */
    private void appendDtoToEntityTest(StringBuilder content, String entityName, String dtoName) {
        String mapperVar = NamingConverter.decapitalizeFirstLetter(entityName) + "Mapper";

        content.append("    /**\n");
        content.append("     * Verifies full DTO to entity mapping, including nested project types.\n");
        content.append("     */\n");
        content.append("    @Test\n");
        content.append("    void shouldMap").append(dtoName).append("To").append(entityName).append("() {\n");
        content.append("        ").append(dtoName).append(" dto = createSample").append(entityName).append("Dto();\n");
        content.append("        ").append(entityName).append(" expectedEntity = createSample").append(entityName).append("Entity();\n\n");
        content.append("        ").append(entityName).append(" actualEntity = ").append(mapperVar).append(".toEntity(dto);\n\n");
        content.append("        assertThat(actualEntity)\n");
        content.append("                .usingRecursiveComparison()\n");
        content.append("                .isEqualTo(expectedEntity);\n");
        content.append("    }\n\n");
    }

    /**
     * Appends the entity list to DTO list mapping test.
     *
     * @param content target source builder
     * @param entityName entity simple name
     * @param dtoName dto simple name
     */
    private void appendEntityListToDtoListTest(StringBuilder content, String entityName, String dtoName) {
        String mapperVar = NamingConverter.decapitalizeFirstLetter(entityName) + "Mapper";

        content.append("    /**\n");
        content.append("     * Verifies full entity list to DTO list mapping.\n");
        content.append("     */\n");
        content.append("    @Test\n");
        content.append("    void shouldMap").append(entityName).append("ListTo").append(dtoName).append("List() {\n");
        content.append("        List<").append(entityName).append("> entityList = List.of(\n");
        content.append("                createSample").append(entityName).append("Entity(),\n");
        content.append("                createAnother").append(entityName).append("Entity()\n");
        content.append("        );\n");
        content.append("        List<").append(dtoName).append("> expectedDtoList = List.of(\n");
        content.append("                createSample").append(entityName).append("Dto(),\n");
        content.append("                createAnother").append(entityName).append("Dto()\n");
        content.append("        );\n\n");
        content.append("        List<").append(dtoName).append("> actualDtoList = ").append(mapperVar).append(".toDTOList(entityList);\n\n");
        content.append("        assertThat(actualDtoList)\n");
        content.append("                .usingRecursiveFieldByFieldElementComparator()\n");
        content.append("                .containsExactlyElementsOf(expectedDtoList);\n");
        content.append("    }\n\n");
    }

    /**
     * Appends the DTO list to entity list mapping test.
     *
     * @param content target source builder
     * @param entityName entity simple name
     * @param dtoName dto simple name
     */
    private void appendDtoListToEntityListTest(StringBuilder content, String entityName, String dtoName) {
        String mapperVar = NamingConverter.decapitalizeFirstLetter(entityName) + "Mapper";

        content.append("    /**\n");
        content.append("     * Verifies full DTO list to entity list mapping.\n");
        content.append("     */\n");
        content.append("    @Test\n");
        content.append("    void shouldMap").append(dtoName).append("ListTo").append(entityName).append("List() {\n");
        content.append("        List<").append(dtoName).append("> dtoList = List.of(\n");
        content.append("                createSample").append(entityName).append("Dto(),\n");
        content.append("                createAnother").append(entityName).append("Dto()\n");
        content.append("        );\n");
        content.append("        List<").append(entityName).append("> expectedEntityList = List.of(\n");
        content.append("                createSample").append(entityName).append("Entity(),\n");
        content.append("                createAnother").append(entityName).append("Entity()\n");
        content.append("        );\n\n");
        content.append("        List<").append(entityName).append("> actualEntityList = ").append(mapperVar).append(".toEntityList(dtoList);\n\n");
        content.append("        assertThat(actualEntityList)\n");
        content.append("                .usingRecursiveFieldByFieldElementComparator()\n");
        content.append("                .containsExactlyElementsOf(expectedEntityList);\n");
        content.append("    }\n\n");
    }

    /**
     * Appends the full partial update behavior test.
     *
     * @param content target source builder
     * @param table current table
     * @param entityMetadata generated entity metadata
     * @param entityName entity simple name
     * @param dtoName dto simple name
     */
    private void appendPartialUpdateTest(
            StringBuilder content,
            Table table,
            Entity entityMetadata,
            String entityName,
            String dtoName
    ) {
        String mapperVar = NamingConverter.decapitalizeFirstLetter(entityName) + "Mapper";
        Set<String> primaryKeyFieldNames = resolvePrimaryKeyFieldNames(table);
        List<Field> entityFields = getEntityFields(entityMetadata);

        content.append("    /**\n");
        content.append("     * Verifies that partialUpdate replaces every non-null mapped field,\n");
        content.append("     * preserves null-patched fields from the original entity,\n");
        content.append("     * and never changes primary key fields.\n");
        content.append("     */\n");
        content.append("    @Test\n");
        content.append("    void shouldApplyFullPartialUpdateFor").append(entityName).append("() {\n");
        content.append("        ").append(entityName).append(" originalEntity = createSample").append(entityName).append("Entity();\n");
        content.append("        ").append(entityName).append(" actualEntity = createSample").append(entityName).append("Entity();\n");
        content.append("        ").append(dtoName).append(" patchDto = createPatch").append(entityName).append("Dto();\n");
        content.append("        ").append(entityName).append(" patchEntity = ").append(mapperVar).append(".toEntity(patchDto);\n\n");
        content.append("        ").append(mapperVar).append(".partialUpdate(actualEntity, patchDto);\n\n");

        for (Field field : entityFields) {
            String fieldName = field.getName();
            String actualGetter = buildGetterInvocation("actualEntity", field);
            String originalGetter = buildGetterInvocation("originalEntity", field);
            String patchGetter = buildGetterInvocation("patchEntity", field);
            String expectedVariableName = fieldName + "ExpectedValue";

            if (primaryKeyFieldNames.contains(fieldName)) {
                appendGeneratedComparisonAssertion(content, actualGetter, originalGetter, field);
                content.append("\n");
                continue;
            }

            content.append("        Object ").append(expectedVariableName).append(" = ")
                    .append(patchGetter).append(" != null ? ")
                    .append(patchGetter).append(" : ")
                    .append(originalGetter).append(";\n");

            appendGeneratedComparisonAssertion(content, actualGetter, expectedVariableName, field);
            content.append("\n");
        }

        content.append("    }\n\n");
    }

    /**
     * Appends null and empty list behavior tests.
     *
     * @param content target source builder
     * @param entityName entity simple name
     * @param dtoName dto simple name
     */
    private void appendNullAndEmptyListTests(StringBuilder content, String entityName, String dtoName) {
        String mapperVar = NamingConverter.decapitalizeFirstLetter(entityName) + "Mapper";

        content.append("    /**\n");
        content.append("     * Verifies that entity list mapping returns an empty list for null and empty input.\n");
        content.append("     */\n");
        content.append("    @Test\n");
        content.append("    void shouldReturnEmpty").append(dtoName).append("ListForNullOrEmpty").append(entityName).append("List() {\n");
        content.append("        assertThat(").append(mapperVar).append(".toDTOList(null)).isEmpty();\n");
        content.append("        assertThat(").append(mapperVar).append(".toDTOList(List.of())).isEmpty();\n");
        content.append("    }\n\n");

        content.append("    /**\n");
        content.append("     * Verifies that DTO list mapping returns an empty list for null and empty input.\n");
        content.append("     */\n");
        content.append("    @Test\n");
        content.append("    void shouldReturnEmpty").append(entityName).append("ListForNullOrEmpty").append(dtoName).append("List() {\n");
        content.append("        assertThat(").append(mapperVar).append(".toEntityList(null)).isEmpty();\n");
        content.append("        assertThat(").append(mapperVar).append(".toEntityList(List.of())).isEmpty();\n");
        content.append("    }\n\n");
    }

    /**
     * Appends null argument safety tests for partial update.
     *
     * @param content target source builder
     * @param entityName entity simple name
     */
    private void appendNullPartialUpdateTest(StringBuilder content, String entityName) {
        String mapperVar = NamingConverter.decapitalizeFirstLetter(entityName) + "Mapper";

        content.append("    /**\n");
        content.append("     * Verifies that partialUpdate safely ignores null inputs.\n");
        content.append("     */\n");
        content.append("    @Test\n");
        content.append("    void shouldIgnoreNullArgumentsInPartialUpdate() {\n");
        content.append("        ").append(entityName).append(" entity = createSample").append(entityName).append("Entity();\n");
        content.append("        ").append(entityName).append(" expectedEntity = createSample").append(entityName).append("Entity();\n\n");
        content.append("        ").append(mapperVar).append(".partialUpdate(entity, null);\n");
        content.append("        ").append(mapperVar).append(".partialUpdate(null, createPatch").append(entityName).append("Dto());\n\n");
        content.append("        assertThat(entity)\n");
        content.append("                .usingRecursiveComparison()\n");
        content.append("                .isEqualTo(expectedEntity);\n");
        content.append("    }\n\n");
    }

    /**
     * Appends a generated assertion for a specific field comparison.
     *
     * @param content target source builder
     * @param actualExpression actual value expression
     * @param expectedExpression expected value expression
     * @param field source field
     */
    private void appendGeneratedComparisonAssertion(
            StringBuilder content,
            String actualExpression,
            String expectedExpression,
            Field field
    ) {
        content.append("        assertThat(").append(actualExpression).append(")\n");

        if (shouldUseRecursiveComparison(field)) {
            content.append("                .usingRecursiveComparison()\n");
        }

        content.append("                .isEqualTo(").append(expectedExpression).append(");\n");
    }

    /**
     * Determines whether the generated assertion for a field should use recursive comparison.
     *
     * @param field source field
     * @return true when recursive comparison should be generated
     */
    private boolean shouldUseRecursiveComparison(Field field) {
        String fieldType = normalizeType(field.getType());

        if (fieldType.isBlank()) {
            return false;
        }

        if (JavaTypeSupport.isScalarType(fieldType)) {
            return false;
        }

        if (JavaTypeSupport.isCollectionType(fieldType)) {
            return true;
        }

        return isProjectType(fieldType);
    }


    /**
     * Appends root fixture builder methods for entity and DTO samples.
     *
     * @param content target source builder
     * @param entities all generated entity metadata
     * @param entityMetadata generated entity metadata
     * @param dtoFields actual generated DTO fields
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param table current table metadata
     */
    private void appendRootFixtureMethods(
            StringBuilder content,
            List<Entity> entities,
            Entity entityMetadata,
            List<Field> dtoFields,
            String entityName,
            String dtoName,
            Table table
    ) {
        appendEntityFixtureMethod(
                content,
                entities,
                entityMetadata,
                entityName,
                "createSample" + entityName + "Entity",
                1
        );

        appendEntityFixtureMethod(
                content,
                entities,
                entityMetadata,
                entityName,
                "createAnother" + entityName + "Entity",
                2
        );

        appendDtoFixtureMethod(
                content,
                entities,
                dtoFields,
                dtoName,
                "createSample" + entityName + "Dto",
                1,
                false,
                table
        );

        appendDtoFixtureMethod(
                content,
                entities,
                dtoFields,
                dtoName,
                "createAnother" + entityName + "Dto",
                2,
                false,
                table
        );

        appendDtoFixtureMethod(
                content,
                entities,
                dtoFields,
                dtoName,
                "createPatch" + entityName + "Dto",
                3,
                true,
                table
        );
    }





    /**
     * Appends a root entity fixture method.
     *
     * @param content target source builder
     * @param entities all generated entity metadata
     * @param entityMetadata generated entity metadata
     * @param entityName entity simple name
     * @param methodName method name
     * @param variant sample variant index
     */
    private void appendEntityFixtureMethod(
            StringBuilder content,
            List<Entity> entities,
            Entity entityMetadata,
            String entityName,
            String methodName,
            int variant
    ) {
        content.append("    /**\n");
        content.append("     * Creates a populated ").append(entityName).append(" fixture for mapper tests.\n");
        content.append("     *\n");
        content.append("     * @return populated entity fixture\n");
        content.append("     */\n");
        content.append("    private ").append(entityName).append(" ").append(methodName).append("() {\n");
        content.append("        ").append(entityName).append(" entity = new ").append(entityName).append("();\n");
        appendEntityFixtureSetterLines(content, entities, entityMetadata, variant);
        content.append("        return entity;\n");
        content.append("    }\n\n");
    }

    /**
     * Appends a root dto fixture method.
     *
     * @param content target source builder
     * @param entities all generated entity metadata
     * @param dtoFields actual generated DTO fields
     * @param dtoName dto simple name
     * @param methodName method name
     * @param variant sample variant index
     * @param skipPrimaryKeys whether primary key fields should be skipped
     * @param table current table
     */
    private void appendDtoFixtureMethod(
            StringBuilder content,
            List<Entity> entities,
            List<Field> dtoFields,
            String dtoName,
            String methodName,
            int variant,
            boolean skipPrimaryKeys,
            Table table
    ) {
        Set<String> primaryKeyFieldNames = resolvePrimaryKeyFieldNames(table);

        content.append("    /**\n");
        content.append("     * Creates a populated ").append(dtoName).append(" fixture for mapper tests.\n");
        content.append("     *\n");
        content.append("     * @return populated dto fixture\n");
        content.append("     */\n");
        content.append("    private ").append(dtoName).append(" ").append(methodName).append("() {\n");
        content.append("        ").append(dtoName).append(" dto = new ").append(dtoName).append("();\n");
        appendDtoFixtureSetterLines(content, entities, dtoFields, variant, skipPrimaryKeys, primaryKeyFieldNames);
        content.append("        return dto;\n");
        content.append("    }\n\n");
    }

    /**
     * Appends root DTO fixture setter lines.
     *
     * @param content target source builder
     * @param entities all generated entity metadata
     * @param dtoFields actual generated DTO fields
     * @param variant sample variant index
     * @param skipPrimaryKeys whether primary key fields should be skipped
     * @param primaryKeyFieldNames primary key field names
     */
    private void appendDtoFixtureSetterLines(
            StringBuilder content,
            List<Entity> entities,
            List<Field> dtoFields,
            int variant,
            boolean skipPrimaryKeys,
            Set<String> primaryKeyFieldNames
    ) {
        for (Field field : dtoFields) {
            if (skipPrimaryKeys && primaryKeyFieldNames.contains(field.getName())) {
                continue;
            }

            if (variant == 3 && shouldReturnNullInPatch(field)) {
                continue;
            }

            String fieldType = normalizeType(field.getType());

            if (isProjectType(fieldType)) {
                appendNestedProjectTypeSetterLines(
                        content,
                        entities,
                        "dto",
                        field,
                        variant,
                        true
                );
                continue;
            }

            String literal = sampleLiteralForGeneratedDtoField(field, variant);

            if ("null".equals(literal)) {
                continue;
            }

            content.append("        dto.")
                    .append(resolveSetterName(field))
                    .append("(")
                    .append(literal)
                    .append(");\n");
        }

        content.append("\n");
    }


    /**
     * Produces a sample literal for an actual generated DTO field.
     *
     * @param field generated DTO field
     * @param variant sample variant index
     * @return Java literal
     */
    private String sampleLiteralForGeneratedDtoField(Field field, int variant) {
        String fieldType = normalizeType(field.getType());

        if (fieldType.isBlank()) {
            return "null";
        }

        if (variant == 0) {
            return "null";
        }

        if (variant == 3 && shouldReturnNullInPatch(field)) {
            return "null";
        }

        if (isListType(fieldType)) {
            return "new java.util.ArrayList<>()";
        }

        if (isSetType(fieldType)) {
            return "new java.util.HashSet<>()";
        }

        if (isMapType(fieldType)) {
            return "new java.util.HashMap<>()";
        }

        if (isProjectType(fieldType)) {
            return "null";
        }

        return sampleLiteralForSimpleType(fieldType, field.getName(), variant);
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
            log.warn("Could not read DTO file for mapper test generation: {}", dtoPath, exception);
        }

        return dtoFields;
    }

    /**
     * Appends root entity fixture setter lines.
     *
     * @param content target source builder
     * @param entities all generated entity metadata
     * @param entityMetadata generated entity metadata
     * @param variant sample variant index
     */
    private void appendEntityFixtureSetterLines(
            StringBuilder content,
            List<Entity> entities,
            Entity entityMetadata,
            int variant
    ) {
        for (Field field : getEntityFields(entityMetadata)) {
            String fieldType = normalizeType(field.getType());

            if (isProjectType(fieldType)) {
                appendNestedProjectTypeSetterLines(
                        content,
                        entities,
                        "entity",
                        field,
                        variant,
                        false
                );
                continue;
            }

            String literal = sampleLiteralForEntityField(field, variant);

            if ("null".equals(literal)) {
                continue;
            }

            content.append("        entity.")
                    .append(resolveSetterName(field))
                    .append("(")
                    .append(literal)
                    .append(");\n");
        }

        content.append("\n");
    }



    /**
     * Finds matching entity metadata by simple name.
     *
     * @param entities generated entity metadata
     * @param entityName entity simple name
     * @return matching entity metadata, or null when not found
     */
    private Entity findEntityMetadata(List<Entity> entities, String entityName) {
        return entities.stream()
                .filter(Objects::nonNull)
                .filter(entity -> entityName.equals(entity.getName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the generated fields for the given entity.
     *
     * @param entity generated entity metadata
     * @return safe entity fields
     */
    private List<Field> getEntityFields(Entity entity) {
        if (entity == null || entity.getFields() == null) {
            return List.of();
        }

        return entity.getFields().stream()
                .filter(Objects::nonNull)
                .toList();
    }


    /**
     * Resolves the setter name for the provided field.
     *
     * @param field source field
     * @return setter method name
     */
    private String resolveSetterName(Field field) {
        return "set" + NamingConverter.toPascalCase(field.getName());
    }

    /**
     * Builds a getter invocation for the provided target variable and field.
     *
     * @param targetVariable target variable name
     * @param field source field
     * @return getter invocation
     */
    private String buildGetterInvocation(String targetVariable, Field field) {
        String fieldType = normalizeType(field.getType());
        String getterPrefix = "boolean".equals(fieldType) ? "is" : "get";

        return targetVariable + "." + getterPrefix + NamingConverter.toPascalCase(field.getName()) + "()";
    }

    /**
     * Resolves primary key field names from the source table metadata.
     *
     * @param table source table
     * @return primary key field names converted to generated Java names
     */
    private Set<String> resolvePrimaryKeyFieldNames(Table table) {
        Set<String> primaryKeyFieldNames = new LinkedHashSet<>();
        int primaryKeyCount = 0;

        if (table == null || table.getColumns() == null) {
            return primaryKeyFieldNames;
        }

        for (Column column : table.getColumns()) {
            if (column != null && column.isPrimaryKey()) {
                primaryKeyCount++;
                primaryKeyFieldNames.add(NamingConverter.toCamelCase(column.getName()));
            }
        }

        if (primaryKeyCount > 1) {
            primaryKeyFieldNames.add("id");
        }

        return primaryKeyFieldNames;
    }

    /**
     * Produces a sample literal for an entity field.
     *
     * @param field source field
     * @param variant sample variant index
     * @return Java literal
     */
    private String sampleLiteralForEntityField(Field field, int variant) {
        String fieldType = normalizeType(field.getType());

        if (fieldType.isBlank()) {
            return "null";
        }

        if (variant == 0) {
            return "null";
        }

        if (isListType(fieldType)) {
            return "new java.util.ArrayList<>()";
        }

        if (isSetType(fieldType)) {
            return "new java.util.HashSet<>()";
        }

        if (isMapType(fieldType)) {
            return "new java.util.HashMap<>()";
        }

        if (isProjectType(fieldType)) {
            return "null";
        }

        return sampleLiteralForSimpleType(fieldType, field.getName(), variant);
    }

    /**
     * Appends nested project type fixture initialization and population lines.
     *
     * @param content target source builder
     * @param entities all generated entity metadata
     * @param targetVariable target variable name, such as entity or dto
     * @param field source field
     * @param variant sample variant index
     * @param dtoMode true when the nested object is a DTO, false for entity mode
     */
    private void appendNestedProjectTypeSetterLines(
            StringBuilder content,
            List<Entity> entities,
            String targetVariable,
            Field field,
            int variant,
            boolean dtoMode
    ) {
        String fieldType = normalizeType(field.getType());
        String nestedType = extractPrimarySimpleType(fieldType);

        if (nestedType.isBlank()) {
            return;
        }

        Entity nestedMetadata = findNestedProjectMetadata(entities, nestedType, dtoMode);
        String nestedVariableName = NamingConverter.toCamelCase(field.getName()) + "Fixture" + variant;

        content.append("        ")
                .append(nestedType)
                .append(" ")
                .append(nestedVariableName)
                .append(" = new ")
                .append(nestedType)
                .append("();\n");

        if (nestedMetadata != null) {
            for (Field nestedField : getEntityFields(nestedMetadata)) {
                if (variant == 3 && dtoMode && shouldReturnNullInPatch(nestedField)) {
                    continue;
                }

                String nestedFieldType = normalizeType(nestedField.getType());

                if (isProjectType(nestedFieldType)) {
                    continue;
                }

                String literal = dtoMode
                        ? sampleLiteralForGeneratedDtoField(nestedField, variant)
                        : sampleLiteralForEntityField(nestedField, variant);

                if ("null".equals(literal)) {
                    continue;
                }

                content.append("        ")
                        .append(nestedVariableName)
                        .append(".")
                        .append(resolveSetterName(nestedField))
                        .append("(")
                        .append(literal)
                        .append(");\n");
            }
        }

        content.append("        ")
                .append(targetVariable)
                .append(".")
                .append(resolveSetterName(field))
                .append("(")
                .append(nestedVariableName)
                .append(");\n");
    }

    /**
     * Finds metadata for a nested project type.
     *
     * @param entities all generated entity metadata
     * @param nestedType nested simple type name
     * @param dtoMode true when resolving a DTO type
     * @return matching entity metadata or null when not found
     */
    private Entity findNestedProjectMetadata(
            List<Entity> entities,
            String nestedType,
            boolean dtoMode
    ) {
        if (GeneratorSupport.trimToEmpty(nestedType).isBlank()) {
            return null;
        }

        String entityName = dtoMode && nestedType.endsWith("Dto")
                ? nestedType.substring(0, nestedType.length() - 3)
                : nestedType;

        return findEntityMetadata(entities, entityName);
    }


    /**
     * Determines whether a DTO patch fixture should keep the field null.
     *
     * @param field source entity field
     * @return true when the generated patch dto should leave the field null
     */
    private boolean shouldReturnNullInPatch(Field field) {
        String fieldName = GeneratorSupport.trimToEmpty(field.getName());

        if (fieldName.isBlank()) {
            return false;
        }

        return Math.abs(fieldName.hashCode()) % 3 == 0;
    }

    /**
     * Produces a sample literal for a simple scalar type.
     *
     * @param typeName type name
     * @param fieldName field name
     * @param variant sample variant index
     * @return Java literal
     */
    private String sampleLiteralForSimpleType(String typeName, String fieldName, int variant) {
        String normalizedType = normalizeType(typeName);
        String normalizedFieldName = GeneratorSupport.trimToEmpty(fieldName);

        return switch (normalizedType) {
            case "String" -> "\"" + normalizedFieldName + "Value" + variant + "\"";
            case "Integer", "int" -> String.valueOf(variant * 10);
            case "Long", "long" -> variant * 100L + "L";
            case "Short", "short" -> "(short) " + (variant * 2);
            case "Byte", "byte" -> "(byte) " + variant;
            case "Double", "double" -> variant + ".5d";
            case "Float", "float" -> variant + ".5f";
            case "Boolean", "boolean" -> variant % 2 != 0 ? "true" : "false";
            case "Character", "char" -> variant % 2 != 0 ? "'A'" : "'B'";
            case "BigDecimal" -> "new BigDecimal(\"" + variant + "00.50\")";
            case "BigInteger" -> "new BigInteger(\"" + variant + "000\")";
            case "UUID" -> "UUID.fromString(\"00000000-0000-0000-0000-00000000000" + variant + "\")";
            case "LocalDate" -> "LocalDate.of(2024, " + variant + ", " + (variant + 10) + ")";
            case "LocalDateTime" -> "LocalDateTime.of(2024, " + variant + ", " + (variant + 10) + ", " + (variant + 8) + ", " + (variant + 5) + ")";
            case "LocalTime" -> "LocalTime.of(" + (variant + 8) + ", " + (variant + 5) + ", " + (variant + 2) + ")";
            case "Instant" -> "Instant.parse(\"2024-01-0" + variant + "T10:15:30Z\")";
            case "OffsetDateTime" -> "OffsetDateTime.parse(\"2024-01-0" + variant + "T10:15:30+02:00\")";
            case "ZonedDateTime" -> "ZonedDateTime.parse(\"2024-01-0" + variant + "T10:15:30+02:00[Europe/Athens]\")";
            default -> "null";
        };
    }

    /**
     * Extracts only valid project-specific type names from a type declaration.
     *
     * @param fieldType field type declaration
     * @return referenced project-specific type names
     */
    private Set<String> extractReferencedProjectTypes(String fieldType) {
        Set<String> referencedTypes = new TreeSet<>();

        String normalizedType = normalizeType(fieldType);

        if (normalizedType.isBlank()) {
            return referencedTypes;
        }

        String cleanedType = normalizedType
                .replace("<", " ")
                .replace(">", " ")
                .replace(",", " ")
                .replace("[", " ")
                .replace("]", " ")
                .trim();

        for (String token : cleanedType.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }

            if (JavaTypeSupport.isScalarType(token)) {
                continue;
            }

            if (isJdkContainerToken(token)) {
                continue;
            }

            if ("Object".equals(token)) {
                continue;
            }

            if (!Character.isUpperCase(token.charAt(0))) {
                continue;
            }

            referencedTypes.add(token);
        }

        return referencedTypes;
    }


    /**
     * Normalizes a type declaration to use simple class names instead of fully qualified names.
     *
     * @param typeName raw type name
     * @return normalized type name
     */
    private String normalizeType(String typeName) {
        String simplifiedType = DtoFieldTypeResolver.simplifyType(GeneratorSupport.trimToEmpty(typeName));

        if (simplifiedType.isBlank()) {
            return "";
        }

        String normalizedType = simplifiedType
                .replace("<", " < ")
                .replace(">", " > ")
                .replace(",", " , ")
                .trim();

        StringBuilder result = new StringBuilder();

        for (String token : normalizedType.split("\\s+")) {
            if ("<".equals(token) || ">".equals(token) || ",".equals(token)) {
                result.append(token);
                continue;
            }

            result.append(extractSimpleTypeName(token));
        }

        return result.toString()
                .replace(" <", "<")
                .replace("< ", "<")
                .replace(" >", ">")
                .replace(" ,", ",")
                .replace(", ", ",")
                .trim();
    }

    /**
     * Extracts the simple type name from a possibly fully qualified class name.
     *
     * @param typeName raw or fully qualified type name
     * @return simple type name
     */
    private String extractSimpleTypeName(String typeName) {
        String trimmedTypeName = GeneratorSupport.trimToEmpty(typeName);

        if (trimmedTypeName.isBlank()) {
            return "";
        }

        int lastDotIndex = trimmedTypeName.lastIndexOf('.');

        if (lastDotIndex >= 0 && lastDotIndex < trimmedTypeName.length() - 1) {
            return trimmedTypeName.substring(lastDotIndex + 1);
        }

        return trimmedTypeName;
    }

    /**
     * Determines whether the provided type is a list type.
     *
     * @param fieldType field type
     * @return true when list-like
     */
    private boolean isListType(String fieldType) {
        String normalized = DtoFieldTypeResolver.simplifyType(fieldType);
        return normalized.startsWith("List<") || normalized.startsWith("ArrayList<");
    }

    /**
     * Determines whether the provided type is a set type.
     *
     * @param fieldType field type
     * @return true when set-like
     */
    private boolean isSetType(String fieldType) {
        String normalized = DtoFieldTypeResolver.simplifyType(fieldType);
        return normalized.startsWith("Set<") || normalized.startsWith("HashSet<");
    }

    /**
     * Determines whether the provided type is a map type.
     *
     * @param fieldType field type
     * @return true when map-like
     */
    private boolean isMapType(String fieldType) {
        String normalized = DtoFieldTypeResolver.simplifyType(fieldType);
        return normalized.startsWith("Map<") || normalized.startsWith("HashMap<");
    }

    /**
     * Determines whether the provided type should be treated as a project type.
     *
     * @param fieldType field type
     * @return true when project-specific
     */
    private boolean isProjectType(String fieldType) {
        String simpleType = extractPrimarySimpleType(fieldType);

        if (simpleType.isBlank()) {
            return false;
        }

        if (JavaTypeSupport.isScalarType(simpleType)) {
            return false;
        }

        return !isJdkContainerToken(simpleType);
    }

    /**
     * Extracts the primary simple type from a type declaration.
     *
     * @param fieldType field type
     * @return simple type token
     */
    private String extractPrimarySimpleType(String fieldType) {
        String normalized = normalizeType(fieldType)
                .replace("<", " ")
                .replace(">", " ")
                .replace(",", " ")
                .replace("[", " ")
                .replace("]", " ")
                .trim();

        if (normalized.isBlank()) {
            return "";
        }

        String[] tokens = normalized.split("\\s+");

        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }

            if (JavaTypeSupport.isScalarType(token)) {
                continue;
            }

            if (isJdkContainerToken(token)) {
                continue;
            }

            return token;
        }

        return "";
    }

    /**
     * Checks whether the provided token belongs to common JDK container types.
     *
     * @param token type token
     * @return true when it is a JDK container type
     */
    private boolean isJdkContainerToken(String token) {
        return "List".equals(token)
                || "ArrayList".equals(token)
                || "Set".equals(token)
                || "HashSet".equals(token)
                || "Map".equals(token)
                || "HashMap".equals(token);
    }


}