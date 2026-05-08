package com.sqldomaingen.generator;

import com.sqldomaingen.model.Relationship;
import com.sqldomaingen.util.*;
import com.sqldomaingen.model.Table;
import lombok.extern.log4j.Log4j2;
import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.UniqueConstraint;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Generates Service interfaces and ServiceImpl classes (domain style),
 * using the project's base package and placing output under src/main/java.
 * Conventions:
 * service package:     {basePackage}.service
 * serviceImpl package: {basePackage}.serviceImpl
 * DTO suffix:          Dto
 * Repository suffix:   Repository
 * Mapper suffix:       Mapper
 */
@Log4j2
public class ServiceGenerator {



    /**
     * Generates service interfaces and implementations for all parsed tables.
     *
     * @param tables parsed SQL tables
     * @param outputDir base output directory
     * @param basePackage base package
     */
    public void generateAllServices(List<Table> tables, String outputDir, String basePackage) {
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        Path serviceDir = GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(outputDir, basePackage, "service")
        );
        Path serviceImplDir = GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(outputDir, basePackage, "serviceImpl")
        );

        for (Table table : tables) {
            String entityName = NamingConverter.toPascalCase(
                    GeneratorSupport.normalizeTableName(table.getName())
            );

            String serviceInterfaceCode = generateServiceInterface(table, basePackage);
            GeneratorSupport.writeFile(serviceDir.resolve(entityName + "Service.java"), serviceInterfaceCode);

            String serviceImplCode = generateServiceImpl(table, basePackage);
            GeneratorSupport.writeFile(serviceImplDir.resolve(entityName + "ServiceImpl.java"), serviceImplCode);
        }

        log.info("Services generated under: {}", serviceDir.getParent().toAbsolutePath());
    }

    /**
     * Generates the Service interface for one entity.
     * @param table source table metadata
     * @param basePackage base package
     * @return Java source code
     */
    public String generateServiceInterface(Table table, String basePackage) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        String entityName = NamingConverter.toPascalCase(
                GeneratorSupport.normalizeTableName(table.getName())
        );
        String dtoName = entityName + Constants.DTO_SUFFIX;

        String servicePackage = PackageResolver.resolvePackageName(basePackage, "service");
        String dtoPackage = PackageResolver.resolvePackageName(basePackage, "dto");
        String modelPackage = PackageResolver.resolvePackageName(basePackage, "entity");

        List<com.sqldomaingen.model.Column> primaryKeyColumns = table.getColumns().stream()
                .filter(Objects::nonNull)
                .filter(com.sqldomaingen.model.Column::isPrimaryKey)
                .toList();

        boolean compositePrimaryKey = primaryKeyColumns.size() > 1;

        PrimaryKeySupport.TypeRef pkType = compositePrimaryKey
                ? null
                : PrimaryKeySupport.resolvePrimaryKeyTypeRef(table, entityName, modelPackage);

        String pluralMethodSuffix = NamingConverter.toPascalCase(
                NamingConverter.toCamelCasePlural(entityName)
        );
        String pluralLowerDisplayLabel = NamingConverter.toLogLabel(
                NamingConverter.toCamelCasePlural(entityName)
        );

        java.util.LinkedHashSet<String> importLines = new java.util.LinkedHashSet<>();
        importLines.add("import " + dtoPackage + "." + dtoName + ";");
        importLines.add("import java.util.List;");

        if (!compositePrimaryKey && pkType.importLine() != null && !pkType.importLine().isBlank()) {
            importLines.add(pkType.importLine());
        }

        StringBuilder idMethodParameters = new StringBuilder();
        StringBuilder updateMethodParameters = new StringBuilder();

        if (compositePrimaryKey) {
            for (int index = 0; index < primaryKeyColumns.size(); index++) {
                com.sqldomaingen.model.Column primaryKeyColumn = primaryKeyColumns.get(index);

                String columnName = GeneratorSupport.unquoteIdentifier(primaryKeyColumn.getName());
                if (columnName == null || columnName.isBlank()) {
                    columnName = "id";
                }

                String parameterName = NamingConverter.toCamelCase(columnName);

                String rawJavaType = primaryKeyColumn.getJavaType();
                String parameterType;
                String importLine = null;

                if (rawJavaType == null || rawJavaType.isBlank()) {
                    parameterType = "Long";
                } else if (rawJavaType.contains(".")) {
                    parameterType = rawJavaType.substring(rawJavaType.lastIndexOf('.') + 1);
                    importLine = "import " + rawJavaType + ";";
                } else {
                    parameterType = JavaTypeSupport.resolveSimpleType(rawJavaType);
                    importLine = JavaTypeSupport.resolveImportLine(rawJavaType);
                }

                if (importLine != null && !importLine.isBlank()) {
                    importLines.add(importLine);
                }

                if (index > 0) {
                    idMethodParameters.append(", ");
                    updateMethodParameters.append(", ");
                }

                idMethodParameters.append(parameterType).append(" ").append(parameterName);
                updateMethodParameters.append(parameterType).append(" ").append(parameterName);
            }

            if (!updateMethodParameters.isEmpty()) {
                updateMethodParameters.append(", ");
            }
            updateMethodParameters.append(dtoName).append(" dto");
        } else {
            idMethodParameters.append(pkType.simpleName()).append(" id");
            updateMethodParameters.append(pkType.simpleName()).append(" id, ").append(dtoName).append(" dto");
        }

        StringBuilder sb = new StringBuilder();

        sb.append("package ").append(servicePackage).append(";\n\n");
        for (String importLine : importLines) {
            sb.append(importLine).append("\n");
        }
        sb.append("\n");

        sb.append("/**\n");
        sb.append(" * Service contract for {@code ").append(entityName).append("} domain operations.\n");
        sb.append(" */\n");
        sb.append("public interface ").append(entityName).append("Service {\n\n");

        sb.append("    /**\n");
        sb.append("     * Retrieves all ").append(pluralLowerDisplayLabel).append(".\n");
        sb.append("     * @return non-null list of {@link ").append(dtoName).append("}\n");
        sb.append("     */\n");
        sb.append("    List<").append(dtoName).append("> getAll").append(pluralMethodSuffix).append("();\n\n");

        sb.append("    /**\n");
        sb.append("     * Retrieves a record by id.\n");

        if (compositePrimaryKey) {
            for (com.sqldomaingen.model.Column primaryKeyColumn : primaryKeyColumns) {
                String columnName = GeneratorSupport.unquoteIdentifier(primaryKeyColumn.getName());
                if (columnName == null || columnName.isBlank()) {
                    columnName = "id";
                }

                String parameterName = NamingConverter.toCamelCase(columnName);
                sb.append("     * @param ").append(parameterName).append(" the ").append(columnName).append(" value\n");
            }
        } else {
            sb.append("     * @param id the record id\n");
        }

        sb.append("     * @return the matching {@link ").append(dtoName).append("}\n");
        sb.append("     */\n");
        sb.append("    ").append(dtoName).append(" get").append(entityName).append("ById(")
                .append(idMethodParameters).append(");\n\n");

        sb.append("    /**\n");
        sb.append("     * Creates a new record.\n");
        sb.append("     * @param dto input payload\n");
        sb.append("     * @return created {@link ").append(dtoName).append("}\n");
        sb.append("     */\n");
        sb.append("    ").append(dtoName).append(" create").append(entityName).append("(")
                .append(dtoName).append(" dto);\n\n");

        sb.append("    /**\n");
        sb.append("     * Updates an existing record.\n");
        sb.append("     * <p>\n");
        sb.append("     * Only non-null fields from the DTO are applied to the existing entity.\n");

        if (compositePrimaryKey) {
            for (com.sqldomaingen.model.Column primaryKeyColumn : primaryKeyColumns) {
                String columnName = GeneratorSupport.unquoteIdentifier(primaryKeyColumn.getName());
                if (columnName == null || columnName.isBlank()) {
                    columnName = "id";
                }

                String parameterName = NamingConverter.toCamelCase(columnName);
                sb.append("     * @param ").append(parameterName).append(" the ").append(columnName).append(" value\n");
            }
        } else {
            sb.append("     * @param id the record id\n");
        }

        sb.append("     * @param dto input payload with partial fields\n");
        sb.append("     * @return updated {@link ").append(dtoName).append("}\n");
        sb.append("     */\n");
        sb.append("    ").append(dtoName).append(" update").append(entityName).append("(")
                .append(updateMethodParameters).append(");\n\n");

        sb.append("    /**\n");
        sb.append("     * Deletes a record by id.\n");

        if (compositePrimaryKey) {
            for (com.sqldomaingen.model.Column primaryKeyColumn : primaryKeyColumns) {
                String columnName = GeneratorSupport.unquoteIdentifier(primaryKeyColumn.getName());
                if (columnName == null || columnName.isBlank()) {
                    columnName = "id";
                }

                String parameterName = NamingConverter.toCamelCase(columnName);
                sb.append("     * @param ").append(parameterName).append(" the ").append(columnName).append(" value\n");
            }
        } else {
            sb.append("     * @param id the record id\n");
        }

        sb.append("     */\n");
        sb.append("    void delete").append(entityName).append("(")
                .append(idMethodParameters).append(");\n");

        sb.append("}\n");

        return sb.toString();
    }

    /**
     * Generates the ServiceImpl class for one entity.
     *
     * @param table source table metadata
     * @param basePackage base package
     * @return Java source code
     */
    public String generateServiceImpl(Table table, String basePackage) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        String entityName = NamingConverter.toPascalCase(
                GeneratorSupport.normalizeTableName(table.getName())
        );
        String dtoName = entityName + Constants.DTO_SUFFIX;
        String repositoryName = entityName + "Repository";
        String mapperName = entityName + "Mapper";
        String serviceName = entityName + "Service";

        String repositoryVariableName = NamingConverter.decapitalizeFirstLetter(entityName) + "Repository";
        String mapperVariableName = NamingConverter.decapitalizeFirstLetter(entityName) + "Mapper";

        String serviceImplPackage = PackageResolver.resolvePackageName(basePackage, "serviceImpl");
        String dtoPackage = PackageResolver.resolvePackageName(basePackage, "dto");
        String mapperPackage = PackageResolver.resolvePackageName(basePackage, "mapper");
        String modelPackage = PackageResolver.resolvePackageName(basePackage, "entity");
        String repositoryPackage = PackageResolver.resolvePackageName(basePackage, "repository");
        String servicePackage = PackageResolver.resolvePackageName(basePackage, "service");
        String exceptionPackage = PackageResolver.resolvePackageName(basePackage, "exception");

        List<Column> primaryKeyColumns = table.getColumns().stream()
                .filter(Objects::nonNull)
                .filter(Column::isPrimaryKey)
                .toList();

        boolean compositePrimaryKey = primaryKeyColumns.size() > 1;

        PrimaryKeySupport.TypeRef pkType =
                PrimaryKeySupport.resolvePrimaryKeyTypeRef(table, entityName, modelPackage);

        String primaryKeyType = compositePrimaryKey
                ? entityName + "Key"
                : pkType.simpleName();

        String primaryKeyImportLine = compositePrimaryKey
                ? "import " + modelPackage + "." + primaryKeyType + ";"
                : pkType.importLine();

        java.util.LinkedHashSet<String> additionalImportLines = resolveCompositePrimaryKeyImportLines(primaryKeyColumns);

        String lowerDisplayLabel = NamingConverter.toLogLabel(entityName);
        if (lowerDisplayLabel.isBlank()) {
            lowerDisplayLabel = entityName;
        }

        String displayLabel = buildDisplayLabel(entityName, lowerDisplayLabel);

        String pluralMethodSuffix = NamingConverter.toPascalCase(
                NamingConverter.toCamelCasePlural(entityName)
        );
        String pluralLowerDisplayLabel = NamingConverter.toLogLabel(
                NamingConverter.toCamelCasePlural(entityName)
        );

        String findByIdOrThrowMethodName = "find" + entityName + "ByIdOrThrow";
        String createNotFoundExceptionMethodName = "create" + entityName + "NotFoundException";
        String validateDoesNotExistMethodName = "validate" + entityName + "DoesNotExist";
        String buildKeyMethodName = "buildKey";
        String buildCompositeIdMethodName = "buildCompositeId";

        StringBuilder stringBuilder = new StringBuilder();

        appendServiceImplPackage(stringBuilder, serviceImplPackage);
        appendServiceImplImports(
                stringBuilder,
                dtoPackage,
                dtoName,
                mapperPackage,
                mapperName,
                modelPackage,
                entityName,
                repositoryPackage,
                repositoryName,
                servicePackage,
                serviceName,
                exceptionPackage,
                primaryKeyImportLine,
                additionalImportLines
        );
        appendServiceImplClassHeader(stringBuilder, entityName, serviceName, displayLabel);
        appendServiceImplFields(stringBuilder, repositoryName, repositoryVariableName, mapperName, mapperVariableName);

        appendGetAllServiceMethod(
                stringBuilder,
                dtoName,
                pluralMethodSuffix,
                pluralLowerDisplayLabel,
                repositoryVariableName,
                mapperVariableName
        );
        appendGetByIdServiceMethod(
                stringBuilder,
                entityName,
                dtoName,
                lowerDisplayLabel,
                mapperVariableName,
                findByIdOrThrowMethodName,
                buildCompositeIdMethodName,
                primaryKeyType,
                primaryKeyColumns,
                compositePrimaryKey
        );
        appendCreateServiceMethod(
                stringBuilder,
                table,
                entityName,
                dtoName,
                lowerDisplayLabel,
                repositoryVariableName,
                mapperVariableName,
                validateDoesNotExistMethodName,
                compositePrimaryKey
        );
        appendPatchServiceMethod(
                stringBuilder,
                entityName,
                dtoName,
                lowerDisplayLabel,
                repositoryVariableName,
                mapperVariableName,
                findByIdOrThrowMethodName,
                buildCompositeIdMethodName,
                primaryKeyType,
                primaryKeyColumns,
                compositePrimaryKey
        );
        appendDeleteServiceMethod(
                stringBuilder,
                entityName,
                lowerDisplayLabel,
                repositoryVariableName,
                findByIdOrThrowMethodName,
                buildKeyMethodName,
                buildCompositeIdMethodName,
                primaryKeyType,
                primaryKeyColumns,
                compositePrimaryKey
        );

        appendCreateUniqueValidationMethod(
                stringBuilder,
                table,
                entityName,
                dtoName,
                repositoryVariableName,
                compositePrimaryKey
        );

        appendFindByIdOrThrowServiceMethod(
                stringBuilder,
                entityName,
                lowerDisplayLabel,
                repositoryVariableName,
                findByIdOrThrowMethodName,
                createNotFoundExceptionMethodName,
                buildKeyMethodName,
                primaryKeyType,
                primaryKeyColumns,
                compositePrimaryKey
        );
        appendCreateNotFoundExceptionServiceMethod(
                stringBuilder,
                entityName,
                lowerDisplayLabel,
                createNotFoundExceptionMethodName,
                buildCompositeIdMethodName,
                primaryKeyType,
                primaryKeyColumns,
                compositePrimaryKey
        );

        if (compositePrimaryKey) {
            appendValidateDoesNotExistServiceMethod(
                    stringBuilder,
                    entityName,
                    dtoName,
                    repositoryVariableName,
                    validateDoesNotExistMethodName,
                    buildKeyMethodName,
                    buildCompositeIdMethodName,
                    primaryKeyType,
                    primaryKeyColumns,
                    table
            );
            appendBuildKeyServiceMethod(
                    stringBuilder,
                    primaryKeyType,
                    primaryKeyColumns,
                    buildKeyMethodName
            );
            appendBuildCompositeIdServiceMethod(
                    stringBuilder,
                    primaryKeyColumns,
                    buildCompositeIdMethodName
            );
        }

        appendServiceImplClassFooter(stringBuilder);

        return stringBuilder.toString();
    }

    /**
     * Appends the package declaration for the generated ServiceImpl.
     *
     * @param stringBuilder target source builder
     * @param serviceImplPackage serviceImpl package name
     */
    private void appendServiceImplPackage(StringBuilder stringBuilder, String serviceImplPackage) {
        stringBuilder.append("package ").append(serviceImplPackage).append(";\n\n");
    }

    /**
     * Appends import lines for the generated ServiceImpl.
     *
     * <p>
     * This method ensures that duplicate import statements are eliminated
     * by collecting all imports in an ordered set before writing them.
     * </p>
     *
     * @param stringBuilder target source builder
     * @param dtoPackage dto package name
     * @param dtoName dto simple name
     * @param mapperPackage mapper package name
     * @param mapperName mapper simple name
     * @param modelPackage entity package name
     * @param entityName entity simple name
     * @param repositoryPackage repository package name
     * @param repositoryName repository simple name
     * @param servicePackage service package name
     * @param serviceName service interface simple name
     * @param exceptionPackage exception package name
     * @param primaryKeyImportLine optional primary key import line
     * @param additionalImportLines additional import lines required by composite PK parameters
     */
    private void appendServiceImplImports(
            StringBuilder stringBuilder,
            String dtoPackage,
            String dtoName,
            String mapperPackage,
            String mapperName,
            String modelPackage,
            String entityName,
            String repositoryPackage,
            String repositoryName,
            String servicePackage,
            String serviceName,
            String exceptionPackage,
            String primaryKeyImportLine,
            java.util.Collection<String> additionalImportLines
    ) {
        java.util.LinkedHashSet<String> imports = new java.util.LinkedHashSet<>();

        // Core imports
        imports.add("import " + dtoPackage + "." + dtoName + ";");
        imports.add("import " + mapperPackage + "." + mapperName + ";");
        imports.add("import " + modelPackage + "." + entityName + ";");
        imports.add("import " + repositoryPackage + "." + repositoryName + ";");
        imports.add("import " + servicePackage + "." + serviceName + ";");
        imports.add("import " + exceptionPackage + ".ErrorCodes;");
        imports.add("import " + exceptionPackage + ".GeneratedRuntimeException;");

        // PK import
        if (primaryKeyImportLine != null && !primaryKeyImportLine.isBlank()) {
            imports.add(primaryKeyImportLine);
        }

        if (additionalImportLines != null) {
            for (String line : additionalImportLines) {
                if (line != null && !line.isBlank()) {
                    imports.add(line);
                }
            }
        }

        // Framework imports
        imports.add("import jakarta.transaction.Transactional;");
        imports.add("import lombok.RequiredArgsConstructor;");
        imports.add("import lombok.extern.log4j.Log4j2;");
        imports.add("import org.springframework.stereotype.Service;");
        imports.add("import java.util.List;");

        for (String importLine : imports) {
            stringBuilder.append(importLine).append("\n");
        }

        stringBuilder.append("\n");
    }

    /**
     * Appends the class header for the generated ServiceImpl.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param serviceName service interface simple name
     * @param displayLabel human-readable display label
     */
    private void appendServiceImplClassHeader(
            StringBuilder stringBuilder,
            String entityName,
            String serviceName,
            String displayLabel
    ) {
        stringBuilder.append("/**\n");
        stringBuilder.append(" * Service implementation for {@code ").append(displayLabel).append("} domain operations.\n");
        stringBuilder.append(" */\n");
        stringBuilder.append("@Service\n");
        stringBuilder.append("@RequiredArgsConstructor\n");
        stringBuilder.append("@Transactional\n");
        stringBuilder.append("@Log4j2\n");
        stringBuilder.append("public class ").append(entityName)
                .append("ServiceImpl implements ").append(serviceName).append(" {\n\n");
    }

    /**
     * Appends repository and mapper fields for the generated ServiceImpl.
     *
     * @param stringBuilder target source builder
     * @param repositoryName repository simple name
     * @param repositoryVariableName repository variable name
     * @param mapperName mapper simple name
     * @param mapperVariableName mapper variable name
     */
    private void appendServiceImplFields(
            StringBuilder stringBuilder,
            String repositoryName,
            String repositoryVariableName,
            String mapperName,
            String mapperVariableName
    ) {
        stringBuilder.append("    private final ").append(repositoryName).append(" ")
                .append(repositoryVariableName).append(";\n");
        stringBuilder.append("    private final ").append(mapperName).append(" ")
                .append(mapperVariableName).append(";\n\n");
    }

    /**
     * Appends the get all service method.
     *
     * @param stringBuilder target source builder
     * @param dtoName dto simple name
     * @param pluralMethodSuffix plural method suffix
     * @param pluralLowerDisplayLabel lowercase plural display label
     * @param repositoryVariableName repository variable name
     * @param mapperVariableName mapper variable name
     */
    private void appendGetAllServiceMethod(
            StringBuilder stringBuilder,
            String dtoName,
            String pluralMethodSuffix,
            String pluralLowerDisplayLabel,
            String repositoryVariableName,
            String mapperVariableName
    ) {
        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Retrieves all ").append(pluralLowerDisplayLabel).append(" records.\n");
        stringBuilder.append("     * @return list of ").append(dtoName).append("\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    @Override\n");
        stringBuilder.append("    public List<").append(dtoName).append("> getAll")
                .append(pluralMethodSuffix).append("() {\n");
        stringBuilder.append("        log.info(\"Fetching all ").append(pluralLowerDisplayLabel).append(" records.\");\n");
        stringBuilder.append("        return ").append(mapperVariableName)
                .append(".toDTOList(").append(repositoryVariableName).append(".findAll());\n");
        stringBuilder.append("    }\n\n");
    }

    /**
     * Appends the get by id service method.
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param lowerDisplayLabel lowercase display label
     * @param mapperVariableName mapper variable name
     * @param findByIdOrThrowMethodName helper method name
     * @param buildCompositeIdMethodName composite id helper method name
     * @param primaryKeyType primary key simple type or composite key type
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey true when the entity uses a composite primary key
     */
    private void appendGetByIdServiceMethod(
            StringBuilder stringBuilder,
            String entityName,
            String dtoName,
            String lowerDisplayLabel,
            String mapperVariableName,
            String findByIdOrThrowMethodName,
            String buildCompositeIdMethodName,
            String primaryKeyType,
            List<Column> primaryKeyColumns,
            boolean compositePrimaryKey
    ) {
        String label = lowerDisplayLabel.replaceAll("\\s+", " ").trim();

        String methodParameters = compositePrimaryKey
                ? buildCompositePrimaryKeyMethodParameters(primaryKeyColumns)
                : primaryKeyType + " id";

        String methodArguments = compositePrimaryKey
                ? buildCompositePrimaryKeyMethodArguments(primaryKeyColumns)
                : "id";

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Retrieves a ").append(label).append(" record by id.\n");

        if (compositePrimaryKey) {
            appendCompositePrimaryKeyJavaDocParameters(stringBuilder, primaryKeyColumns);
        } else {
            stringBuilder.append("     * @param id the ").append(label).append(" id\n");
        }

        stringBuilder.append("     * @return ").append(dtoName).append("\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    @Override\n");
        stringBuilder.append("    public ").append(dtoName).append(" get").append(entityName)
                .append("ById(").append(methodParameters).append(") {\n");

        if (compositePrimaryKey) {
            stringBuilder.append("\n");
            stringBuilder.append("        String compositeId = ")
                    .append(buildCompositeIdMethodName)
                    .append("(")
                    .append(methodArguments)
                    .append(");\n");
            stringBuilder.append("        log.info(\"Fetching ").append(label)
                    .append(" with composite id: {}\", compositeId);\n\n");
        } else {
            stringBuilder.append("        log.info(\"Fetching ").append(label)
                    .append(" with id: {}\", id);\n\n");
        }

        stringBuilder.append("        ").append(entityName).append(" existingEntity = ")
                .append(findByIdOrThrowMethodName).append("(").append(methodArguments).append(");\n");
        stringBuilder.append("        return ").append(mapperVariableName).append(".toDTO(existingEntity);\n");
        stringBuilder.append("    }\n\n");
    }



    /**
     * Creates a NOT_FOUND exception for the entity.
     *
     * @param stringBuilder target builder
     * @param entityName entity name
     * @param lowerDisplayLabel display label
     * @param createNotFoundExceptionMethodName method name
     * @param buildCompositeIdMethodName composite id builder method
     * @param primaryKeyType primary key type
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey whether composite key is used
     */
    private void appendCreateNotFoundExceptionServiceMethod(
            StringBuilder stringBuilder,
            String entityName,
            String lowerDisplayLabel,
            String createNotFoundExceptionMethodName,
            String buildCompositeIdMethodName,
            String primaryKeyType,
            List<Column> primaryKeyColumns,
            boolean compositePrimaryKey
    ) {
        String methodParameters = compositePrimaryKey
                ? buildCompositePrimaryKeyMethodParameters(primaryKeyColumns)
                : primaryKeyType + " id";

        String methodArguments = compositePrimaryKey
                ? buildCompositePrimaryKeyMethodArguments(primaryKeyColumns)
                : "id";

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Creates a NOT FOUND exception for the ")
                .append(lowerDisplayLabel)
                .append(" entity.\n");

        if (compositePrimaryKey) {
            for (Column primaryKeyColumn : primaryKeyColumns) {
                String columnName = GeneratorSupport.unquoteIdentifier(primaryKeyColumn.getName());
                if (columnName == null || columnName.isBlank()) {
                    columnName = "id";
                }

                String parameterName = NamingConverter.toCamelCase(columnName);
                stringBuilder.append("     * @param ")
                        .append(parameterName)
                        .append(" the ")
                        .append(columnName)
                        .append(" value\n");
            }
        } else {
            stringBuilder.append("     * @param id the ")
                    .append(lowerDisplayLabel)
                    .append(" id\n");
        }

        stringBuilder.append("     * @return runtime exception\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    private RuntimeException ")
                .append(createNotFoundExceptionMethodName)
                .append("(")
                .append(methodParameters)
                .append(") {\n");

        if (compositePrimaryKey) {
            stringBuilder.append("        String compositeId = ")
                    .append(buildCompositeIdMethodName)
                    .append("(")
                    .append(methodArguments)
                    .append(");\n");
        }

        stringBuilder.append("        log.warn(\"")
                .append(entityName)
                .append(" not found with ")
                .append(compositePrimaryKey ? "composite id: {}" : "id: {}")
                .append("\", ")
                .append(compositePrimaryKey ? "compositeId" : "id")
                .append(");\n\n");

        stringBuilder.append("        return GeneratedRuntimeException.builder()\n");
        stringBuilder.append("                .code(ErrorCodes.NOT_FOUND)\n");
        stringBuilder.append("                .message(\"")
                .append(entityName)
                .append(" not found with ")
                .append(compositePrimaryKey ? "composite id: \" + compositeId" : "id: \" + id")
                .append(")\n");
        stringBuilder.append("                .build();\n");
        stringBuilder.append("    }\n\n");
    }

    /**
     * Appends the update service method.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param lowerDisplayLabel lowercase display label
     * @param repositoryVariableName repository variable name
     * @param mapperVariableName mapper variable name
     * @param findByIdOrThrowMethodName helper method name
     * @param buildCompositeIdMethodName composite id helper method name
     * @param primaryKeyType primary key simple type or composite key type
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey true when the entity uses a composite primary key
     */
    private void appendPatchServiceMethod(
            StringBuilder stringBuilder,
            String entityName,
            String dtoName,
            String lowerDisplayLabel,
            String repositoryVariableName,
            String mapperVariableName,
            String findByIdOrThrowMethodName,
            String buildCompositeIdMethodName,
            String primaryKeyType,
            List<Column> primaryKeyColumns,
            boolean compositePrimaryKey
    ) {
        String label = lowerDisplayLabel.replaceAll("\\s+", " ").trim();

        String methodParameters = compositePrimaryKey
                ? buildCompositePrimaryKeyMethodParametersWithDto(primaryKeyColumns, dtoName)
                : primaryKeyType + " id, " + dtoName + " dto";

        String idArguments = compositePrimaryKey
                ? buildCompositePrimaryKeyMethodArguments(primaryKeyColumns)
                : "id";

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Updates an existing ").append(label).append(" record.\n");
        stringBuilder.append("     *\n");

        if (compositePrimaryKey) {
            appendCompositePrimaryKeyJavaDocParameters(stringBuilder, primaryKeyColumns);
        } else {
            stringBuilder.append("     * @param id the ").append(label).append(" id\n");
        }

        stringBuilder.append("     * @param dto input payload\n");
        stringBuilder.append("     * @return updated {@link ").append(dtoName).append("}\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    @Override\n");
        stringBuilder.append("    public ").append(dtoName).append(" update").append(entityName)
                .append("(").append(methodParameters).append(") {\n");

        if (compositePrimaryKey) {
            stringBuilder.append("        String compositeId = ")
                    .append(buildCompositeIdMethodName)
                    .append("(")
                    .append(idArguments)
                    .append(");\n\n");
            stringBuilder.append("        log.info(\"Updating ").append(label)
                    .append(" with composite id: {}\", compositeId);\n\n");
        } else {
            stringBuilder.append("        log.info(\"Updating ").append(label)
                    .append(" with id: {}\", id);\n\n");
        }

        stringBuilder.append("        ").append(entityName).append(" existingEntity = ")
                .append(findByIdOrThrowMethodName).append("(").append(idArguments).append(");\n");
        stringBuilder.append("        ").append(mapperVariableName)
                .append(".partialUpdate(existingEntity, dto);\n");
        stringBuilder.append("        ").append(entityName).append(" savedEntity = ")
                .append(repositoryVariableName).append(".save(existingEntity);\n\n");
        stringBuilder.append("        return ")
                .append(mapperVariableName)
                .append(".toDTO(savedEntity);\n");
        stringBuilder.append("    }\n\n");
    }


    /**
     * Creates a new entity record.
     *
     * @param stringBuilder target source builder
     * @param table table metadata
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param lowerDisplayLabel lowercase display label
     * @param repositoryVariableName repository variable name
     * @param mapperVariableName mapper variable name
     * @param validateDoesNotExistMethodName validation helper method name
     * @param compositePrimaryKey whether entity has composite primary key
     */
    private void appendCreateServiceMethod(
            StringBuilder stringBuilder,
            Table table,
            String entityName,
            String dtoName,
            String lowerDisplayLabel,
            String repositoryVariableName,
            String mapperVariableName,
            String validateDoesNotExistMethodName,
            boolean compositePrimaryKey
    ) {
        String label = lowerDisplayLabel.replaceAll("\\s+", " ").trim();
        String validateCreateUniqueConstraintsMethodName = "validate" + entityName + "CreateUniqueConstraints";

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Creates a new ").append(label).append(" record.\n");
        stringBuilder.append("     * @param dto input payload\n");
        stringBuilder.append("     * @return created {@link ").append(dtoName).append("}\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    @Override\n");
        stringBuilder.append("    public ").append(dtoName).append(" create").append(entityName)
                .append("(").append(dtoName).append(" dto) {\n");
        stringBuilder.append("        log.info(\"Creating ").append(label).append(".\");\n\n");

        if (compositePrimaryKey) {
            appendCreateCompositePrimaryKeyValidationInvocation(
                    stringBuilder,
                    validateDoesNotExistMethodName
            );
        }

        if (hasCreateUniqueValidations(table, compositePrimaryKey)) {
            stringBuilder.append("        ").append(validateCreateUniqueConstraintsMethodName)
                    .append("(dto);\n\n");
        }

        stringBuilder.append("        ").append(entityName).append(" entity = ")
                .append(mapperVariableName).append(".toEntity(dto);\n");
        stringBuilder.append("        ").append(entityName).append(" savedEntity = ")
                .append(repositoryVariableName).append(".save(entity);\n\n");
        stringBuilder.append("        return ").append(mapperVariableName).append(".toDTO(savedEntity);\n");
        stringBuilder.append("    }\n\n");
    }


    /**
     * Appends the composite primary key validation invocation for the create method.
     *
     * @param stringBuilder target source builder
     * @param validateDoesNotExistMethodName validation helper method name
     */
    private void appendCreateCompositePrimaryKeyValidationInvocation(
            StringBuilder stringBuilder,
            String validateDoesNotExistMethodName
    ) {
        stringBuilder.append("        ")
                .append(validateDoesNotExistMethodName)
                .append("(dto);\n\n");
    }

    /**
     * Returns whether the noun creation flow has any unique validations to generate.
     *
     * @param table table metadata
     * @param compositePrimaryKey true when the entity uses a composite primary key
     * @return true when at least one unique validation exists
     */
    private boolean hasCreateUniqueValidations(Table table, boolean compositePrimaryKey) {
        if (!getEligibleInlineUniqueColumns(table).isEmpty()) {
            return true;
        }

        if (table.getUniqueConstraints() == null || table.getUniqueConstraints().isEmpty()) {
            return false;
        }

        List<String> primaryKeyColumnNames = table.getColumns().stream()
                .filter(Objects::nonNull)
                .filter(Column::isPrimaryKey)
                .map(Column::getName)
                .filter(Objects::nonNull)
                .map(GeneratorSupport::unquoteIdentifier)
                .map(String::toLowerCase)
                .sorted()
                .toList();

        return getEligibleCompositeUniqueConstraints(table).stream()
                .map(UniqueConstraint::getColumns)
                .filter(Objects::nonNull)
                .map(columns -> columns.stream()
                        .filter(Objects::nonNull)
                        .map(GeneratorSupport::unquoteIdentifier)
                        .map(String::toLowerCase)
                        .sorted()
                        .toList())
                .anyMatch(uniqueColumnNames -> !compositePrimaryKey || !uniqueColumnNames.equals(primaryKeyColumnNames));
    }


    /**
     * Appends the noun create unique constraints validation helper method.
     *
     * @param stringBuilder target builder
     * @param table table metadata
     * @param entityName entity name
     * @param dtoName dto name
     * @param repositoryVariableName repository variable name
     * @param compositePrimaryKey whether entity has composite primary key
     */
    private void appendCreateUniqueValidationMethod(
            StringBuilder stringBuilder,
            Table table,
            String entityName,
            String dtoName,
            String repositoryVariableName,
            boolean compositePrimaryKey
    ) {
        if (!hasCreateUniqueValidations(table, compositePrimaryKey)) {
            return;
        }

        String methodName = "validate" + entityName + "CreateUniqueConstraints";

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Validates unique constraints for create operations.\n");
        stringBuilder.append("     * @param dto input payload\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    private void ").append(methodName)
                .append("(").append(dtoName).append(" dto) {\n\n");

        appendCreateUniqueValidation(
                stringBuilder,
                table,
                entityName,
                repositoryVariableName,
                compositePrimaryKey
        );

        stringBuilder.append("    }\n\n");
    }




    /**
     * Appends the delete service method.
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param lowerDisplayLabel lowercase display label
     * @param repositoryVariableName repository variable name
     * @param findByIdOrThrowMethodName helper method name
     * @param buildKeyMethodName key builder method name
     * @param buildCompositeIdMethodName composite id helper method name
     * @param primaryKeyType primary key simple type or composite key type
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey true when the entity uses a composite primary key
     */
    private void appendDeleteServiceMethod(
            StringBuilder stringBuilder,
            String entityName,
            String lowerDisplayLabel,
            String repositoryVariableName,
            String findByIdOrThrowMethodName,
            String buildKeyMethodName,
            String buildCompositeIdMethodName,
            String primaryKeyType,
            List<Column> primaryKeyColumns,
            boolean compositePrimaryKey
    ) {
        String methodParameters = compositePrimaryKey
                ? buildCompositePrimaryKeyMethodParameters(primaryKeyColumns)
                : primaryKeyType + " id";

        String idArguments = compositePrimaryKey
                ? buildCompositePrimaryKeyMethodArguments(primaryKeyColumns)
                : "id";

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Delete a ").append(lowerDisplayLabel).append(" record by id.\n");

        if (compositePrimaryKey) {
            for (Column primaryKeyColumn : primaryKeyColumns) {
                String columnName = GeneratorSupport.unquoteIdentifier(primaryKeyColumn.getName());
                if (columnName == null || columnName.isBlank()) {
                    columnName = "id";
                }

                String parameterName = NamingConverter.toCamelCase(columnName);
                stringBuilder.append("     * @param ").append(parameterName)
                        .append(" the ").append(columnName).append(" value\n");
            }
        } else {
            stringBuilder.append("     * @param id the ").append(lowerDisplayLabel).append(" id\n");
        }

        stringBuilder.append("     */\n");
        stringBuilder.append("    @Override\n");
        stringBuilder.append("    public void delete").append(entityName)
                .append("(").append(methodParameters).append(") {\n");

        if (compositePrimaryKey) {
            stringBuilder.append("        String compositeId = ")
                    .append(buildCompositeIdMethodName)
                    .append("(")
                    .append(idArguments)
                    .append(");\n");
            stringBuilder.append("        log.info(\"Deleting ").append(lowerDisplayLabel)
                    .append(" with composite id: {}\", compositeId);\n\n");
            stringBuilder.append("        ").append(findByIdOrThrowMethodName).append("(")
                    .append(idArguments).append(");\n");
            stringBuilder.append("        ").append(repositoryVariableName).append(".deleteById(")
                    .append(buildKeyMethodName).append("(").append(idArguments).append("));\n");
        } else {
            stringBuilder.append("        log.info(\"Deleting ").append(lowerDisplayLabel)
                    .append(" with id: {}\", id);\n\n");
            stringBuilder.append("        ").append(findByIdOrThrowMethodName).append("(id);\n");
            stringBuilder.append("        ").append(repositoryVariableName).append(".deleteById(id);\n");
        }

        stringBuilder.append("    }\n\n");
    }


    /**
     * Appends the helper find-by-id-or-throw method.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param lowerDisplayLabel lowercase display label
     * @param repositoryVariableName repository variable name
     * @param findByIdOrThrowMethodName helper method name
     * @param createNotFoundExceptionMethodName exception factory method name
     * @param buildKeyMethodName key builder method name
     * @param primaryKeyType primary key simple type or composite key type
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey true when the entity uses a composite primary key
     */
    private void appendFindByIdOrThrowServiceMethod(
            StringBuilder stringBuilder,
            String entityName,
            String lowerDisplayLabel,
            String repositoryVariableName,
            String findByIdOrThrowMethodName,
            String createNotFoundExceptionMethodName,
            String buildKeyMethodName,
            String primaryKeyType,
            List<Column> primaryKeyColumns,
            boolean compositePrimaryKey
    ) {
        String methodParameters = compositePrimaryKey
                ? buildCompositePrimaryKeyMethodParameters(primaryKeyColumns)
                : primaryKeyType + " id";

        String methodArguments = compositePrimaryKey
                ? buildCompositePrimaryKeyMethodArguments(primaryKeyColumns)
                : "id";

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Finds an existing ").append(lowerDisplayLabel)
                .append(" record by id or throws an exception.\n");

        if (compositePrimaryKey) {
            appendCompositePrimaryKeyJavaDocParameters(stringBuilder, primaryKeyColumns);
        } else {
            stringBuilder.append("     * @param id the ").append(lowerDisplayLabel).append(" id\n");
        }

        stringBuilder.append("     * @return existing ").append(entityName).append(" entity\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    private ").append(entityName).append(" ").append(findByIdOrThrowMethodName)
                .append("(").append(methodParameters).append(") {\n");

        if (compositePrimaryKey) {
            stringBuilder.append("        return ").append(repositoryVariableName)
                    .append(".findById(")
                    .append(buildKeyMethodName)
                    .append("(")
                    .append(methodArguments)
                    .append("))\n");
        } else {
            stringBuilder.append("        return ").append(repositoryVariableName).append(".findById(id)\n");
        }

        stringBuilder.append("                .orElseThrow(() -> ")
                .append(createNotFoundExceptionMethodName).append("(").append(methodArguments).append("));\n");
        stringBuilder.append("    }\n\n");
    }

    /**
     * Resolves extra import lines required by composite primary key parameters.
     *
     * @param primaryKeyColumns primary key columns
     * @return ordered import lines
     */
    private java.util.LinkedHashSet<String> resolveCompositePrimaryKeyImportLines(
            List<com.sqldomaingen.model.Column> primaryKeyColumns
    ) {
        java.util.LinkedHashSet<String> importLines = new java.util.LinkedHashSet<>();

        for (com.sqldomaingen.model.Column primaryKeyColumn : primaryKeyColumns) {
            if (primaryKeyColumn == null) {
                continue;
            }

            String importLine = JavaTypeSupport.resolveImportLine(primaryKeyColumn.getJavaType());
            if (importLine != null && !importLine.isBlank()) {
                importLines.add(importLine);
            }
        }

        return importLines;
    }

    /**
     * Builds the method parameter list for composite primary key methods.
     *
     * @param primaryKeyColumns primary key columns
     * @return comma-separated method parameter list
     */
    private String buildCompositePrimaryKeyMethodParameters(
            List<com.sqldomaingen.model.Column> primaryKeyColumns
    ) {
        StringBuilder stringBuilder = new StringBuilder();

        for (int index = 0; index < primaryKeyColumns.size(); index++) {
            com.sqldomaingen.model.Column primaryKeyColumn = primaryKeyColumns.get(index);

            String parameterName = resolvePrimaryKeyParameterName(primaryKeyColumn);
            String parameterType = resolvePrimaryKeyParameterType(primaryKeyColumn);

            if (index > 0) {
                stringBuilder.append(", ");
            }

            stringBuilder.append(parameterType).append(" ").append(parameterName);
        }

        return stringBuilder.toString();
    }

    /**
     * Builds the method parameter list for composite primary key methods that also receive a DTO.
     *
     * @param primaryKeyColumns primary key columns
     * @param dtoName dto simple name
     * @return comma-separated method parameter list
     */
    private String buildCompositePrimaryKeyMethodParametersWithDto(
            List<com.sqldomaingen.model.Column> primaryKeyColumns,
            String dtoName
    ) {
        StringBuilder stringBuilder = new StringBuilder(
                buildCompositePrimaryKeyMethodParameters(primaryKeyColumns)
        );

        if (stringBuilder.length() > 0) {
            stringBuilder.append(", ");
        }

        stringBuilder.append(dtoName).append(" dto");
        return stringBuilder.toString();
    }

    /**
     * Builds the method argument list for composite primary key method calls.
     *
     * @param primaryKeyColumns primary key columns
     * @return comma-separated method argument list
     */
    private String buildCompositePrimaryKeyMethodArguments(
            List<com.sqldomaingen.model.Column> primaryKeyColumns
    ) {
        StringBuilder stringBuilder = new StringBuilder();

        for (int index = 0; index < primaryKeyColumns.size(); index++) {
            if (index > 0) {
                stringBuilder.append(", ");
            }

            stringBuilder.append(resolvePrimaryKeyParameterName(primaryKeyColumns.get(index)));
        }

        return stringBuilder.toString();
    }

    /**
     * Appends Javadoc parameter lines for composite primary key values.
     *
     * @param stringBuilder target source builder
     * @param primaryKeyColumns primary key columns
     */
    private void appendCompositePrimaryKeyJavaDocParameters(
            StringBuilder stringBuilder,
            List<Column> primaryKeyColumns
    ) {
        for (Column primaryKeyColumn : primaryKeyColumns) {
            String parameterName = resolvePrimaryKeyParameterName(primaryKeyColumn);

            stringBuilder.append("     * @param ").append(parameterName)
                    .append(" the ").append(parameterName).append(" value\n");
        }
    }

    /**
     * Appends setter assignments from composite PK parameters to the composite key object.
     *
     * @param stringBuilder target source builder
     * @param primaryKeyColumns primary key columns
     */
    private void appendCompositePrimaryKeyAssignments(
            StringBuilder stringBuilder,
            List<com.sqldomaingen.model.Column> primaryKeyColumns
    ) {
        for (com.sqldomaingen.model.Column primaryKeyColumn : primaryKeyColumns) {
            String parameterName = resolvePrimaryKeyParameterName(primaryKeyColumn);
            String setterSuffix = NamingConverter.toPascalCase(parameterName);

            stringBuilder.append("        key")
                    .append(".set").append(setterSuffix)
                    .append("(").append(parameterName).append(");\n");
        }
    }



    /**
     * Builds a composite id description expression using method parameters.
     *
     * @param primaryKeyColumns primary key columns
     * @return Java string expression
     */
    private String buildCompositePrimaryKeyParameterDescriptionExpression(
            List<Column> primaryKeyColumns
    ) {
        StringBuilder stringBuilder = new StringBuilder();

        for (int index = 0; index < primaryKeyColumns.size(); index++) {
            Column primaryKeyColumn = primaryKeyColumns.get(index);

            String parameterName = resolvePrimaryKeyParameterName(primaryKeyColumn);

            if (index > 0) {
                stringBuilder.append(" + \", \" + ");
            }

            stringBuilder.append("\"")
                    .append(parameterName)
                    .append("=\" + ")
                    .append(parameterName);
        }

        return stringBuilder.toString();
    }


    /**
     * Resolves the normalized primary key column name.
     *
     * @param primaryKeyColumn primary key column metadata
     * @return normalized column name
     */
    private String resolvePrimaryKeyColumnName(com.sqldomaingen.model.Column primaryKeyColumn) {
        String columnName = primaryKeyColumn == null
                ? null
                : GeneratorSupport.unquoteIdentifier(primaryKeyColumn.getName());

        if (columnName == null || columnName.isBlank()) {
            return "id";
        }

        return columnName;
    }

    /**
     * Resolves the method parameter name for a primary key column.
     *
     * @param primaryKeyColumn primary key column metadata
     * @return camelCase parameter name
     */
    private String resolvePrimaryKeyParameterName(com.sqldomaingen.model.Column primaryKeyColumn) {
        return NamingConverter.toCamelCase(resolvePrimaryKeyColumnName(primaryKeyColumn));
    }

    /**
     * Resolves the Java method parameter type for a primary key column.
     *
     * @param primaryKeyColumn primary key column metadata
     * @return simple Java type name
     */
    private String resolvePrimaryKeyParameterType(com.sqldomaingen.model.Column primaryKeyColumn) {
        if (primaryKeyColumn == null) {
            return "Long";
        }

        String rawJavaType = primaryKeyColumn.getJavaType();
        if (rawJavaType == null || rawJavaType.isBlank()) {
            return "Long";
        }

        return JavaTypeSupport.resolveSimpleType(rawJavaType);
    }

    /**
     * Appends the closing brace for the generated ServiceImpl.
     *
     * @param stringBuilder target source builder
     */
    private void appendServiceImplClassFooter(StringBuilder stringBuilder) {
        stringBuilder.append("}\n");
    }



    /**
     * Builds a display label for logs and messages.
     *
     * @param entityName entity simple name
     * @param lowerDisplayLabel lower-case label
     * @return formatted display label
     */
    private String buildDisplayLabel(String entityName, String lowerDisplayLabel) {
        if (lowerDisplayLabel == null || lowerDisplayLabel.isBlank()) {
            return entityName;
        }

        return NamingConverter.buildTitleCaseLabel(lowerDisplayLabel);
    }

    /**
     * Appends unique-constraint validation statements for the create helper method.
     *
     * <p>
     * Inline unique validation is always generated when eligible.
     * Composite unique validation is also generated, except when the unique
     * constraint matches the composite primary key exactly.
     * </p>
     *
     * @param stringBuilder target source builder
     * @param table table metadata
     * @param entityName entity simple name
     * @param repositoryVariableName repository variable name
     * @param compositePrimaryKey true when the entity uses a composite primary key
     */
    private void appendCreateUniqueValidation(
            StringBuilder stringBuilder,
            Table table,
            String entityName,
            String repositoryVariableName,
            boolean compositePrimaryKey
    ) {
        appendInlineUniqueCreateValidation(
                stringBuilder,
                table,
                entityName,
                repositoryVariableName,
                compositePrimaryKey
        );

        if (table.getUniqueConstraints() == null || table.getUniqueConstraints().isEmpty()) {
            return;
        }

        List<String> primaryKeyColumnNames = table.getColumns().stream()
                .filter(Objects::nonNull)
                .filter(Column::isPrimaryKey)
                .map(Column::getName)
                .filter(Objects::nonNull)
                .map(GeneratorSupport::unquoteIdentifier)
                .map(String::toLowerCase)
                .sorted()
                .toList();

        List<UniqueConstraint> eligibleCompositeUniqueConstraints = getEligibleCompositeUniqueConstraints(table).stream()
                .filter(uniqueConstraint -> {
                    List<String> uniqueColumnNames = uniqueConstraint.getColumns().stream()
                            .filter(Objects::nonNull)
                            .map(GeneratorSupport::unquoteIdentifier)
                            .map(String::toLowerCase)
                            .sorted()
                            .toList();

                    return !compositePrimaryKey || !uniqueColumnNames.equals(primaryKeyColumnNames);
                })
                .toList();

        for (UniqueConstraint uniqueConstraint : eligibleCompositeUniqueConstraints) {
            appendSingleCompositeUniqueCreateValidation(
                    stringBuilder,
                    table,
                    entityName,
                    repositoryVariableName,
                    uniqueConstraint,
                    compositePrimaryKey
            );
        }
    }

    /**
     * Appends create-time validation for inline unique columns.
     *
     * @param stringBuilder target source builder
     * @param table table metadata
     * @param entityName entity simple name
     * @param repositoryVariableName repository variable name
     * @param compositePrimaryKey true when the entity uses a composite primary key
     */
    private void appendInlineUniqueCreateValidation(
            StringBuilder stringBuilder,
            Table table,
            String entityName,
            String repositoryVariableName,
            boolean compositePrimaryKey
    ) {
        for (Column column : getEligibleInlineUniqueColumns(table)) {
            String propertyName = NamingConverter.toCamelCase(
                    GeneratorSupport.unquoteIdentifier(column.getName())
            );
            String repositoryMethodName = buildExistsByMethodName(table, List.of(column.getName()));
            String dtoAccessExpression = buildDtoAccessExpressionForColumn(table, column, compositePrimaryKey);
            String dtoPresenceCheckExpression = buildDtoPresenceCheckExpressionForColumn(
                    table,
                    column,
                    compositePrimaryKey
            );

            stringBuilder.append("        if (")
                    .append(dtoPresenceCheckExpression)
                    .append(" && ")
                    .append(repositoryVariableName)
                    .append(".")
                    .append(repositoryMethodName)
                    .append("(")
                    .append(dtoAccessExpression)
                    .append(")) {\n");
            stringBuilder.append("            throw GeneratedRuntimeException.builder()\n");
            stringBuilder.append("                    .code(ErrorCodes.BAD_REQUEST)\n");
            stringBuilder.append("                    .message(\"").append(entityName)
                    .append(" already exists with ").append(propertyName).append(": \" + ")
                    .append(dtoAccessExpression).append(")\n");
            stringBuilder.append("                    .build();\n");
            stringBuilder.append("        }\n");
        }
    }


    /**
     * Appends create-time validation for one composite unique constraint.
     *
     * @param stringBuilder target source builder
     * @param table table metadata
     * @param entityName entity simple name
     * @param repositoryVariableName repository variable name
     * @param uniqueConstraint composite unique constraint metadata
     * @param compositePrimaryKey true when the entity uses a composite primary key
     */
    private void appendSingleCompositeUniqueCreateValidation(
            StringBuilder stringBuilder,
            Table table,
            String entityName,
            String repositoryVariableName,
            UniqueConstraint uniqueConstraint,
            boolean compositePrimaryKey
    ) {
        List<Column> uniqueColumns = uniqueConstraint.getColumns().stream()
                .map(columnName -> findColumnByName(table, columnName))
                .filter(Objects::nonNull)
                .toList();

        if (uniqueColumns.size() != uniqueConstraint.getColumns().size()) {
            return;
        }

        String repositoryMethodName = buildExistsByMethodName(table, uniqueConstraint.getColumns());

        String repositoryArguments = uniqueColumns.stream()
                .map(column -> buildDtoAccessExpressionForColumn(table, column, compositePrimaryKey))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");

        if (repositoryArguments.isBlank()) {
            return;
        }

        if (compositePrimaryKey) {
            stringBuilder.append("        if (dto == null || dto.getId() == null) {\n");
            stringBuilder.append("            return;\n");
            stringBuilder.append("        }\n\n");
            stringBuilder.append("        ")
                    .append(entityName)
                    .append("Key id = dto.getId();\n\n");
        }

        String requiredFieldsCheckExpression = uniqueColumns.stream()
                .map(column -> buildDtoAccessExpressionForColumn(table, column, compositePrimaryKey)
                        .replace("dto.getId()", "id") + " == null")
                .reduce((left, right) -> left + " || " + right)
                .orElse("");

        if (!requiredFieldsCheckExpression.isBlank()) {
            stringBuilder.append("        if (")
                    .append(requiredFieldsCheckExpression)
                    .append(") {\n");
            stringBuilder.append("            return;\n");
            stringBuilder.append("        }\n\n");
        }

        stringBuilder.append("        boolean exists = ")
                .append(repositoryVariableName)
                .append("\n");
        stringBuilder.append("                .")
                .append(repositoryMethodName)
                .append("(\n");
        stringBuilder.append("                        ")
                .append(repositoryArguments.replace("dto.getId()", "id").replace(", ", ",\n                        "))
                .append("\n");
        stringBuilder.append("                );\n\n");

        stringBuilder.append("        if (!exists) {\n");
        stringBuilder.append("            return;\n");
        stringBuilder.append("        }\n\n");

        stringBuilder.append("        throw GeneratedRuntimeException.builder()\n");
        stringBuilder.append("                .code(ErrorCodes.BAD_REQUEST)\n");
        stringBuilder.append("                .message(\"")
                .append(entityName)
                .append(" already exists with \"");

        for (int index = 0; index < uniqueColumns.size(); index++) {
            Column column = uniqueColumns.get(index);

            String propertyName = NamingConverter.toCamelCase(
                    GeneratorSupport.unquoteIdentifier(column.getName())
            );

            String accessExpression = buildDtoAccessExpressionForColumn(
                    table,
                    column,
                    compositePrimaryKey
            ).replace("dto.getId()", "id");

            stringBuilder.append("\n                        + \"")
                    .append(propertyName)
                    .append("=\" + ")
                    .append(accessExpression);

            if (index < uniqueColumns.size() - 1) {
                stringBuilder.append(" + \", \"");
            }
        }

        stringBuilder.append(")\n");
        stringBuilder.append("                .build();\n");
    }

    /**
     * Builds the DTO presence-check expression for a column.
     *
     * @param table table metadata
     * @param column table column
     * @param compositePrimaryKey true when the entity uses a composite primary key
     * @return DTO presence-check expression
     */
    private String buildDtoPresenceCheckExpressionForColumn(
            Table table,
            Column column,
            boolean compositePrimaryKey
    ) {
        String columnName = column == null
                ? ""
                : GeneratorSupport.unquoteIdentifier(column.getName());

        if (columnName == null || columnName.isBlank()) {
            return "false";
        }

        String getterSuffix = NamingConverter.toPascalCase(
                NamingConverter.toCamelCase(columnName)
        );

        if (compositePrimaryKey && isPrimaryKeyColumn(table, columnName)) {
            return "dto.getId().get" + getterSuffix + "() != null";
        }

        boolean isRelationship = table.getRelationships() != null &&
                table.getRelationships().stream().anyMatch(rel ->
                        columnName.equalsIgnoreCase(GeneratorSupport.unquoteIdentifier(rel.getSourceColumn()))
                                && (rel.getRelationshipType() == Relationship.RelationshipType.MANYTOONE
                                || rel.getRelationshipType() == Relationship.RelationshipType.ONETOONE)
                );

        if (isRelationship) {
            String relationName = NamingConverter.toCamelCase(
                    columnName.replaceAll("_id$", "")
            );
            String relationGetterSuffix = NamingConverter.toPascalCase(relationName);

            return "dto.get" + relationGetterSuffix + "() != null && dto.get"
                    + relationGetterSuffix + "().getId() != null";
        }

        return "dto.get" + getterSuffix + "() != null";
    }

    /**
     * Builds the DTO access expression for a column, supporting composite primary keys.
     *
     * @param table table metadata
     * @param column table column
     * @param compositePrimaryKey true when the entity uses a composite primary key
     * @return DTO access expression
     */
    private String buildDtoAccessExpressionForColumn(
            Table table,
            Column column,
            boolean compositePrimaryKey
    ) {
        String columnName = column == null
                ? ""
                : GeneratorSupport.unquoteIdentifier(column.getName());

        if (columnName == null || columnName.isBlank()) {
            return "null";
        }

        String getterSuffix = NamingConverter.toPascalCase(
                NamingConverter.toCamelCase(columnName)
        );

        if (compositePrimaryKey && isPrimaryKeyColumn(table, columnName)) {
            return "dto.getId().get" + getterSuffix + "()";
        }

        boolean isRelationship = table.getRelationships() != null &&
                table.getRelationships().stream().anyMatch(rel ->
                        columnName.equalsIgnoreCase(GeneratorSupport.unquoteIdentifier(rel.getSourceColumn()))
                                && (rel.getRelationshipType() == Relationship.RelationshipType.MANYTOONE
                                || rel.getRelationshipType() == Relationship.RelationshipType.ONETOONE)
                );

        if (isRelationship) {
            String relationName = NamingConverter.toCamelCase(
                    columnName.replaceAll("_id$", "")
            );
            String relationGetterSuffix = NamingConverter.toPascalCase(relationName);

            return "(dto.get" + relationGetterSuffix + "() != null ? dto.get"
                    + relationGetterSuffix + "().getId() : null)";
        }

        return "dto.get" + getterSuffix + "()";
    }


    /**
     * Returns all eligible inline unique columns.
     *
     * @param table table metadata
     * @return eligible inline unique columns
     */
    private List<Column> getEligibleInlineUniqueColumns(Table table) {
        return table.getColumns().stream()
                .filter(column -> !isNotEligibleInlineUniqueColumn(column))
                .toList();
    }

    /**
     * Returns all eligible composite unique constraints.
     *
     * @param table table metadata
     * @return eligible composite unique constraints
     */
    private List<UniqueConstraint> getEligibleCompositeUniqueConstraints(Table table) {
        if (table.getUniqueConstraints() == null || table.getUniqueConstraints().isEmpty()) {
            return List.of();
        }

        return table.getUniqueConstraints().stream()
                .filter(uniqueConstraint -> !isNotEligibleCompositeUniqueConstraint(table, uniqueConstraint))
                .toList();
    }

    /**
     * Returns whether the given column is NOT eligible for inline unique validation.
     *
     * @param column table column
     * @return true when the column is NOT eligible
     */
    private boolean isNotEligibleInlineUniqueColumn(Column column) {
        return column == null
                || !column.isUnique()
                || isUnsupportedForDerivedQuery(column.getJavaType());
    }

    /**
     * Returns whether the given composite unique constraint is NOT eligible
     * for create time unique validation.
     *
     * @param table table metadata
     * @param uniqueConstraint unique constraint metadata
     * @return true when the constraint is NOT eligible
     */
    private boolean isNotEligibleCompositeUniqueConstraint(
            Table table,
            UniqueConstraint uniqueConstraint
    ) {
        return !isValidCompositeUniqueConstraint(uniqueConstraint)
                || containsUnsupportedDerivedQueryType(table, uniqueConstraint);
    }

    /**
     * Returns true when the given composite unique constraint can produce
     * a repository existsBy method.
     *
     * @param uniqueConstraint unique constraint metadata
     * @return true when the constraint is valid
     */
    private boolean isValidCompositeUniqueConstraint(UniqueConstraint uniqueConstraint) {
        return uniqueConstraint != null
                && uniqueConstraint.getColumns() != null
                && uniqueConstraint.getColumns().size() >= 2;
    }

    /**
     * Returns true when at least one participating column uses
     * an unsupported derived-query type.
     *
     * @param table table metadata
     * @param uniqueConstraint composite unique constraint metadata
     * @return true when validation generation should be skipped
     */
    private boolean containsUnsupportedDerivedQueryType(
            Table table,
            UniqueConstraint uniqueConstraint
    ) {
        for (String columnName : uniqueConstraint.getColumns()) {
            Column column = findColumnByName(table, columnName);
            if (column == null || isUnsupportedForDerivedQuery(column.getJavaType())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Finds a column in the table by its name.
     *
     * @param table table metadata
     * @param columnName target column name
     * @return matching column or null when not found
     */
    private Column findColumnByName(Table table, String columnName) {
        if (columnName == null || columnName.isBlank()) {
            return null;
        }

        return table.getColumns().stream()
                .filter(Objects::nonNull)
                .filter(column -> columnName.equalsIgnoreCase(column.getName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Determines whether a Java type is unsupported for Spring Data derived queries.
     *
     * @param javaType full or simple Java type
     * @return true when method generation should be skipped
     */
    private boolean isUnsupportedForDerivedQuery(String javaType) {
        if (javaType == null || javaType.isBlank()) {
            return true;
        }

        String normalizedType = javaType.trim();

        return normalizedType.equals("byte[]")
                || normalizedType.contains("List")
                || normalizedType.contains("Map")
                || normalizedType.contains("Set");
    }


    /**
     * Resolves the repository property path for a column name.
     *
     * @param table table metadata
     * @param columnName database column name
     * @return repository property path
     */
    private String resolveRepositoryPropertyPath(Table table, String columnName) {
        if (PrimaryKeySupport.hasCompositePrimaryKey(table) && isPrimaryKeyColumn(table, columnName)) {
            return "Id" + NamingConverter.toPascalCase(
                    GeneratorSupport.unquoteIdentifier(columnName)
            );
        }

        return NamingConverter.toPascalCase(
                GeneratorSupport.unquoteIdentifier(columnName)
        );
    }

    /**
     * Returns whether the given column is part of the table primary key.
     *
     * @param table table metadata
     * @param columnName database column name
     * @return true when the column belongs to the primary key
     */
    private boolean isPrimaryKeyColumn(Table table, String columnName) {
        if (table == null || columnName == null || columnName.isBlank()) {
            return false;
        }

        return table.getColumns().stream()
                .filter(Objects::nonNull)
                .filter(Column::isPrimaryKey)
                .map(Column::getName)
                .filter(Objects::nonNull)
                .anyMatch(primaryKeyColumnName -> primaryKeyColumnName.equalsIgnoreCase(columnName));
    }

    /**
     * Builds an existsBy repository method name from one or more column names.
     *
     * @param table table metadata
     * @param columnNames participating column names
     * @return generated repository method name
     */
    private String buildExistsByMethodName(Table table, List<String> columnNames) {
        StringBuilder methodName = new StringBuilder("existsBy");
        boolean first = true;

        for (String columnName : columnNames) {
            if (columnName == null || columnName.isBlank()) {
                continue;
            }

            if (!first) {
                methodName.append("And");
            }

            methodName.append(resolveRepositoryPropertyPath(table, columnName));
            first = false;
        }

        return methodName.toString();
    }

    /**
     * Appends a duplicate validation helper for composite primary keys.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param repositoryVariableName repository variable name
     * @param validateDoesNotExistMethodName validation helper method name
     * @param buildKeyMethodName key builder method name
     * @param buildCompositeIdMethodName composite id helper method name
     * @param primaryKeyType composite key type
     * @param primaryKeyColumns primary key columns
     */
    private void appendValidateDoesNotExistServiceMethod(
            StringBuilder stringBuilder,
            String entityName,
            String dtoName,
            String repositoryVariableName,
            String validateDoesNotExistMethodName,
            String buildKeyMethodName,
            String buildCompositeIdMethodName,
            String primaryKeyType,
            List<Column> primaryKeyColumns,
            Table table
    ) {
        String nullCheckExpression = primaryKeyColumns.stream()
                .map(column -> buildDtoAccessExpressionForColumn(table, column, true) + " == null")
                .reduce((left, right) -> left + " || " + right)
                .orElse("dto == null || dto.getId() == null");

        String methodArguments = primaryKeyColumns.stream()
                .map(column -> buildDtoAccessExpressionForColumn(table, column, true))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Validates that a ")
                .append(NamingConverter.toLogLabel(entityName))
                .append(" record does not already exist.\n");
        stringBuilder.append("     * @param dto input payload\n");
        stringBuilder.append("     * @throws GeneratedRuntimeException if the entity already exists\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    private void ")
                .append(validateDoesNotExistMethodName)
                .append("(")
                .append(dtoName)
                .append(" dto) {\n");
        stringBuilder.append("        if (dto == null || ")
                .append(nullCheckExpression)
                .append(") {\n");
        stringBuilder.append("            return;\n");
        stringBuilder.append("        }\n\n");
        stringBuilder.append("        ")
                .append(primaryKeyType)
                .append(" key = ")
                .append(buildKeyMethodName)
                .append("(")
                .append(methodArguments)
                .append(");\n\n");
        stringBuilder.append("        if (")
                .append(repositoryVariableName)
                .append(".existsById(key)) {\n");
        stringBuilder.append("            String compositeId = ")
                .append(buildCompositeIdMethodName)
                .append("(")
                .append(methodArguments)
                .append(");\n");
        stringBuilder.append("            log.warn(\"")
                .append(entityName)
                .append(" already exists with composite id: {}\", compositeId);\n\n");
        stringBuilder.append("            throw GeneratedRuntimeException.builder()\n");
        stringBuilder.append("                    .code(ErrorCodes.BAD_REQUEST)\n");
        stringBuilder.append("                    .message(\"")
                .append(entityName)
                .append(" already exists with composite id: \" + compositeId)\n");
        stringBuilder.append("                    .build();\n");
        stringBuilder.append("        }\n");
        stringBuilder.append("    }\n\n");
    }

    /**
     * Appends the composite key builder helper method.
     *
     * @param stringBuilder target source builder
     * @param primaryKeyType composite key type
     * @param primaryKeyColumns primary key columns
     * @param buildKeyMethodName key builder method name
     */
    private void appendBuildKeyServiceMethod(
            StringBuilder stringBuilder,
            String primaryKeyType,
            List<Column> primaryKeyColumns,
            String buildKeyMethodName
    ) {
        String methodParameters = buildCompositePrimaryKeyMethodParameters(primaryKeyColumns);

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Builds the composite key.\n");
        stringBuilder.append("     *\n");
        appendCompositePrimaryKeyJavaDocParameters(stringBuilder, primaryKeyColumns);
        stringBuilder.append("     * @return populated {@link ").append(primaryKeyType).append("}\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    private ").append(primaryKeyType).append(" ")
                .append(buildKeyMethodName).append("(").append(methodParameters).append(") {\n");
        stringBuilder.append("        ").append(primaryKeyType).append(" key = new ")
                .append(primaryKeyType).append("();\n");
        appendCompositePrimaryKeyAssignments(stringBuilder, primaryKeyColumns);
        stringBuilder.append("        return key;\n");
        stringBuilder.append("    }\n\n");
    }

    /**
     * Appends the composite id builder helper method.
     *
     * @param stringBuilder target source builder
     * @param primaryKeyColumns primary key columns
     * @param buildCompositeIdMethodName composite id helper method name
     */
    private void appendBuildCompositeIdServiceMethod(
            StringBuilder stringBuilder,
            List<Column> primaryKeyColumns,
            String buildCompositeIdMethodName
    ) {
        String methodParameters = buildCompositePrimaryKeyMethodParameters(primaryKeyColumns);

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Builds the composite id string.\n");
        stringBuilder.append("     *\n");
        appendCompositePrimaryKeyJavaDocParameters(stringBuilder, primaryKeyColumns);
        stringBuilder.append("     * @return composite id string\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    private String ").append(buildCompositeIdMethodName)
                .append("(").append(methodParameters).append(") {\n");
        stringBuilder.append("        return ").append(
                buildCompositePrimaryKeyParameterDescriptionExpression(primaryKeyColumns)
        ).append(";\n");
        stringBuilder.append("    }\n\n");
    }

}