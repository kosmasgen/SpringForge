package com.sqldomaingen.generator;

import com.sqldomaingen.util.Constants;
import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.util.JavaTypeSupport;
import com.sqldomaingen.util.NamingConverter;
import com.sqldomaingen.util.PackageResolver;
import com.sqldomaingen.util.PrimaryKeySupport;
import com.sqldomaingen.model.Table;
import lombok.extern.log4j.Log4j2;
import java.nio.file.Path;
import java.util.LinkedHashSet;
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

    private final ServiceImplGenerator serviceImplGenerator = new ServiceImplGenerator();

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

            String serviceImplCode = serviceImplGenerator.generateServiceImpl(table, basePackage);
            GeneratorSupport.writeFile(serviceImplDir.resolve(entityName + "ServiceImpl.java"), serviceImplCode);
        }

        log.debug("Services generated under: {}", serviceDir.getParent().toAbsolutePath());
    }

    /**
     * Generates the Service interface for one entity.
     *
     * @param table source table metadata
     * @param basePackage base package
     * @return Java source code
     */
    public String generateServiceInterface(Table table, String basePackage) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        String entityName = buildEntityName(table);
        String dtoName = entityName + Constants.DTO_SUFFIX;
        String servicePackage = PackageResolver.resolvePackageName(basePackage, "service");
        String dtoPackage = PackageResolver.resolvePackageName(basePackage, "dto");
        String modelPackage = PackageResolver.resolvePackageName(basePackage, "entity");

        List<com.sqldomaingen.model.Column> primaryKeyColumns = getPrimaryKeyColumns(table);
        boolean compositePrimaryKey = primaryKeyColumns.size() > 1;

        PrimaryKeySupport.TypeRef primaryKeyType = compositePrimaryKey
                ? null
                : PrimaryKeySupport.resolvePrimaryKeyTypeRef(table, entityName, modelPackage);

        ServiceMethodParameters serviceMethodParameters = buildServiceMethodParameters(
                primaryKeyColumns,
                primaryKeyType,
                dtoName,
                compositePrimaryKey
        );

        java.util.LinkedHashSet<String> importLines = buildServiceInterfaceImports(
                dtoPackage,
                dtoName,
                primaryKeyColumns,
                primaryKeyType,
                compositePrimaryKey
        );

        StringBuilder stringBuilder = new StringBuilder();

        appendServiceInterfacePackageAndImports(stringBuilder, servicePackage, importLines);
        appendServiceInterfaceHeader(stringBuilder, entityName);
        appendGetAllMethodSignature(stringBuilder, entityName, dtoName);
        appendGetByIdMethodSignature(
                stringBuilder,
                entityName,
                dtoName,
                primaryKeyColumns,
                compositePrimaryKey,
                serviceMethodParameters.idMethodParameters()
        );
        appendCreateMethodSignature(stringBuilder, entityName, dtoName);
        appendUpdateMethodSignature(
                stringBuilder,
                entityName,
                dtoName,
                primaryKeyColumns,
                compositePrimaryKey,
                serviceMethodParameters.updateMethodParameters()
        );
        appendDeleteMethodSignature(
                stringBuilder,
                entityName,
                primaryKeyColumns,
                compositePrimaryKey,
                serviceMethodParameters.idMethodParameters()
        );
        appendServiceInterfaceFooter(stringBuilder);

        return stringBuilder.toString();
    }

    /**
     * Holds generated service method parameter declarations.
     *
     * @param idMethodParameters parameters used by get/delete methods
     * @param updateMethodParameters parameters used by update method
     */
    private record ServiceMethodParameters(
            String idMethodParameters,
            String updateMethodParameters
    ) {
    }

    /**
     * Builds the generated entity name for a table.
     *
     * @param table source table
     * @return entity simple name
     */
    private String buildEntityName(Table table) {
        return NamingConverter.toPascalCase(
                GeneratorSupport.normalizeTableName(table.getName())
        );
    }

    /**
     * Returns all primary key columns from the table.
     *
     * @param table source table
     * @return primary key columns
     */
    private List<com.sqldomaingen.model.Column> getPrimaryKeyColumns(Table table) {
        return table.getColumns().stream()
                .filter(Objects::nonNull)
                .filter(com.sqldomaingen.model.Column::isPrimaryKey)
                .toList();
    }

    /**
     * Builds all imports required by a generated service interface.
     *
     * @param dtoPackage DTO package name
     * @param dtoName DTO simple name
     * @param primaryKeyColumns primary key columns
     * @param primaryKeyType primary key type reference
     * @param compositePrimaryKey true when the entity uses a composite primary key
     * @return ordered import lines
     */
    private LinkedHashSet<String> buildServiceInterfaceImports(
            String dtoPackage,
            String dtoName,
            List<com.sqldomaingen.model.Column> primaryKeyColumns,
            PrimaryKeySupport.TypeRef primaryKeyType,
            boolean compositePrimaryKey
    ) {
        LinkedHashSet<String> importLines = new LinkedHashSet<>();

        importLines.add("import " + dtoPackage + "." + dtoName + ";");
        importLines.add("import java.util.List;");

        if (!compositePrimaryKey && primaryKeyType.importLine() != null && !primaryKeyType.importLine().isBlank()) {
            importLines.add(primaryKeyType.importLine());
            return importLines;
        }

        if (compositePrimaryKey) {
            for (com.sqldomaingen.model.Column primaryKeyColumn : primaryKeyColumns) {
                String importLine = JavaTypeSupport.resolveImportLine(primaryKeyColumn.getJavaType());

                if (importLine != null && !importLine.isBlank()) {
                    importLines.add(importLine);
                }
            }
        }

        return importLines;
    }

    /**
     * Builds method parameter declarations for service interface methods.
     *
     * @param primaryKeyColumns primary key columns
     * @param primaryKeyType single primary key type reference
     * @param dtoName DTO simple name
     * @param compositePrimaryKey true when the entity uses a composite primary key
     * @return service method parameters
     */
    private ServiceMethodParameters buildServiceMethodParameters(
            List<com.sqldomaingen.model.Column> primaryKeyColumns,
            PrimaryKeySupport.TypeRef primaryKeyType,
            String dtoName,
            boolean compositePrimaryKey
    ) {
        if (!compositePrimaryKey) {
            String idMethodParameters = primaryKeyType.simpleName() + " id";
            String updateMethodParameters = primaryKeyType.simpleName() + " id, " + dtoName + " dto";
            return new ServiceMethodParameters(idMethodParameters, updateMethodParameters);
        }

        StringBuilder idMethodParameters = new StringBuilder();
        StringBuilder updateMethodParameters = new StringBuilder();

        for (int index = 0; index < primaryKeyColumns.size(); index++) {
            com.sqldomaingen.model.Column primaryKeyColumn = primaryKeyColumns.get(index);

            if (index > 0) {
                idMethodParameters.append(", ");
                updateMethodParameters.append(", ");
            }

            String parameterType = resolvePrimaryKeyParameterType(primaryKeyColumn);
            String parameterName = resolvePrimaryKeyParameterName(primaryKeyColumn);

            idMethodParameters.append(parameterType).append(" ").append(parameterName);
            updateMethodParameters.append(parameterType).append(" ").append(parameterName);
        }

        if (!updateMethodParameters.isEmpty()) {
            updateMethodParameters.append(", ");
        }

        updateMethodParameters.append(dtoName).append(" dto");

        return new ServiceMethodParameters(
                idMethodParameters.toString(),
                updateMethodParameters.toString()
        );
    }

    /**
     * Resolves the Java parameter type for a primary key column.
     *
     * @param primaryKeyColumn primary key column
     * @return simple Java parameter type
     */
    private String resolvePrimaryKeyParameterType(com.sqldomaingen.model.Column primaryKeyColumn) {
        String rawJavaType = primaryKeyColumn.getJavaType();

        if (rawJavaType == null || rawJavaType.isBlank()) {
            return "Long";
        }

        return JavaTypeSupport.resolveSimpleType(rawJavaType);
    }

    /**
     * Resolves the Java parameter name for a primary key column.
     *
     * @param primaryKeyColumn primary key column
     * @return camelCase parameter name
     */
    private String resolvePrimaryKeyParameterName(com.sqldomaingen.model.Column primaryKeyColumn) {
        String columnName = GeneratorSupport.unquoteIdentifier(primaryKeyColumn.getName());

        if (columnName == null || columnName.isBlank()) {
            columnName = "id";
        }

        return NamingConverter.toCamelCase(columnName);
    }

    /**
     * Appends package declaration and imports for the service interface.
     *
     * @param stringBuilder target source builder
     * @param servicePackage service package name
     * @param importLines ordered import lines
     */
    private void appendServiceInterfacePackageAndImports(
            StringBuilder stringBuilder,
            String servicePackage,
            java.util.LinkedHashSet<String> importLines
    ) {
        stringBuilder.append("package ").append(servicePackage).append(";\n\n");

        for (String importLine : importLines) {
            stringBuilder.append(importLine).append("\n");
        }

        stringBuilder.append("\n");
    }

    /**
     * Appends the service interface header.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     */
    private void appendServiceInterfaceHeader(StringBuilder stringBuilder, String entityName) {
        stringBuilder.append("/**\n");
        stringBuilder.append(" * Service contract for {@code ").append(entityName).append("} domain operations.\n");
        stringBuilder.append(" */\n");
        stringBuilder.append("public interface ").append(entityName).append("Service {\n\n");
    }

    /**
     * Appends get-all method signature.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param dtoName DTO simple name
     */
    private void appendGetAllMethodSignature(StringBuilder stringBuilder, String entityName, String dtoName) {
        String pluralMethodSuffix = NamingConverter.toPascalCase(
                NamingConverter.toCamelCasePlural(entityName)
        );
        String pluralLowerDisplayLabel = NamingConverter.toLogLabel(
                NamingConverter.toCamelCasePlural(entityName)
        );

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Retrieves all available ").append(pluralLowerDisplayLabel).append(".\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @return a non-null list of {@link ").append(dtoName).append("}\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    List<").append(dtoName).append("> getAll").append(pluralMethodSuffix).append("();\n\n");
    }

    /**
     * Appends get-by-id method signature.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param dtoName DTO simple name
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey true when the entity uses a composite primary key
     * @param idMethodParameters id method parameters
     */
    private void appendGetByIdMethodSignature(
            StringBuilder stringBuilder,
            String entityName,
            String dtoName,
            List<com.sqldomaingen.model.Column> primaryKeyColumns,
            boolean compositePrimaryKey,
            String idMethodParameters
    ) {
        String lowerDisplayLabel = NamingConverter.toLogLabel(entityName);

        stringBuilder.append("    /**\n");

        if (compositePrimaryKey) {
            stringBuilder.append("     * Retrieves ")
                    .append(NamingConverter.resolveIndefiniteArticle(lowerDisplayLabel))
                    .append(" ")
                    .append(lowerDisplayLabel)
                    .append(" by its composite identifier.\n");
        } else {
            stringBuilder.append("     * Retrieves ")
                    .append(NamingConverter.resolveIndefiniteArticle(lowerDisplayLabel))
                    .append(" ")
                    .append(lowerDisplayLabel)
                    .append(" by its identifier.\n");
        }

        stringBuilder.append("     *\n");
        appendPrimaryKeyJavaDocParameters(stringBuilder, primaryKeyColumns, compositePrimaryKey, lowerDisplayLabel, "retrieve");
        stringBuilder.append("     * @return the matching {@link ").append(dtoName).append("}\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    ").append(dtoName).append(" get").append(entityName).append("ById(")
                .append(idMethodParameters).append(");\n\n");
    }

    /**
     * Appends create method signature.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param dtoName DTO simple name
     */
    private void appendCreateMethodSignature(StringBuilder stringBuilder, String entityName, String dtoName) {
        String lowerDisplayLabel = NamingConverter.toLogLabel(entityName);

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Creates a new ").append(lowerDisplayLabel).append(".\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param dto the ").append(lowerDisplayLabel).append(" payload to create\n");
        stringBuilder.append("     * @return the created {@link ").append(dtoName).append("}\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    ").append(dtoName).append(" create").append(entityName).append("(")
                .append(dtoName).append(" dto);\n\n");
    }

    /**
     * Appends update method signature.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param dtoName DTO simple name
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey true when the entity uses a composite primary key
     * @param updateMethodParameters update method parameters
     */
    private void appendUpdateMethodSignature(
            StringBuilder stringBuilder,
            String entityName,
            String dtoName,
            List<com.sqldomaingen.model.Column> primaryKeyColumns,
            boolean compositePrimaryKey,
            String updateMethodParameters
    ) {
        String lowerDisplayLabel = NamingConverter.toLogLabel(entityName);

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Updates an existing ").append(lowerDisplayLabel).append(".\n");
        stringBuilder.append("     * <p>\n");
        stringBuilder.append("     * Only non-null fields from the DTO are applied to the existing entity.\n");
        stringBuilder.append("     *\n");
        appendPrimaryKeyJavaDocParameters(stringBuilder, primaryKeyColumns, compositePrimaryKey, lowerDisplayLabel, "update");
        stringBuilder.append("     * @param dto the partial ").append(lowerDisplayLabel).append(" payload\n");
        stringBuilder.append("     * @return the updated {@link ").append(dtoName).append("}\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    ").append(dtoName).append(" update").append(entityName).append("(")
                .append(updateMethodParameters).append(");\n\n");
    }

    /**
     * Appends delete method signature.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey true when the entity uses a composite primary key
     * @param idMethodParameters id method parameters
     */
    private void appendDeleteMethodSignature(
            StringBuilder stringBuilder,
            String entityName,
            List<com.sqldomaingen.model.Column> primaryKeyColumns,
            boolean compositePrimaryKey,
            String idMethodParameters
    ) {
        String lowerDisplayLabel = NamingConverter.toLogLabel(entityName);

        stringBuilder.append("    /**\n");

        if (compositePrimaryKey) {
            stringBuilder.append("     * Deletes ")
                    .append(NamingConverter.resolveIndefiniteArticle(lowerDisplayLabel))
                    .append(" ")
                    .append(lowerDisplayLabel)
                    .append(" by its composite identifier.\n");
        } else {
            stringBuilder.append("     * Deletes ")
                    .append(NamingConverter.resolveIndefiniteArticle(lowerDisplayLabel))
                    .append(" ")
                    .append(lowerDisplayLabel)
                    .append(" by its identifier.\n");
        }

        stringBuilder.append("     *\n");
        appendPrimaryKeyJavaDocParameters(stringBuilder, primaryKeyColumns, compositePrimaryKey, lowerDisplayLabel, "delete");
        stringBuilder.append("     */\n");
        stringBuilder.append("    void delete").append(entityName).append("(")
                .append(idMethodParameters).append(");\n");
    }

    /**
     * Appends primary key JavaDoc parameters.
     *
     * @param stringBuilder target source builder
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey true when the entity uses a composite primary key
     * @param lowerDisplayLabel lowercase display label
     * @param action target action description
     */
    private void appendPrimaryKeyJavaDocParameters(
            StringBuilder stringBuilder,
            List<com.sqldomaingen.model.Column> primaryKeyColumns,
            boolean compositePrimaryKey,
            String lowerDisplayLabel,
            String action
    ) {
        if (!compositePrimaryKey) {
            stringBuilder.append("     * @param id the identifier of the ")
                    .append(lowerDisplayLabel)
                    .append(" to ")
                    .append(action)
                    .append("\n");
            return;
        }

        for (com.sqldomaingen.model.Column primaryKeyColumn : primaryKeyColumns) {
            String parameterName = resolvePrimaryKeyParameterName(primaryKeyColumn);
            String readableParameterLabel = NamingConverter.toLogLabel(parameterName);

            stringBuilder.append("     * @param ")
                    .append(parameterName)
                    .append(" the ")
                    .append(readableParameterLabel)
                    .append(" of the composite key\n");
        }
    }

    /**
     * Appends the service interface footer.
     *
     * @param stringBuilder target source builder
     */
    private void appendServiceInterfaceFooter(StringBuilder stringBuilder) {
        stringBuilder.append("}\n");
    }
}