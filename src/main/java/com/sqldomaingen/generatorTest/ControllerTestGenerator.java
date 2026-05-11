package com.sqldomaingen.generatorTest;

import com.sqldomaingen.model.Field;
import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.Table;
import com.sqldomaingen.util.GeneratorImportSupport;
import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.util.JavaTypeSupport;
import com.sqldomaingen.util.NamingConverter;
import com.sqldomaingen.util.PackageResolver;
import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates controller test classes for parsed tables.
 */
@Log4j2
public class ControllerTestGenerator {

    /**
     * Generates controller test classes for all parsed tables.
     *
     * @param tables parsed SQL tables
     * @param outputDir project root output directory
     * @param basePackage base Java package
     * @param overwrite overwrite existing files when true
     */
    public void generateControllerTests(List<Table> tables, String outputDir, String basePackage, boolean overwrite) {
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        Path controllerTestDir = GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(outputDir, basePackage, "controller", true)
        );

        String testPackage = PackageResolver.resolvePackageName(basePackage, "controller");
        String controllerPackage = PackageResolver.resolvePackageName(basePackage, "controller");
        String dtoPackage = PackageResolver.resolvePackageName(basePackage, "dto");
        String servicePackage = PackageResolver.resolvePackageName(basePackage, "service");

        for (Table table : tables) {
            if (table == null || table.getName() == null || table.getName().isBlank()) {
                continue;
            }

            String entityName = NamingConverter.toPascalCase(
                    GeneratorSupport.normalizeTableName(table.getName())
            );
            String dtoName = entityName + "Dto";
            String testName = entityName + "ControllerTest";
            String controllerName = entityName + "Controller";
            String serviceName = entityName + "Service";
            String serviceVar = NamingConverter.decapitalizeFirstLetter(entityName) + "Service";

            Map<String, String> dtoFieldTypes = loadGeneratedDtoFieldTypes(outputDir, basePackage, dtoName);
            List<Field> dtoFields = loadGeneratedDtoFields(outputDir, basePackage, dtoName);
            List<String> requiredDtoFieldNames = loadRequiredDtoFieldNames(outputDir, basePackage, dtoName);

            boolean compositePrimaryKey = hasCompositePrimaryKey(table);
            boolean shouldImportEq = true;
            List<Column> primaryKeyColumns = getPrimaryKeyColumns(table);
            String normalizedEntityName = entityName;

            if (entityName.startsWith("Api") && entityName.length() > 3 && Character.isUpperCase(entityName.charAt(3))) {
                normalizedEntityName = entityName.substring(3);
            }

            String apiPath = "/api/" + NamingConverter.toKebabCase(normalizedEntityName);

            StringBuilder content = new StringBuilder();

            appendPackageAndImports(
                    content,
                    testPackage,
                    controllerPackage,
                    controllerName,
                    dtoPackage,
                    dtoName,
                    servicePackage,
                    serviceName,
                    shouldImportEq,
                    basePackage,
                    table,
                    dtoFieldTypes,
                    dtoFields,
                    requiredDtoFieldNames
            );

            content.append("@AutoConfigureMockMvc(addFilters = false)\n");
            content.append("@WebMvcTest(").append(controllerName).append(".class)\n");
            content.append("class ").append(testName).append(" {\n\n");

            appendFields(content, serviceName, serviceVar);
            appendGetAllTest(content, entityName, dtoName, serviceVar, apiPath);

            if (compositePrimaryKey) {
                appendCompositeGetByIdTests(content, entityName, dtoName, serviceVar, apiPath, primaryKeyColumns);
                appendCompositeCreateTests(
                        content,
                        entityName,
                        dtoName,
                        serviceVar,
                        apiPath,
                        dtoFields,
                        requiredDtoFieldNames
                );

                appendCompositePatchTests(content, entityName, dtoName, serviceVar, apiPath, primaryKeyColumns);
                appendCompositeDeleteTests(content, entityName, serviceVar, apiPath, primaryKeyColumns);
            } else {
                appendSinglePrimaryKeyTests(
                        content,
                        table,
                        entityName,
                        dtoName,
                        serviceVar,
                        apiPath,
                        dtoFields,
                        requiredDtoFieldNames
                );
            }

            appendCreateDtoFixtureMethod(
                    content,
                    dtoName,
                    entityName,
                    dtoFields,
                    requiredDtoFieldNames
            );
            content.append("}\n");

            GeneratorSupport.writeFile(
                    controllerTestDir.resolve(testName + ".java"),
                    content.toString(),
                    overwrite
            );
        }

        log.debug("Controller test classes generated under: {}", controllerTestDir.toAbsolutePath());
    }

    /**
     * Appends a valid creation DTO fixture factory method based on actual DTO fields.
     *
     * @param content generated test content
     * @param dtoName dto simple name
     * @param entityName entity simple name
     * @param dtoFields actual DTO fields
     * @param requiredDtoFieldNames required DTO field names
     */
    private void appendCreateDtoFixtureMethod(
            StringBuilder content,
            String dtoName,
            String entityName,
            List<Field> dtoFields,
            List<String> requiredDtoFieldNames
    ) {
        content.append("    /**\n");
        content.append("     * Creates a valid create request DTO for ").append(entityName).append(".\n");
        content.append("     *\n");
        content.append("     * @return populated create request dto\n");
        content.append("     */\n");
        content.append("    private ").append(dtoName).append(" createValidCreate")
                .append(entityName).append("Dto() {\n");

        StringBuilder setterLines = new StringBuilder();
        boolean hasAssignments = appendRequiredCreateDtoSetterLines(
                setterLines,
                dtoFields,
                requiredDtoFieldNames
        );

        if (!hasAssignments) {
            content.append("        return new ").append(dtoName).append("();\n");
            content.append("    }\n\n");
            return;
        }

        content.append("        ").append(dtoName).append(" dto = new ").append(dtoName).append("();\n");
        content.append(setterLines);
        content.append("        return dto;\n");
        content.append("    }\n\n");
    }

    /**
     * Appends package and import statements.
     *
     * @param content generated file content
     * @param testPackage test package name
     * @param controllerPackage controller package name
     * @param controllerName controller simple name
     * @param dtoPackage dto package name
     * @param dtoName dto simple name
     * @param servicePackage service package name
     * @param serviceName service simple name
     * @param shouldImportEq whether eq matcher import should be added
     * @param basePackage base package name
     * @param table current table
     * @param dtoFieldTypes actual DTO field types
     */
    private void appendPackageAndImports(
            StringBuilder content,
            String testPackage,
            String controllerPackage,
            String controllerName,
            String dtoPackage,
            String dtoName,
            String servicePackage,
            String serviceName,
            boolean shouldImportEq,
            String basePackage,
            Table table,
            Map<String, String> dtoFieldTypes,
            List<Field> dtoFields,
            List<String> requiredDtoFieldNames
    ) {
        String exceptionPackage = PackageResolver.resolvePackageName(basePackage, "exception");

        Set<String> imports = new java.util.TreeSet<>();
        Set<String> staticImports = new java.util.TreeSet<>();

        if (!testPackage.equals(controllerPackage)) {
            imports.add("import " + controllerPackage + "." + controllerName + ";");
        }

        imports.add("import " + dtoPackage + "." + dtoName + ";");
        imports.add("import " + servicePackage + "." + serviceName + ";");
        imports.add("import " + exceptionPackage + ".ErrorCodes;");
        imports.add("import " + exceptionPackage + ".GeneratedRuntimeException;");

        for (String nestedDtoType : resolveNestedDtoImports(dtoFields, requiredDtoFieldNames, dtoName)) {
            imports.add("import " + dtoPackage + "." + nestedDtoType + ";");
        }

        imports.add("import com.fasterxml.jackson.databind.ObjectMapper;");
        imports.add("import org.junit.jupiter.api.Test;");
        imports.add("import org.springframework.beans.factory.annotation.Autowired;");
        imports.add("import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;");
        imports.add("import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;");
        imports.add("import org.springframework.http.MediaType;");
        imports.add("import org.springframework.test.context.bean.override.mockito.MockitoBean;");
        imports.add("import org.springframework.test.web.servlet.MockMvc;");
        imports.add("import java.util.List;");

        imports.addAll(resolveMandatoryJavaTypeImports(table, dtoFields, requiredDtoFieldNames));

        imports.addAll(GeneratorImportSupport.resolveImports(
                table,
                (column, type) -> usesTypeInGeneratedTest(column, dtoFieldTypes, type)
        ));

        staticImports.add("import static org.mockito.ArgumentMatchers.any;");

        if (shouldImportEq) {
            staticImports.add("import static org.mockito.ArgumentMatchers.eq;");
        }

        staticImports.add("import static org.mockito.BDDMockito.given;");
        staticImports.add("import static org.mockito.BDDMockito.willDoNothing;");
        staticImports.add("import static org.mockito.BDDMockito.willThrow;");
        staticImports.add("import static org.mockito.Mockito.verify;");
        staticImports.add("import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;");
        staticImports.add("import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;");
        staticImports.add("import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;");
        staticImports.add("import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;");
        staticImports.add("import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;");
        staticImports.add("import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;");

        content.append("package ").append(testPackage).append(";\n\n");

        for (String importLine : imports) {
            content.append(importLine).append("\n");
        }

        content.append("\n");

        for (String staticImportLine : staticImports) {
            content.append(staticImportLine).append("\n");
        }

        content.append("\n");
    }


    /**
     * Resolves nested DTO imports required by generated controller tests.
     *
     * @param dtoFields actual DTO fields
     * @param requiredDtoFieldNames required DTO field names
     * @param rootDtoName current root DTO simple name
     * @return nested DTO simple names that must be imported
     */
    private Set<String> resolveNestedDtoImports(
            List<Field> dtoFields,
            List<String> requiredDtoFieldNames,
            String rootDtoName
    ) {
        Set<String> imports = new java.util.TreeSet<>();

        if (dtoFields == null || dtoFields.isEmpty()
                || requiredDtoFieldNames == null || requiredDtoFieldNames.isEmpty()) {
            return imports;
        }

        Set<String> requiredFieldNameSet = new java.util.LinkedHashSet<>(requiredDtoFieldNames);

        for (Field field : dtoFields) {
            if (field == null || field.getName() == null || field.getType() == null) {
                continue;
            }

            if (!requiredFieldNameSet.contains(field.getName())) {
                continue;
            }

            String simpleType = resolveSimpleDtoType(field.getType());

            if (simpleType == null || simpleType.isBlank()) {
                continue;
            }

            if (!simpleType.endsWith("Dto")) {
                continue;
            }

            if (simpleType.equals(rootDtoName)) {
                continue;
            }

            imports.add(simpleType);
        }

        return imports;
    }

    /**
     * Appends test class fields.
     *
     * @param content generated file content
     * @param serviceName service simple name
     * @param serviceVar service variable name
     */
    private void appendFields(StringBuilder content, String serviceName, String serviceVar) {
        content.append("    @Autowired\n");
        content.append("    private MockMvc mockMvc;\n\n");

        content.append("    @Autowired\n");
        content.append("    private ObjectMapper objectMapper;\n\n");

        content.append("    @MockitoBean\n");
        content.append("    private ").append(serviceName).append(" ").append(serviceVar).append(";\n\n");
    }

    /**
     * Appends the get-all controller test.
     *
     * @param content generated test content
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param serviceVar service variable name
     * @param apiPath api base path
     */
    private void appendGetAllTest(StringBuilder content,
                                  String entityName,
                                  String dtoName,
                                  String serviceVar,
                                  String apiPath) {
        String pluralMethodSuffix = NamingConverter.toPascalCase(
                NamingConverter.toCamelCasePlural(entityName)
        );

        content.append("    @Test\n");
        content.append("    void shouldReturnOkForGetAll() throws Exception {\n");
        content.append("        given(").append(serviceVar).append(".getAll").append(pluralMethodSuffix)
                .append("()).willReturn(List.of(new ").append(dtoName).append("()));\n\n");

        content.append("        mockMvc.perform(get(\"").append(apiPath).append("\"))\n");
        content.append("                .andExpect(status().isOk())\n");
        content.append("                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));\n\n");

        content.append("        verify(").append(serviceVar).append(").getAll").append(pluralMethodSuffix).append("();\n");
        content.append("    }\n\n");
    }

    /**
     * Appends all single-primary-key controller tests.
     *
     * @param content generated test content
     * @param table current table
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param serviceVar service variable name
     * @param apiPath api base path
     * @param dtoFields actual DTO fields
     * @param requiredDtoFieldNames required DTO field names
     */
    private void appendSinglePrimaryKeyTests(
            StringBuilder content,
            Table table,
            String entityName,
            String dtoName,
            String serviceVar,
            String apiPath,
            List<Field> dtoFields,
            List<String> requiredDtoFieldNames
    ) {
        String primaryKeyType = detectSinglePrimaryKeyType(table);
        String sampleId = sampleValueForType(primaryKeyType, 1);

        appendSingleGetByIdTests(content, entityName, dtoName, serviceVar, apiPath, primaryKeyType, sampleId);
        appendSingleCreateTests(content, entityName, dtoName, serviceVar, apiPath, dtoFields, requiredDtoFieldNames);

        appendSinglePatchTests(content, entityName, dtoName, serviceVar, apiPath, primaryKeyType, sampleId);
        appendSingleDeleteTests(content, entityName, serviceVar, apiPath, primaryKeyType, sampleId);
    }


    /**
     * Appends validation failure test for CREATE endpoint.
     *
     * @param content StringBuilder for test content
     * @param entityName entity name
     * @param dtoName dto name
     * @param apiPath api path
     * @param dtoFields dto fields
     * @param requiredDtoFieldNames required fields
     */
    private void appendCreateValidationFailureTest(
            StringBuilder content,
            String entityName,
            String dtoName,
            String apiPath,
            List<Field> dtoFields,
            List<String> requiredDtoFieldNames
    ) {
        String invalidFieldName = findFirstNullInvalidatableCreateField(dtoFields, requiredDtoFieldNames);
        if (invalidFieldName == null) {
            return;
        }

        String setterName = "set" + NamingConverter.toPascalCase(invalidFieldName);

        content.append("    @Test\n");
        content.append("    void shouldReturnUnprocessableEntityForCreateValidationFailure() throws Exception {\n");
        content.append("        ").append(dtoName).append(" requestDto = createValidCreate")
                .append(entityName).append("Dto();\n");
        content.append("        requestDto.").append(setterName).append("(null);\n\n");

        content.append("        mockMvc.perform(post(\"").append(apiPath).append("\")\n");
        content.append("                .contentType(MediaType.APPLICATION_JSON)\n");
        content.append("                .content(objectMapper.writeValueAsString(requestDto)))\n");
        content.append("                .andExpect(status().isUnprocessableEntity());\n");
        content.append("    }\n\n");
    }

    /**
     * Returns the first required DTO field that can safely be invalidated with null.
     *
     * @param dtoFields actual DTO fields
     * @param requiredDtoFieldNames required DTO field names
     * @return first null-invalidatable required DTO field name, or null when none exists
     */
    private String findFirstNullInvalidatableCreateField(
            List<Field> dtoFields,
            List<String> requiredDtoFieldNames
    ) {
        if (dtoFields == null || dtoFields.isEmpty()) {
            return null;
        }

        if (requiredDtoFieldNames == null || requiredDtoFieldNames.isEmpty()) {
            return null;
        }

        Set<String> requiredFieldNameSet = new java.util.LinkedHashSet<>(requiredDtoFieldNames);

        for (Field field : dtoFields) {
            if (field == null) {
                continue;
            }

            String dtoFieldName = field.getName();
            String dtoFieldType = field.getType();

            if (dtoFieldName == null || dtoFieldName.isBlank()) {
                continue;
            }

            if (!requiredFieldNameSet.contains(dtoFieldName)) {
                continue;
            }

            if ("id".equals(dtoFieldName)
                    || "dateCreated".equals(dtoFieldName)
                    || "lastUpdated".equals(dtoFieldName)) {
                continue;
            }

            if (dtoFieldType == null || dtoFieldType.isBlank()) {
                continue;
            }

            if (!isSupportedScalarDtoType(dtoFieldType) && !isSupportedNestedDtoType(dtoFieldType)) {
                continue;
            }

            if (!canInvalidateDtoFieldWithNull(dtoFieldType)) {
                continue;
            }

            return dtoFieldName;
        }

        return null;
    }


    /**
     * Loads DTO field names that are explicitly required by validation annotations.
     *
     * @param outputDir project root output directory
     * @param basePackage base Java package
     * @param dtoName dto simple name
     * @return required DTO field names in declaration order
     */
    private List<String> loadRequiredDtoFieldNames(
            String outputDir,
            String basePackage,
            String dtoName
    ) {
        Path dtoPath = PackageResolver.resolvePath(outputDir, basePackage, "dto")
                .resolve(dtoName + ".java");

        if (!Files.exists(dtoPath)) {
            return List.of();
        }

        List<String> requiredFieldNames = new java.util.ArrayList<>();
        Pattern fieldPattern = Pattern.compile("^\\s*private\\s+(.+?)\\s+(\\w+)\\s*;\\s*$");

        boolean requiredNextField = false;

        try {
            for (String line : Files.readAllLines(dtoPath)) {
                String trimmedLine = line.trim();

                if (trimmedLine.startsWith("@NotNull")
                        || trimmedLine.startsWith("@NotBlank")
                        || trimmedLine.startsWith("@NotEmpty")) {
                    requiredNextField = true;
                    continue;
                }

                Matcher matcher = fieldPattern.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }

                String fieldName = matcher.group(2).trim();

                if (requiredNextField) {
                    requiredFieldNames.add(fieldName);
                }

                requiredNextField = false;
            }
        } catch (IOException exception) {
            log.warn("Could not read DTO file for required field discovery: {}", dtoPath, exception);
        }

        return requiredFieldNames;
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

        if (!Files.exists(dtoPath)) {
            return List.of();
        }

        List<Field> dtoFields = new java.util.ArrayList<>();
        Pattern fieldPattern = Pattern.compile("^\\s*private\\s+(.+?)\\s+(\\w+)\\s*;\\s*$");

        try {
            for (String line : Files.readAllLines(dtoPath)) {
                Matcher matcher = fieldPattern.matcher(line);
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
        } catch (IOException exception) {
            log.warn("Could not read DTO file for field discovery: {}", dtoPath, exception);
        }

        return dtoFields;
    }

    /**
     * Determines whether a DTO field type can safely be invalidated with null.
     *
     * @param dtoFieldType actual DTO field type
     * @return true when null can be assigned safely
     */
    private boolean canInvalidateDtoFieldWithNull(String dtoFieldType) {
        String simpleType = resolveSimpleDtoType(dtoFieldType);

        return !"int".equals(simpleType)
                && !"long".equals(simpleType)
                && !"short".equals(simpleType)
                && !"byte".equals(simpleType)
                && !"boolean".equals(simpleType)
                && !"double".equals(simpleType)
                && !"float".equals(simpleType)
                && !"char".equals(simpleType);
    }


    /**
     * Appends single-primary-key get-by-id tests.
     *
     * @param content generated test content
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param serviceVar service variable name
     * @param apiPath api base path
     * @param primaryKeyType primary key type
     * @param sampleId sample id value
     */
    private void appendSingleGetByIdTests(StringBuilder content,
                                          String entityName,
                                          String dtoName,
                                          String serviceVar,
                                          String apiPath,
                                          String primaryKeyType,
                                          String sampleId) {
        content.append("    @Test\n");
        content.append("    void shouldReturnOkForGetById() throws Exception {\n");
        content.append("        ").append(primaryKeyType).append(" id = ").append(sampleId).append(";\n");
        content.append("        given(").append(serviceVar).append(".get").append(entityName)
                .append("ById(id)).willReturn(new ").append(dtoName).append("());\n\n");

        content.append("        mockMvc.perform(get(\"").append(apiPath).append("/{id}\", id))\n");
        content.append("                .andExpect(status().isOk())\n");
        content.append("                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));\n\n");

        content.append("        verify(").append(serviceVar).append(").get").append(entityName).append("ById(id);\n");
        content.append("    }\n\n");

        content.append("    @Test\n");
        content.append("    void shouldReturnNotFoundForGetById() throws Exception {\n");
        content.append("        ").append(primaryKeyType).append(" id = ").append(sampleId).append(";\n");
        content.append("        given(").append(serviceVar).append(".get").append(entityName).append("ById(id))\n");
        content.append("                .willThrow(GeneratedRuntimeException.builder()\n");
        content.append("                        .code(ErrorCodes.NOT_FOUND)\n");
        content.append("                        .message(\"").append(entityName).append(" not found with id: \" + id)\n");
        content.append("                        .build());\n\n");

        content.append("        mockMvc.perform(get(\"").append(apiPath).append("/{id}\", id))\n");
        content.append("                .andExpect(status().isNotFound());\n");
        content.append("    }\n\n");
    }

    /**
     * Appends single-primary-key create tests.
     *
     * @param content generated test content
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param serviceVar service variable name
     * @param apiPath api base path
     * @param dtoFields actual DTO fields
     * @param requiredDtoFieldNames required DTO field names
     */
    private void appendSingleCreateTests(
            StringBuilder content,
            String entityName,
            String dtoName,
            String serviceVar,
            String apiPath,
            List<Field> dtoFields,
            List<String> requiredDtoFieldNames
    ) {
        content.append("    @Test\n");
        content.append("    void shouldReturnCreatedForCreate() throws Exception {\n");
        content.append("        ").append(dtoName).append(" requestDto = createValidCreate")
                .append(entityName).append("Dto();\n");
        content.append("        ").append(dtoName).append(" responseDto = new ").append(dtoName).append("();\n");
        content.append("        given(").append(serviceVar).append(".create")
                .append(entityName)
                .append("(any(").append(dtoName).append(".class))).willReturn(responseDto);\n\n");

        content.append("        mockMvc.perform(post(\"").append(apiPath).append("\")\n");
        content.append("                .contentType(MediaType.APPLICATION_JSON)\n");
        content.append("                .content(objectMapper.writeValueAsString(requestDto)))\n");
        content.append("                .andExpect(status().isCreated())\n");
        content.append("                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));\n\n");

        content.append("        verify(").append(serviceVar).append(").create")
                .append(entityName)
                .append("(any(").append(dtoName).append(".class));\n");
        content.append("    }\n\n");

        appendCreateValidationFailureTest(
                content,
                entityName,
                dtoName,
                apiPath,
                dtoFields,
                requiredDtoFieldNames
        );

        content.append("    @Test\n");
        content.append("    void shouldReturnBadRequestForCreateWhenServiceThrows() throws Exception {\n");
        content.append("        ").append(dtoName).append(" requestDto = createValidCreate")
                .append(entityName).append("Dto();\n");
        content.append("        given(").append(serviceVar).append(".create")
                .append(entityName)
                .append("(any(").append(dtoName).append(".class)))\n");
        content.append("                .willThrow(GeneratedRuntimeException.builder()\n");
        content.append("                        .code(ErrorCodes.BAD_REQUEST)\n");
        content.append("                        .message(\"Invalid payload\")\n");
        content.append("                        .build());\n\n");

        content.append("        mockMvc.perform(post(\"").append(apiPath).append("\")\n");
        content.append("                .contentType(MediaType.APPLICATION_JSON)\n");
        content.append("                .content(objectMapper.writeValueAsString(requestDto)))\n");
        content.append("                .andExpect(status().isBadRequest());\n");
        content.append("    }\n\n");
    }

    /**
     * Appends single-primary-key patch tests.
     *
     * @param content generated test content
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param serviceVar service variable name
     * @param apiPath api base path
     * @param primaryKeyType primary key type
     * @param sampleId sample id value
     */
    private void appendSinglePatchTests(
            StringBuilder content,
            String entityName,
            String dtoName,
            String serviceVar,
            String apiPath,
            String primaryKeyType,
            String sampleId
    ) {
        content.append("    @Test\n");
        content.append("    void shouldReturnOkForPatch() throws Exception {\n");
        content.append("        ").append(primaryKeyType).append(" id = ").append(sampleId).append(";\n");
        content.append("        ").append(dtoName).append(" requestDto = createValidCreate")
                .append(entityName).append("Dto();\n");
        content.append("        ").append(dtoName).append(" responseDto = new ").append(dtoName).append("();\n");
        content.append("        given(").append(serviceVar).append(".update").append(entityName)
                .append("(eq(id), any(").append(dtoName).append(".class))).willReturn(responseDto);\n\n");

        content.append("        mockMvc.perform(patch(\"").append(apiPath).append("/{id}\", id)\n");
        content.append("                .contentType(MediaType.APPLICATION_JSON)\n");
        content.append("                .content(objectMapper.writeValueAsString(requestDto)))\n");
        content.append("                .andExpect(status().isOk())\n");
        content.append("                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));\n\n");

        content.append("        verify(").append(serviceVar).append(").update").append(entityName)
                .append("(eq(id), any(").append(dtoName).append(".class));\n");
        content.append("    }\n\n");

        content.append("    @Test\n");
        content.append("    void shouldReturnNotFoundForPatch() throws Exception {\n");
        content.append("        ").append(primaryKeyType).append(" id = ").append(sampleId).append(";\n");
        content.append("        ").append(dtoName).append(" requestDto = createValidCreate")
                .append(entityName).append("Dto();\n");
        content.append("        given(").append(serviceVar).append(".update").append(entityName)
                .append("(eq(id), any(").append(dtoName).append(".class)))\n");
        content.append("                .willThrow(GeneratedRuntimeException.builder()\n");
        content.append("                        .code(ErrorCodes.NOT_FOUND)\n");
        content.append("                        .message(\"").append(entityName).append(" not found with id: \" + id)\n");
        content.append("                        .build());\n\n");

        content.append("        mockMvc.perform(patch(\"").append(apiPath).append("/{id}\", id)\n");
        content.append("                .contentType(MediaType.APPLICATION_JSON)\n");
        content.append("                .content(objectMapper.writeValueAsString(requestDto)))\n");
        content.append("                .andExpect(status().isNotFound());\n");
        content.append("    }\n\n");

        appendSinglePatchInternalServerErrorTest(content, entityName, dtoName, serviceVar, apiPath, primaryKeyType, sampleId);
    }

    /**
     * Appends composite-primary-key patch test when service throws unexpected exception.
     *
     * @param content generated test content
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param serviceVar service variable name
     * @param apiPath api base path
     * @param primaryKeyColumns primary key columns
     */
    private void appendCompositePatchInternalServerErrorTest(
            StringBuilder content,
            String entityName,
            String dtoName,
            String serviceVar,
            String apiPath,
            List<Column> primaryKeyColumns
    ) {
        String serviceEqArguments = buildCompositeEqServiceArguments(primaryKeyColumns);
        String compositePathTemplate = buildCompositePathTemplate(primaryKeyColumns);

        content.append("    @Test\n");
        content.append("    void shouldReturnInternalServerErrorForPatchWhenServiceThrowsUnexpectedException() throws Exception {\n");
        appendCompositePrimaryKeyDeclarations(content, primaryKeyColumns);
        content.append("        ").append(dtoName).append(" requestDto = createValidCreate")
                .append(entityName).append("Dto();\n");
        content.append("        given(").append(serviceVar).append(".update").append(entityName)
                .append("(").append(serviceEqArguments).append(", any(").append(dtoName).append(".class)))\n");
        content.append("                .willThrow(new RuntimeException(\"Unexpected error\"));\n\n");

        content.append("        mockMvc.perform(patch(\"").append(apiPath).append(compositePathTemplate).append("\"");
        appendCompositePathArguments(content, primaryKeyColumns);
        content.append(")\n");
        content.append("                .contentType(MediaType.APPLICATION_JSON)\n");
        content.append("                .content(objectMapper.writeValueAsString(requestDto)))\n");
        content.append("                .andExpect(status().isInternalServerError());\n");
        content.append("    }\n\n");
    }

    /**
     * Appends single-primary-key delete tests.
     *
     * @param content generated test content
     * @param entityName entity simple name
     * @param serviceVar service variable name
     * @param apiPath api base path
     * @param primaryKeyType primary key type
     * @param sampleId sample id value
     */
    private void appendSingleDeleteTests(StringBuilder content,
                                         String entityName,
                                         String serviceVar,
                                         String apiPath,
                                         String primaryKeyType,
                                         String sampleId) {
        content.append("    @Test\n");
        content.append("    void shouldReturnNoContentForDelete() throws Exception {\n");
        content.append("        ").append(primaryKeyType).append(" id = ").append(sampleId).append(";\n");
        content.append("        willDoNothing().given(").append(serviceVar).append(").delete")
                .append(entityName).append("(id);\n\n");

        content.append("        mockMvc.perform(delete(\"").append(apiPath).append("/{id}\", id))\n");
        content.append("                .andExpect(status().isNoContent());\n\n");

        content.append("        verify(").append(serviceVar).append(").delete").append(entityName).append("(id);\n");
        content.append("    }\n\n");

        content.append("    @Test\n");
        content.append("    void shouldReturnNotFoundForDelete() throws Exception {\n");
        content.append("        ").append(primaryKeyType).append(" id = ").append(sampleId).append(";\n");
        content.append("        willThrow(GeneratedRuntimeException.builder()\n");
        content.append("                .code(ErrorCodes.NOT_FOUND)\n");
        content.append("                .message(\"").append(entityName).append(" not found with id: \" + id)\n");
        content.append("                .build())\n");
        content.append("                .given(").append(serviceVar).append(").delete").append(entityName).append("(id);\n\n");

        content.append("        mockMvc.perform(delete(\"").append(apiPath).append("/{id}\", id))\n");
        content.append("                .andExpect(status().isNotFound());\n");
        content.append("    }\n\n");
    }

    /**
     * Appends composite-primary-key get-by-id tests.
     *
     * @param content generated test content
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param serviceVar service variable name
     * @param apiPath api base path
     * @param primaryKeyColumns primary key columns
     */
    private void appendCompositeGetByIdTests(StringBuilder content,
                                             String entityName,
                                             String dtoName,
                                             String serviceVar,
                                             String apiPath,
                                             List<Column> primaryKeyColumns) {
        String serviceArguments = buildCompositeServiceArguments(primaryKeyColumns);
        String compositePathTemplate = buildCompositePathTemplate(primaryKeyColumns);

        content.append("    @Test\n");
        content.append("    void shouldReturnOkForGetById() throws Exception {\n");
        appendCompositePrimaryKeyDeclarations(content, primaryKeyColumns);
        content.append("        given(").append(serviceVar).append(".get").append(entityName)
                .append("ById(").append(serviceArguments).append("))")
                .append(".willReturn(new ").append(dtoName).append("());\n\n");

        content.append("        mockMvc.perform(get(\"").append(apiPath).append(compositePathTemplate).append("\"");
        appendCompositePathArguments(content, primaryKeyColumns);
        content.append("))\n");
        content.append("                .andExpect(status().isOk())\n");
        content.append("                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));\n\n");

        content.append("        verify(").append(serviceVar).append(").get").append(entityName)
                .append("ById(").append(serviceArguments).append(");\n");
        content.append("    }\n\n");

        content.append("    @Test\n");
        content.append("    void shouldReturnNotFoundForGetById() throws Exception {\n");
        appendCompositePrimaryKeyDeclarations(content, primaryKeyColumns);
        content.append("        given(").append(serviceVar).append(".get").append(entityName)
                .append("ById(").append(serviceArguments).append("))\n");
        content.append("                .willThrow(GeneratedRuntimeException.builder()\n");
        content.append("                        .code(ErrorCodes.NOT_FOUND)\n");
        content.append("                        .message(\"").append(entityName).append(" not found\")\n");
        content.append("                        .build());\n\n");

        content.append("        mockMvc.perform(get(\"").append(apiPath).append(compositePathTemplate).append("\"");
        appendCompositePathArguments(content, primaryKeyColumns);
        content.append("))\n");
        content.append("                .andExpect(status().isNotFound());\n");
        content.append("    }\n\n");
    }

    /**
     * Appends composite-primary-key create tests.
     *
     * @param content generated test content
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param serviceVar service variable name
     * @param apiPath api base path
     * @param dtoFields actual DTO fields
     * @param requiredDtoFieldNames required DTO field names
     */
    private void appendCompositeCreateTests(
            StringBuilder content,
            String entityName,
            String dtoName,
            String serviceVar,
            String apiPath,
            List<Field> dtoFields,
            List<String> requiredDtoFieldNames
    ) {
        content.append("    @Test\n");
        content.append("    void shouldReturnCreatedForCreate() throws Exception {\n");
        content.append("        ").append(dtoName).append(" requestDto = createValidCreate")
                .append(entityName).append("Dto();\n");
        content.append("        ").append(dtoName).append(" responseDto = new ").append(dtoName).append("();\n");
        content.append("        given(").append(serviceVar).append(".create").append(entityName)
                .append("(any(").append(dtoName).append(".class))).willReturn(responseDto);\n\n");

        content.append("        mockMvc.perform(post(\"").append(apiPath).append("\")\n");
        content.append("                .contentType(MediaType.APPLICATION_JSON)\n");
        content.append("                .content(objectMapper.writeValueAsString(requestDto)))\n");
        content.append("                .andExpect(status().isCreated())\n");
        content.append("                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));\n\n");

        content.append("        verify(").append(serviceVar).append(").create").append(entityName)
                .append("(any(").append(dtoName).append(".class));\n");
        content.append("    }\n\n");

        appendCreateValidationFailureTest(
                content,
                entityName,
                dtoName,
                apiPath,
                dtoFields,
                requiredDtoFieldNames
        );

        content.append("    @Test\n");
        content.append("    void shouldReturnBadRequestForCreateWhenServiceThrows() throws Exception {\n");
        content.append("        ").append(dtoName).append(" requestDto = createValidCreate")
                .append(entityName).append("Dto();\n");
        content.append("        given(").append(serviceVar).append(".create").append(entityName)
                .append("(any(").append(dtoName).append(".class)))\n");
        content.append("                .willThrow(GeneratedRuntimeException.builder()\n");
        content.append("                        .code(ErrorCodes.BAD_REQUEST)\n");
        content.append("                        .message(\"Invalid payload\")\n");
        content.append("                        .build());\n\n");

        content.append("        mockMvc.perform(post(\"").append(apiPath).append("\")\n");
        content.append("                .contentType(MediaType.APPLICATION_JSON)\n");
        content.append("                .content(objectMapper.writeValueAsString(requestDto)))\n");
        content.append("                .andExpect(status().isBadRequest());\n");
        content.append("    }\n\n");
    }

    /**
     * Appends setter lines for the generated create DTO fixture
     * based on required DTO fields.
     *
     * @param content generated test content
     * @param dtoFields actual DTO fields
     * @param requiredDtoFieldNames required DTO field names
     * @return true when at least one setter line was appended
     */
    private boolean appendRequiredCreateDtoSetterLines(
            StringBuilder content,
            List<Field> dtoFields,
            List<String> requiredDtoFieldNames
    ) {
        if (content == null || dtoFields == null || dtoFields.isEmpty()) {
            return false;
        }

        if (requiredDtoFieldNames == null || requiredDtoFieldNames.isEmpty()) {
            return false;
        }

        Set<String> requiredFieldNameSet = new java.util.LinkedHashSet<>(requiredDtoFieldNames);
        boolean appended = false;

        for (Field field : dtoFields) {
            if (field == null) {
                continue;
            }

            String dtoFieldName = field.getName();
            String dtoFieldType = field.getType();

            if (dtoFieldName == null || dtoFieldName.isBlank()) {
                continue;
            }

            if (!requiredFieldNameSet.contains(dtoFieldName)) {
                continue;
            }

            if (dtoFieldType == null || dtoFieldType.isBlank()) {
                continue;
            }

            if ("id".equals(dtoFieldName)
                    || "dateCreated".equals(dtoFieldName)
                    || "lastUpdated".equals(dtoFieldName)) {
                continue;
            }

            if (isSupportedScalarDtoType(dtoFieldType)) {
                String setterName = "set" + NamingConverter.toPascalCase(dtoFieldName);
                String sampleValue = sampleValueForType(resolveSimpleDtoType(dtoFieldType), 1);

                if (!"null".equals(sampleValue)) {
                    content.append("        dto.")
                            .append(setterName)
                            .append("(")
                            .append(sampleValue)
                            .append(");\n");
                    appended = true;
                }

                continue;
            }

            if (isSupportedNestedDtoType(dtoFieldType)) {
                appendNestedDtoCreateFixtureLine(content, dtoFieldName, dtoFieldType);
                appended = true;
            }
        }

        if (appended) {
            content.append("\n");
        }

        return appended;
    }



    /**
     * Appends a nested DTO setter line for a required relation field.
     *
     * @param content generated test content
     * @param dtoFieldName actual DTO field name
     * @param dtoFieldType actual DTO field type
     */
    private void appendNestedDtoCreateFixtureLine(StringBuilder content,
                                                  String dtoFieldName,
                                                  String dtoFieldType) {
        String simpleType = resolveSimpleDtoType(dtoFieldType);

        if (simpleType == null || simpleType.isBlank()) {
            return;
        }

        String setterName = "set" + NamingConverter.toPascalCase(dtoFieldName);

        content.append("        dto.")
                .append(setterName)
                .append("(new ")
                .append(simpleType)
                .append("());\n");
    }

    /**
     * Determines whether the DTO field type is a supported nested DTO type for fixture assignment.
     *
     * @param dtoFieldType actual DTO field type
     * @return true when the field can be assigned with an empty nested DTO instance
     */
    private boolean isSupportedNestedDtoType(String dtoFieldType) {
        String simpleType = resolveSimpleDtoType(dtoFieldType);

        if (simpleType == null || simpleType.isBlank()) {
            return false;
        }

        return simpleType.endsWith("Dto")
                && !simpleType.startsWith("List<")
                && !simpleType.startsWith("Set<")
                && !simpleType.startsWith("Map<");
    }

    /**
     * Loads the actual field names and types declared in the already generated DTO source file.
     *
     * @param outputDir project root output directory
     * @param basePackage base Java package
     * @param dtoName DTO simple name
     * @return declared DTO field names and types
     */
    private Map<String, String> loadGeneratedDtoFieldTypes(String outputDir, String basePackage, String dtoName) {
        Map<String, String> fieldTypes = new LinkedHashMap<>();

        Path dtoDir = PackageResolver.resolvePath(outputDir, basePackage, "dto");
        Path dtoPath = dtoDir.resolve(dtoName + ".java");

        if (!Files.exists(dtoPath)) {
            return fieldTypes;
        }

        Pattern fieldPattern = Pattern.compile("^\\s*private\\s+(.+?)\\s+(\\w+)\\s*;\\s*$");

        try {
            for (String line : Files.readAllLines(dtoPath)) {
                Matcher matcher = fieldPattern.matcher(line);
                if (matcher.matches()) {
                    fieldTypes.put(matcher.group(2), matcher.group(1).trim());
                }
            }
        } catch (IOException exception) {
            log.warn("Could not read DTO file for field discovery: {}", dtoPath, exception);
        }

        return fieldTypes;
    }

    /**
     * Resolves the flat DTO field name that corresponds to the given database column.
     *
     * <p>
     * This method converts the column name to camelCase and attempts to match it
     * against the existing DTO field names. It assumes that DTOs are generated
     * using a flat structure (no nested relations).
     * </p>
     *
     * @param column current database column
     * @param dtoFieldTypes map of DTO field names to their types
     * @return matching DTO field name when found, otherwise null
     */
    private String resolveFlatDtoFieldName(Column column, Map<String, String> dtoFieldTypes) {
        if (column == null || dtoFieldTypes == null || dtoFieldTypes.isEmpty()) {
            return null;
        }

        String columnName = GeneratorSupport.unquoteIdentifier(column.getName());
        String dtoFieldName = NamingConverter.toCamelCase(columnName);

        if (!dtoFieldTypes.containsKey(dtoFieldName)) {
            return null;
        }

        return dtoFieldName;
    }


    /**
     * Resolves the simple DTO type name.
     *
     * @param rawType raw DTO type
     * @return simple type name
     */
    private String resolveSimpleDtoType(String rawType) {
        return JavaTypeSupport.resolveSimpleType(rawType);
    }

    /**
     * Determines whether the DTO field type is a supported scalar type for fixture assignment.
     *
     * @param dtoFieldType actual DTO field type
     * @return true when the field can be assigned with a scalar literal
     */
    private boolean isSupportedScalarDtoType(String dtoFieldType) {
        String simpleType = resolveSimpleDtoType(dtoFieldType);

        return "String".equals(simpleType)
                || JavaTypeSupport.isScalarType(simpleType)
                || isPrimitiveType(simpleType);
    }

    /**
     * Determines whether the provided type is a Java primitive type.
     *
     * @param type simple type name
     * @return true when primitive
     */
    private boolean isPrimitiveType(String type) {
        return "long".equals(type)
                || "int".equals(type)
                || "boolean".equals(type)
                || "double".equals(type)
                || "float".equals(type)
                || "short".equals(type)
                || "byte".equals(type)
                || "char".equals(type);
    }


    /**
     * Determines whether a column should be skipped in generated create DTO fixtures
     * and validation-failure field selection.
     *
     * @param column current column
     * @return true when the column must be skipped
     */
    private boolean shouldSkipCreateField(Column column) {
        if (column == null) {
            return true;
        }

        if (column.isPrimaryKey()) {
            return true;
        }

        if (column.isNullable()) {
            return true;
        }

        String columnName = GeneratorSupport.unquoteIdentifier(column.getName());
        return "date_created".equalsIgnoreCase(columnName)
                || "last_updated".equalsIgnoreCase(columnName);
    }


    /**
     * Appends composite-primary-key patch tests.
     *
     * @param content generated test content
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param serviceVar service variable name
     * @param apiPath api base path
     * @param primaryKeyColumns primary key columns
     */
    private void appendCompositePatchTests(
            StringBuilder content,
            String entityName,
            String dtoName,
            String serviceVar,
            String apiPath,
            List<Column> primaryKeyColumns
    ) {
        String serviceEqArguments = buildCompositeEqServiceArguments(primaryKeyColumns);
        String compositePathTemplate = buildCompositePathTemplate(primaryKeyColumns);

        content.append("    @Test\n");
        content.append("    void shouldReturnOkForPatch() throws Exception {\n");
        appendCompositePrimaryKeyDeclarations(content, primaryKeyColumns);
        content.append("        ").append(dtoName).append(" requestDto = createValidCreate")
                .append(entityName).append("Dto();\n");
        content.append("        ").append(dtoName).append(" responseDto = new ").append(dtoName).append("();\n");
        content.append("        given(").append(serviceVar).append(".update").append(entityName)
                .append("(").append(serviceEqArguments).append(", any(").append(dtoName).append(".class)))")
                .append(".willReturn(responseDto);\n\n");

        content.append("        mockMvc.perform(patch(\"").append(apiPath).append(compositePathTemplate).append("\"");
        appendCompositePathArguments(content, primaryKeyColumns);
        content.append(")\n");
        content.append("                .contentType(MediaType.APPLICATION_JSON)\n");
        content.append("                .content(objectMapper.writeValueAsString(requestDto)))\n");
        content.append("                .andExpect(status().isOk())\n");
        content.append("                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));\n\n");

        content.append("        verify(").append(serviceVar).append(").update").append(entityName)
                .append("(").append(serviceEqArguments).append(", any(").append(dtoName).append(".class));\n");
        content.append("    }\n\n");

        content.append("    @Test\n");
        content.append("    void shouldReturnNotFoundForPatch() throws Exception {\n");
        appendCompositePrimaryKeyDeclarations(content, primaryKeyColumns);
        content.append("        ").append(dtoName).append(" requestDto = createValidCreate")
                .append(entityName).append("Dto();\n");
        content.append("        given(").append(serviceVar).append(".update").append(entityName)
                .append("(").append(serviceEqArguments).append(", any(").append(dtoName).append(".class)))\n");
        content.append("                .willThrow(GeneratedRuntimeException.builder()\n");
        content.append("                        .code(ErrorCodes.NOT_FOUND)\n");
        content.append("                        .message(\"").append(entityName).append(" not found\")\n");
        content.append("                        .build());\n\n");

        content.append("        mockMvc.perform(patch(\"").append(apiPath).append(compositePathTemplate).append("\"");
        appendCompositePathArguments(content, primaryKeyColumns);
        content.append(")\n");
        content.append("                .contentType(MediaType.APPLICATION_JSON)\n");
        content.append("                .content(objectMapper.writeValueAsString(requestDto)))\n");
        content.append("                .andExpect(status().isNotFound());\n");
        content.append("    }\n\n");

        //  FIX: call the missing method
        appendCompositePatchInternalServerErrorTest(
                content,
                entityName,
                dtoName,
                serviceVar,
                apiPath,
                primaryKeyColumns
        );
    }


    /**
     * Builds ordered composite primary key arguments wrapped with eq(...) for Mockito calls.
     *
     * @param primaryKeyColumns primary key columns
     * @return comma-separated Mockito eq(...) argument list
     */
    private String buildCompositeEqServiceArguments(List<Column> primaryKeyColumns) {
        StringBuilder stringBuilder = new StringBuilder();

        for (int index = 0; index < primaryKeyColumns.size(); index++) {
            if (index > 0) {
                stringBuilder.append(", ");
            }

            stringBuilder.append("eq(")
                    .append(resolvePkParamName(primaryKeyColumns.get(index)))
                    .append(")");
        }

        return stringBuilder.toString();
    }

    /**
     * Appends composite-primary-key delete tests.
     *
     * @param content generated test content
     * @param entityName entity simple name
     * @param serviceVar service variable name
     * @param apiPath api base path
     * @param primaryKeyColumns primary key columns
     */
    private void appendCompositeDeleteTests(StringBuilder content,
                                            String entityName,
                                            String serviceVar,
                                            String apiPath,
                                            List<Column> primaryKeyColumns) {
        String serviceArguments = buildCompositeServiceArguments(primaryKeyColumns);
        String compositePathTemplate = buildCompositePathTemplate(primaryKeyColumns);

        content.append("    @Test\n");
        content.append("    void shouldReturnNoContentForDelete() throws Exception {\n");
        appendCompositePrimaryKeyDeclarations(content, primaryKeyColumns);
        content.append("        willDoNothing().given(").append(serviceVar).append(").delete")
                .append(entityName).append("(").append(serviceArguments).append(");\n\n");

        content.append("        mockMvc.perform(delete(\"").append(apiPath).append(compositePathTemplate).append("\"");
        appendCompositePathArguments(content, primaryKeyColumns);
        content.append("))\n");
        content.append("                .andExpect(status().isNoContent());\n\n");

        content.append("        verify(").append(serviceVar).append(").delete").append(entityName)
                .append("(").append(serviceArguments).append(");\n");
        content.append("    }\n\n");

        content.append("    @Test\n");
        content.append("    void shouldReturnNotFoundForDelete() throws Exception {\n");
        appendCompositePrimaryKeyDeclarations(content, primaryKeyColumns);
        content.append("        willThrow(GeneratedRuntimeException.builder()\n");
        content.append("                .code(ErrorCodes.NOT_FOUND)\n");
        content.append("                .message(\"").append(entityName).append(" not found\")\n");
        content.append("                .build())\n");
        content.append("                .given(").append(serviceVar).append(").delete")
                .append(entityName).append("(").append(serviceArguments).append(");\n\n");

        content.append("        mockMvc.perform(delete(\"").append(apiPath).append(compositePathTemplate).append("\"");
        appendCompositePathArguments(content, primaryKeyColumns);
        content.append("))\n");
        content.append("                .andExpect(status().isNotFound());\n");
        content.append("    }\n\n");
    }

    /**
     * Builds the URI template suffix for composite primary key endpoints.
     *
     * @param primaryKeyColumns primary key columns
     * @return path template suffix like "/{idPart1}/{idPart2}"
     */
    private String buildCompositePathTemplate(List<Column> primaryKeyColumns) {
        StringBuilder stringBuilder = new StringBuilder();

        for (Column primaryKeyColumn : primaryKeyColumns) {
            String parameterName = resolvePkParamName(primaryKeyColumn);
            stringBuilder.append("/{").append(parameterName).append("}");
        }

        return stringBuilder.toString();
    }

    /**
     * Appends sample declarations for composite primary key fields.
     *
     * @param content generated test content
     * @param primaryKeyColumns primary key columns
     */
    private void appendCompositePrimaryKeyDeclarations(StringBuilder content,
                                                       List<Column> primaryKeyColumns) {
        for (int index = 0; index < primaryKeyColumns.size(); index++) {
            Column primaryKeyColumn = primaryKeyColumns.get(index);
            String javaType = detectJavaTypeForPkColumn(primaryKeyColumn);
            String parameterName = resolvePkParamName(primaryKeyColumn);
            String sampleValue = sampleValueForType(javaType, index + 1);

            content.append("        ").append(javaType).append(" ")
                    .append(parameterName).append(" = ").append(sampleValue).append(";\n");
        }

        content.append("\n");
    }

    /**
     * Resolves a safe camelCase parameter name for a primary key column.
     *
     * @param column primary key column
     * @return normalized parameter name
     */
    private String resolvePkParamName(Column column) {
        if (column == null || column.getName() == null || column.getName().isBlank()) {
            return "id";
        }

        String columnName = GeneratorSupport.unquoteIdentifier(column.getName());
        if (columnName == null || columnName.isBlank()) {
            return "id";
        }

        return NamingConverter.toCamelCase(columnName);
    }

    /**
     * Resolves the Java type for a primary key column.
     *
     * @param column primary key column
     * @return resolved Java type
     */
    private String detectJavaTypeForPkColumn(Column column) {
        return detectJavaTypeForPrimaryKeyColumn(column);
    }

    /**
     * Appends ordered composite primary key path arguments for MockMvc requests.
     *
     * @param content generated test content
     * @param primaryKeyColumns primary key columns
     */
    private void appendCompositePathArguments(StringBuilder content,
                                              List<Column> primaryKeyColumns) {
        for (Column primaryKeyColumn : primaryKeyColumns) {
            content.append(", ").append(resolvePkParamName(primaryKeyColumn));
        }
    }

    /**
     * Builds ordered composite primary key arguments for service calls.
     *
     * @param primaryKeyColumns primary key columns
     * @return comma-separated service argument list
     */
    private String buildCompositeServiceArguments(List<Column> primaryKeyColumns) {
        StringBuilder stringBuilder = new StringBuilder();

        for (int index = 0; index < primaryKeyColumns.size(); index++) {
            if (index > 0) {
                stringBuilder.append(", ");
            }

            stringBuilder.append(resolvePkParamName(primaryKeyColumns.get(index)));
        }

        return stringBuilder.toString();
    }

    /**
     * Produces a sample Java literal for the provided simple type.
     *
     * @param javaType simple Java type name
     * @param variant sample variant index
     * @return Java literal source
     */
    private String sampleValueForType(String javaType, int variant) {
        String normalizedType = GeneratorSupport.trimToEmpty(javaType);

        return switch (normalizedType) {
            case "UUID" -> switch (variant) {
                case 1 -> "UUID.fromString(\"123e4567-e89b-12d3-a456-426614174000\")";
                case 2 -> "UUID.fromString(\"223e4567-e89b-12d3-a456-426614174000\")";
                default -> "UUID.fromString(\"323e4567-e89b-12d3-a456-426614174000\")";
            };
            case "BigDecimal" -> "new BigDecimal(\"" + variant + "\")";
            case "BigInteger" -> "new BigInteger(\"" + variant + "\")";
            case "Short", "short" -> "(short) " + variant;
            case "Byte", "byte" -> "(byte) " + variant;
            case "Boolean", "boolean" -> (variant % 2 != 0) ? "true" : "false";
            case "String" -> "\"A\"";
            case "Long", "long" -> variant + "L";
            case "Integer", "int" -> String.valueOf(variant);
            case "Double", "double" -> variant + ".0d";
            case "Float", "float" -> variant + ".0f";
            case "Character", "char" -> "'A'";
            case "LocalDate" -> "LocalDate.of(2025, " + variant + ", " + (variant + 10) + ")";
            case "LocalDateTime" -> "LocalDateTime.of(2025, " + variant + ", " + (variant + 10) + ", 10, 0)";
            case "LocalTime" -> "LocalTime.of(" + (variant + 9) + ", " + (variant + 5) + ")";
            case "Instant" -> "Instant.parse(\"2025-01-0" + variant + "T10:15:30Z\")";
            case "OffsetDateTime" -> "OffsetDateTime.parse(\"2025-01-0" + variant + "T10:15:30+02:00\")";
            case "ZonedDateTime" -> "ZonedDateTime.parse(\"2025-01-0" + variant + "T10:15:30+02:00[Europe/Athens]\")";
            default -> "null";
        };
    }

    /**
     * Appends patch test when service throws unexpected exception.
     *
     * @param content generated test content
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param serviceVar service variable name
     * @param apiPath api base path
     * @param primaryKeyType primary key type
     * @param sampleId sample id value
     */
    private void appendSinglePatchInternalServerErrorTest(
            StringBuilder content,
            String entityName,
            String dtoName,
            String serviceVar,
            String apiPath,
            String primaryKeyType,
            String sampleId
    ) {
        content.append("    @Test\n");
        content.append("    void shouldReturnInternalServerErrorForPatchWhenServiceThrowsUnexpectedException() throws Exception {\n");
        content.append("        ").append(primaryKeyType).append(" id = ").append(sampleId).append(";\n");
        content.append("        ").append(dtoName).append(" requestDto = createValidCreate")
                .append(entityName).append("Dto();\n");
        content.append("        given(").append(serviceVar).append(".update").append(entityName)
                .append("(eq(id), any(").append(dtoName).append(".class)))\n");
        content.append("                .willThrow(new RuntimeException(\"Unexpected error\"));\n\n");

        content.append("        mockMvc.perform(patch(\"").append(apiPath).append("/{id}\", id)\n");
        content.append("                .contentType(MediaType.APPLICATION_JSON)\n");
        content.append("                .content(objectMapper.writeValueAsString(requestDto)))\n");
        content.append("                .andExpect(status().isInternalServerError());\n");
        content.append("    }\n\n");
    }

    /**
     * Determines whether the table uses a composite primary key.
     *
     * @param table current table
     * @return true when more than one primary key column exists
     */
    private boolean hasCompositePrimaryKey(Table table) {
        return getPrimaryKeyColumns(table).size() > 1;
    }

    /**
     * Returns all primary key columns of the table.
     *
     * @param table current table
     * @return primary key columns
     */
    private List<Column> getPrimaryKeyColumns(Table table) {
        if (table == null || table.getColumns() == null || table.getColumns().isEmpty()) {
            return List.of();
        }

        return table.getColumns().stream()
                .filter(Objects::nonNull)
                .filter(Column::isPrimaryKey)
                .toList();
    }

    /**
     * Resolves the Java type for a primary key column.
     *
     * @param column primary key column
     * @return resolved Java type
     */
    private String detectJavaTypeForPrimaryKeyColumn(Column column) {
        if (column == null || column.getJavaType() == null || column.getJavaType().isBlank()) {
            return "Long";
        }

        return JavaTypeSupport.resolveSimpleType(column.getJavaType());
    }

    /**
     * Resolves the Java type for a single primary key.
     *
     * @param table current table
     * @return resolved Java type
     */
    private String detectSinglePrimaryKeyType(Table table) {
        List<Column> primaryKeyColumns = getPrimaryKeyColumns(table);

        if (primaryKeyColumns.isEmpty()) {
            throw new IllegalStateException("No primary key found for table: " + table.getName());
        }

        if (primaryKeyColumns.size() > 1) {
            throw new IllegalStateException("Composite primary key is not supported here");
        }

        return detectJavaTypeForPrimaryKeyColumn(primaryKeyColumns.getFirst());
    }

    /**
     * Resolves mandatory Java type imports required by generated controller tests.
     *
     * <p>
     * This method covers Java types that appear directly in generated test code
     * through primary key declarations or DTO fixture values.
     * </p>
     *
     * @param table current table
     * @param dtoFields actual DTO fields
     * @param requiredDtoFieldNames required DTO field names
     * @return required import lines
     */
    private Set<String> resolveMandatoryJavaTypeImports(
            Table table,
            List<Field> dtoFields,
            List<String> requiredDtoFieldNames
    ) {
        Set<String> requiredImports = new java.util.TreeSet<>();

        if (table != null) {
            for (Column primaryKeyColumn : getPrimaryKeyColumns(table)) {
                String primaryKeyType = detectJavaTypeForPrimaryKeyColumn(primaryKeyColumn);
                addMandatoryImportForType(requiredImports, primaryKeyType);
            }
        }

        if (dtoFields == null || dtoFields.isEmpty()
                || requiredDtoFieldNames == null || requiredDtoFieldNames.isEmpty()) {
            return requiredImports;
        }

        Set<String> requiredFieldNameSet = new java.util.LinkedHashSet<>(requiredDtoFieldNames);

        for (Field field : dtoFields) {
            if (field == null || field.getName() == null || field.getType() == null) {
                continue;
            }

            if (!requiredFieldNameSet.contains(field.getName())) {
                continue;
            }

            if ("id".equals(field.getName())
                    || "dateCreated".equals(field.getName())
                    || "lastUpdated".equals(field.getName())) {
                continue;
            }

            String simpleType = resolveSimpleDtoType(field.getType());
            addMandatoryImportForType(requiredImports, simpleType);
        }

        return requiredImports;
    }


    /**
     * Adds the fully qualified import statement required for the given simple Java type.
     *
     * @param requiredImports collected import lines
     * @param simpleType simple Java type name
     */
    private void addMandatoryImportForType(Set<String> requiredImports, String simpleType) {
        if (requiredImports == null || simpleType == null || simpleType.isBlank()) {
            return;
        }

        switch (simpleType) {
            case "BigDecimal" -> requiredImports.add("import java.math.BigDecimal;");
            case "BigInteger" -> requiredImports.add("import java.math.BigInteger;");
            case "LocalDate" -> requiredImports.add("import java.time.LocalDate;");
            case "LocalDateTime" -> requiredImports.add("import java.time.LocalDateTime;");
            case "LocalTime" -> requiredImports.add("import java.time.LocalTime;");
            case "Instant" -> requiredImports.add("import java.time.Instant;");
            case "OffsetDateTime" -> requiredImports.add("import java.time.OffsetDateTime;");
            case "ZonedDateTime" -> requiredImports.add("import java.time.ZonedDateTime;");
            case "UUID" -> requiredImports.add("import java.util.UUID;");
            default -> {
            }
        }
    }




    /**
     * Determines whether a column contributes the given Java type
     * to generated controller test source.
     *
     * @param column current column
     * @param dtoFieldTypes actual DTO field types
     * @param expectedType expected simple Java type
     * @return true when the generated test code uses the given type
     */
    private boolean usesTypeInGeneratedTest(
            Column column,
            Map<String, String> dtoFieldTypes,
            String expectedType
    ) {
        if (column == null || expectedType == null || expectedType.isBlank()) {
            return false;
        }

        // Primary key handling
        if (column.isPrimaryKey()) {
            return expectedType.equals(detectJavaTypeForPrimaryKeyColumn(column));
        }

        if (shouldSkipCreateField(column)) {
            return false;
        }

        String dtoFieldName = resolveFlatDtoFieldName(column, dtoFieldTypes);
        if (dtoFieldName == null) {
            return false;
        }

        String dtoFieldType = dtoFieldTypes.get(dtoFieldName);
        if (dtoFieldType == null || !isSupportedScalarDtoType(dtoFieldType)) {
            return false;
        }

        String simpleType = resolveSimpleDtoType(dtoFieldType);
        return expectedType.equals(simpleType);
    }
}