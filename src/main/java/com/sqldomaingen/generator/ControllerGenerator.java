package com.sqldomaingen.generator;

import com.sqldomaingen.config.GeneratorConfig;
import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.Table;
import com.sqldomaingen.util.*;
import com.sqldomaingen.util.Constants;
import lombok.extern.log4j.Log4j2;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Generates REST controller classes for parsed database tables.
 */
@Log4j2
public class ControllerGenerator {

    /**
     * Generates REST controllers for all tables.
     * Output directory:
     * {outputDir}/src/main/java/{basePackagePath}/controller
     * Package:
     * {basePackage}.controller
     *
     * @param tables source table metadata
     * @param outputDir target project root directory
     * @param basePackage base package for generated classes
     * @param overwrite overwrite existing files when true
     */
    public void generateControllers(List<Table> tables, String outputDir, String basePackage, boolean overwrite,
                                    GeneratorConfig generatorConfig) {
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");
        Objects.requireNonNull(generatorConfig, "generatorConfig must not be null");

        Path controllerDir = resolveControllerDirectory(outputDir, basePackage);
        String controllerPackage = resolveControllerPackage(basePackage);

        for (Table table : tables) {
            String entityName = resolveEntityName(table);
            String code = generateControllerCode(table, controllerPackage, basePackage);

            Path filePath = controllerDir.resolve(entityName + Constants.CONTROLLER_FILE_SUFFIX);
            GeneratorSupport.writeFile(filePath, code, overwrite);
        }

        GeneratorConfig.Security security = generatorConfig.getSecurity();

        if (security != null && security.isEnabled()) {
            generateRegistryController(controllerDir, controllerPackage, basePackage, overwrite);
        }

        log.info("Controllers generated under: {}", controllerDir.toAbsolutePath());
    }

    /**
     * Generates controller source code for a single table.
     *
     * @param table source table metadata
     * @param controllerPackage package of the generated controller
     * @param basePackage base package for generated classes
     * @return generated Java source code
     */
    public String generateControllerCode(Table table, String controllerPackage, String basePackage) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(controllerPackage, "controllerPackage must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        String entityName = resolveEntityName(table);
        String dtoName = entityName + Constants.DTO_SUFFIX;
        String serviceName = entityName + Constants.SERVICE_SUFFIX;
        String serviceVar = NamingConverter.decapitalizeFirstLetter(entityName) + Constants.SERVICE_SUFFIX;

        String dtoPackage = resolveDtoPackage(basePackage);
        String servicePackage = resolveServicePackage(basePackage);

        String lowerDisplayLabel = NamingConverter.toLogLabel(entityName);
        if (lowerDisplayLabel.isBlank()) {
            lowerDisplayLabel = entityName;
        }

        String displayLabel = NamingConverter.buildTitleCaseLabel(lowerDisplayLabel);
        if (displayLabel.isBlank()) {
            displayLabel = entityName;
        }

        boolean compositePk = hasCompositePrimaryKey(table);
        List<Column> pkColumns = getPrimaryKeyColumns(table);

        String pkType = compositePk ? null : detectSinglePrimaryKeyType(table);

        String apiPath = resolveApiPath(entityName);

        JavaImportCollector importCollector = buildControllerImports(dtoPackage, dtoName, servicePackage, serviceName, pkColumns);

        StringBuilder sourceBuilder = new StringBuilder();
        sourceBuilder.append("package ").append(controllerPackage).append(";\n\n");
        sourceBuilder.append(importCollector.buildImportBlock());

        sourceBuilder.append("/**\n");
        sourceBuilder.append(" * REST controller for managing ").append(displayLabel).append(" resources.\n");
        sourceBuilder.append(" * Generated automatically by SQLDomainGen.\n");
        sourceBuilder.append(" */\n");
        sourceBuilder.append("@RestController\n");
        sourceBuilder.append("@RequiredArgsConstructor\n");
        sourceBuilder.append("@Tag(name = \"").append(displayLabel).append("\", description = \"").append(displayLabel).append(" API\")\n");
        sourceBuilder.append("@RequestMapping(\"").append(apiPath).append("\")\n");
        sourceBuilder.append("public class ").append(entityName).append("Controller {\n\n");

        sourceBuilder.append("    private final ").append(serviceName).append(" ").append(serviceVar).append(";\n\n");

        appendGetAllMethod(sourceBuilder, entityName, dtoName, serviceVar);
        appendGetByIdMethod(sourceBuilder, entityName, dtoName, displayLabel, lowerDisplayLabel, serviceVar, pkType, pkColumns, compositePk);
        appendCreateMethod(sourceBuilder, entityName, dtoName, displayLabel, lowerDisplayLabel, serviceVar);
        appendPatchMethod(sourceBuilder, entityName, dtoName, displayLabel, lowerDisplayLabel, serviceVar, pkType, pkColumns, compositePk);
        appendDeleteMethod(sourceBuilder, entityName, displayLabel, lowerDisplayLabel, serviceVar, pkType, pkColumns, compositePk);

        sourceBuilder.append("}\n");
        return sourceBuilder.toString();
    }

    /**
     * Generates the registry controller used for user registration and authentication.
     *
     * @param controllerDir target controller directory
     * @param controllerPackage generated controller package
     * @param basePackage generated application base package
     * @param overwrite overwrite existing file when true
     */
    private void generateRegistryController(
            Path controllerDir,
            String controllerPackage,
            String basePackage,
            boolean overwrite
    ) {
        String securityDtoPackage =
                PackageResolver.resolvePackageName(basePackage, "security.dto");

        String servicePackage =
                PackageResolver.resolvePackageName(basePackage, Constants.SERVICE_PACKAGE);

        JavaImportCollector importCollector = new JavaImportCollector();

        importCollector.addImport(
                "import " + securityDtoPackage + ".RegisterRequest;"
        );
        importCollector.addImport(
                "import " + securityDtoPackage + ".LoginRequest;"
        );
        importCollector.addImport(
                "import " + securityDtoPackage + ".LoginResponse;"
        );
        importCollector.addImport(
                "import " + servicePackage + ".AuthService;"
        );

        GeneratorImportSupport.addControllerFrameworkImports(importCollector);

        StringBuilder sourceBuilder = new StringBuilder();

        sourceBuilder.append("package ")
                .append(controllerPackage)
                .append(";\n\n");

        sourceBuilder.append(importCollector.buildImportBlock());

        sourceBuilder.append("/**\n");
        sourceBuilder.append(" * REST controller for user registration and authentication.\n");
        sourceBuilder.append(" * Generated automatically by SQLDomainGen.\n");
        sourceBuilder.append(" */\n");

        sourceBuilder.append("@RestController\n");
        sourceBuilder.append("@RequiredArgsConstructor\n");
        sourceBuilder.append("@Tag(name = \"Registry\", description = \"User registration and authentication API\")\n");
        sourceBuilder.append("@RequestMapping(\"/registry\")\n");
        sourceBuilder.append("public class RegistryController {\n\n");

        sourceBuilder.append("    private final AuthService authService;\n\n");

        appendRegisterMethod(sourceBuilder, "authService");
        appendLoginMethod(sourceBuilder, "authService");

        sourceBuilder.append("}\n");

        Path filePath = controllerDir.resolve("RegistryController.java");
        GeneratorSupport.writeFile(filePath, sourceBuilder.toString(), overwrite);

        log.debug(
                "Generated RegistryController: {}",
                filePath.toAbsolutePath()
        );
    }

    /**
     * Appends the user registration endpoint to the registry controller.
     *
     * @param stringBuilder target builder
     * @param serviceName injected authentication service variable name
     */
    private void appendRegisterMethod(
            StringBuilder stringBuilder,
            String serviceName
    ) {
        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Registers a new application user.\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param request user registration request\n");
        stringBuilder.append("     * @return a {@link ResponseEntity} with HTTP status 201 (Created)\n");
        stringBuilder.append("     */\n");

        stringBuilder.append("    @Operation(summary = \"Register user\")\n");
        stringBuilder.append("    @PostMapping(\"/register\")\n");

        stringBuilder.append("    public ResponseEntity<Void> register(\n");
        stringBuilder.append("            @Valid @RequestBody RegisterRequest request) {\n");

        stringBuilder.append("        ")
                .append(serviceName)
                .append(".register(request);\n\n");

        stringBuilder.append("        return ResponseEntity\n");
        stringBuilder.append("                .status(HttpStatus.CREATED)\n");
        stringBuilder.append("                .build();\n");

        stringBuilder.append("    }\n\n");
    }

    /**
     * Appends the user login endpoint to the registry controller.
     *
     * @param stringBuilder target builder
     * @param serviceName injected authentication service variable name
     */
    private void appendLoginMethod(
            StringBuilder stringBuilder,
            String serviceName
    ) {
        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Authenticates an existing application user.\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param request user login request\n");
        stringBuilder.append("     * @return a {@link ResponseEntity} containing the generated access token\n");
        stringBuilder.append("     *         and HTTP status 200 (OK)\n");
        stringBuilder.append("     */\n");

        stringBuilder.append("    @Operation(summary = \"Login user\")\n");
        stringBuilder.append("    @PostMapping(\"/login\")\n");

        stringBuilder.append("    public ResponseEntity<LoginResponse> login(\n");
        stringBuilder.append("            @Valid @RequestBody LoginRequest request) {\n");

        stringBuilder.append("        return ResponseEntity\n");
        stringBuilder.append("                .status(HttpStatus.OK)\n");
        stringBuilder.append("                .body(")
                .append(serviceName)
                .append(".login(request));\n");

        stringBuilder.append("    }\n\n");
    }

    /**
     * Appends the get all controller method.
     *
     * @param sb target builder
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param serviceName injected service variable name
     */
    private void appendGetAllMethod(
            StringBuilder sb,
            String entityName,
            String dtoName,
            String serviceName
    ) {
        String pluralMethodSuffix = NamingConverter.toPascalCase(
                NamingConverter.toCamelCasePlural(entityName)
        );

        String pluralLowerDisplayLabel = NamingConverter.toLogLabel(
                NamingConverter.toCamelCasePlural(entityName)
        );

        String controllerMethodName = "getAll" + pluralMethodSuffix;

        sb.append("    /**\n");
        sb.append("     * Retrieves all available ")
                .append(pluralLowerDisplayLabel)
                .append(".\n");
        sb.append("     *\n");

        sb.append("     * @return a {@link ResponseEntity} containing a list of {@link ")
                .append(dtoName)
                .append("}\n");

        sb.append("     *         and HTTP status 200 (OK)\n");
        sb.append("     */\n");

        sb.append("    @Operation(summary = \"Get all ")
                .append(pluralLowerDisplayLabel)
                .append("\")\n");

        sb.append("    @GetMapping\n");

        sb.append("    public ResponseEntity<List<")
                .append(dtoName)
                .append(">> ")
                .append(controllerMethodName)
                .append("() {\n");

        sb.append("        return ResponseEntity\n");
        sb.append("                .status(HttpStatus.OK)\n");
        sb.append("                .body(")
                .append(serviceName)
                .append(".getAll")
                .append(pluralMethodSuffix)
                .append("());\n");

        sb.append("    }\n\n");
    }


    /**
     * Appends the get by id controller method.
     *
     * @param stringBuilder target builder
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param displayLabel readable display label
     * @param lowerDisplayLabel lowercase display label
     * @param serviceName injected service variable name
     * @param primaryKeyType primary key type for single-key entities
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey true when the entity uses a composite primary key
     */
    private void appendGetByIdMethod(
            StringBuilder stringBuilder,
            String entityName,
            String dtoName,
            String displayLabel,
            String lowerDisplayLabel,
            String serviceName,
            String primaryKeyType,
            List<Column> primaryKeyColumns,
            boolean compositePrimaryKey
    ) {
        String controllerMethodName = "get" + entityName + "ById";

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

        if (compositePrimaryKey) {
            for (Column primaryKeyColumn : primaryKeyColumns) {
                String parameterName = resolvePkParamName(primaryKeyColumn);
                String readableParameterLabel = NamingConverter.toLogLabel(parameterName);

                stringBuilder.append("     * @param ")
                        .append(parameterName)
                        .append(" the ")
                        .append(readableParameterLabel)
                        .append(" of the composite key\n");
            }
        } else {
            stringBuilder.append("     * @param id the identifier of the ")
                    .append(lowerDisplayLabel)
                    .append(" to retrieve\n");
        }

        stringBuilder.append("     * @return a {@link ResponseEntity} containing the requested {@link ")
                .append(dtoName)
                .append("}\n");
        stringBuilder.append("     *         and HTTP status 200 (OK)\n");
        stringBuilder.append("     */\n");

        if (compositePrimaryKey) {
            stringBuilder.append("    @Operation(summary = \"Get ")
                    .append(displayLabel)
                    .append(" by composite id\")\n");
        } else {
            stringBuilder.append("    @Operation(summary = \"Get ")
                    .append(displayLabel)
                    .append(" by id\")\n");
        }

        if (compositePrimaryKey) {
            stringBuilder.append("    @GetMapping(\"");
            appendCompositePath(stringBuilder, primaryKeyColumns);
            stringBuilder.append("\")\n");
            stringBuilder.append("    public ResponseEntity<")
                    .append(dtoName)
                    .append("> ")
                    .append(controllerMethodName)
                    .append("(\n");

            for (int index = 0; index < primaryKeyColumns.size(); index++) {
                Column primaryKeyColumn = primaryKeyColumns.get(index);
                String parameterName = resolvePkParamName(primaryKeyColumn);
                String parameterType = detectJavaTypeForPkColumn(primaryKeyColumn);

                stringBuilder.append("            @PathVariable ")
                        .append(parameterType)
                        .append(" ")
                        .append(parameterName);

                if (index < primaryKeyColumns.size() - 1) {
                    stringBuilder.append(",\n");
                } else {
                    stringBuilder.append(") {\n");
                }
            }

            stringBuilder.append("        return ResponseEntity\n");
            stringBuilder.append("                .status(HttpStatus.OK)\n");
            stringBuilder.append("                .body(")
                    .append(serviceName)
                    .append(".get")
                    .append(entityName)
                    .append("ById(");

            appendCompositeServiceArguments(stringBuilder, primaryKeyColumns);

            stringBuilder.append("));\n");
            stringBuilder.append("    }\n\n");
            return;
        }

        stringBuilder.append("    @GetMapping(\"/{id}\")\n");
        stringBuilder.append("    public ResponseEntity<")
                .append(dtoName)
                .append("> ")
                .append(controllerMethodName)
                .append("(\n");
        stringBuilder.append("            @PathVariable ")
                .append(primaryKeyType)
                .append(" id) {\n");

        stringBuilder.append("        return ResponseEntity\n");
        stringBuilder.append("                .status(HttpStatus.OK)\n");
        stringBuilder.append("                .body(")
                .append(serviceName)
                .append(".get")
                .append(entityName)
                .append("ById(id));\n");

        stringBuilder.append("    }\n\n");
    }


    /**
     * Appends the create controller method.
     *
     * @param stringBuilder target builder
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param displayLabel readable display label
     * @param lowerDisplayLabel lowercase display label
     * @param serviceName injected service variable name
     */
    private void appendCreateMethod(
            StringBuilder stringBuilder,
            String entityName,
            String dtoName,
            String displayLabel,
            String lowerDisplayLabel,
            String serviceName
    ) {
        String controllerMethodName = "create" + entityName;

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Creates a new ")
                .append(lowerDisplayLabel)
                .append(".\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param dto the ")
                .append(lowerDisplayLabel)
                .append(" payload to create; must be valid\n");
        stringBuilder.append("     * @return a {@link ResponseEntity} containing the created {@link ")
                .append(dtoName)
                .append("}\n");
        stringBuilder.append("     *         and HTTP status 201 (Created)\n");
        stringBuilder.append("     */\n");

        stringBuilder.append("    @Operation(summary = \"Create ")
                .append(displayLabel)
                .append("\")\n");
        stringBuilder.append("    @PostMapping\n");
        stringBuilder.append("    public ResponseEntity<")
                .append(dtoName)
                .append("> ")
                .append(controllerMethodName)
                .append("(\n");
        stringBuilder.append("            @Valid @RequestBody ")
                .append(dtoName)
                .append(" dto) {\n");

        stringBuilder.append("        ")
                .append(dtoName)
                .append(" created")
                .append(entityName)
                .append(" = ")
                .append(serviceName)
                .append(".create")
                .append(entityName)
                .append("(dto);\n\n");

        stringBuilder.append("        return ResponseEntity\n");
        stringBuilder.append("                .status(HttpStatus.CREATED)\n");
        stringBuilder.append("                .body(created")
                .append(entityName)
                .append(");\n");

        stringBuilder.append("    }\n\n");
    }


    /**
     * Appends the PATCH controller method.
     *
     * @param stringBuilder target builder
     * @param entityName entity simple name
     * @param dtoName dto simple name
     * @param displayLabel display label
     * @param lowerDisplayLabel lowercase display label
     * @param serviceName injected service variable name
     * @param primaryKeyType primary key type for single-key entities
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey true when the entity uses a composite primary key
     */
    private void appendPatchMethod(
            StringBuilder stringBuilder,
            String entityName,
            String dtoName,
            String displayLabel,
            String lowerDisplayLabel,
            String serviceName,
            String primaryKeyType,
            List<Column> primaryKeyColumns,
            boolean compositePrimaryKey
    ) {
        String label = lowerDisplayLabel.replaceAll("\\s+", " ").trim();
        String controllerMethodName = "patch" + entityName;

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Partially updates an existing ")
                .append(label)
                .append(".\n");
        stringBuilder.append("     * Only non-null fields from the request payload are updated.\n");
        stringBuilder.append("     *\n");

        if (compositePrimaryKey) {
            for (Column primaryKeyColumn : primaryKeyColumns) {
                String parameterName = resolvePkParamName(primaryKeyColumn);
                String readableParameterLabel = NamingConverter.toLogLabel(parameterName);

                stringBuilder.append("     * @param ")
                        .append(parameterName)
                        .append(" the ")
                        .append(readableParameterLabel)
                        .append(" of the composite key\n");
            }
        } else {
            stringBuilder.append("     * @param id the identifier of the ")
                    .append(label)
                    .append(" to update\n");
        }

        stringBuilder.append("     * @param dto the partial ")
                .append(label)
                .append(" payload\n");
        stringBuilder.append("     * @return a {@link ResponseEntity} containing the updated {@link ")
                .append(dtoName)
                .append("}\n");
        stringBuilder.append("     *         and HTTP status 200 (OK)\n");
        stringBuilder.append("     */\n");

        if (compositePrimaryKey) {
            stringBuilder.append("    @Operation(summary = \"Patch ")
                    .append(displayLabel)
                    .append(" by composite id\")\n");
        } else {
            stringBuilder.append("    @Operation(summary = \"Patch ")
                    .append(displayLabel)
                    .append("\")\n");
        }

        if (compositePrimaryKey) {
            stringBuilder.append("    @PatchMapping(\"");
            appendCompositePath(stringBuilder, primaryKeyColumns);
            stringBuilder.append("\")\n");
            stringBuilder.append("    public ResponseEntity<")
                    .append(dtoName)
                    .append("> ")
                    .append(controllerMethodName)
                    .append("(\n");

            for (Column primaryKeyColumn : primaryKeyColumns) {
                String parameterName = resolvePkParamName(primaryKeyColumn);
                String parameterType = detectJavaTypeForPkColumn(primaryKeyColumn);

                stringBuilder.append("            @PathVariable ")
                        .append(parameterType)
                        .append(" ")
                        .append(parameterName)
                        .append(",\n");
            }

            stringBuilder.append("            @RequestBody ")
                    .append(dtoName)
                    .append(" dto) {\n");

            stringBuilder.append("        ")
                    .append(dtoName)
                    .append(" updated")
                    .append(entityName)
                    .append(" = ")
                    .append(serviceName)
                    .append(".update")
                    .append(entityName)
                    .append("(");

            appendCompositeServiceArguments(stringBuilder, primaryKeyColumns);

            stringBuilder.append(", dto);\n\n");

            stringBuilder.append("        return ResponseEntity\n");
            stringBuilder.append("                .status(HttpStatus.OK)\n");
            stringBuilder.append("                .body(updated")
                    .append(entityName)
                    .append(");\n");

            stringBuilder.append("    }\n\n");
            return;
        }

        stringBuilder.append("    @PatchMapping(\"/{id}\")\n");
        stringBuilder.append("    public ResponseEntity<")
                .append(dtoName)
                .append("> ")
                .append(controllerMethodName)
                .append("(\n");
        stringBuilder.append("            @PathVariable ")
                .append(primaryKeyType)
                .append(" id,\n");
        stringBuilder.append("            @RequestBody ")
                .append(dtoName)
                .append(" dto) {\n");

        stringBuilder.append("        ")
                .append(dtoName)
                .append(" updated")
                .append(entityName)
                .append(" = ")
                .append(serviceName)
                .append(".update")
                .append(entityName)
                .append("(id, dto);\n\n");

        stringBuilder.append("        return ResponseEntity\n");
        stringBuilder.append("                .status(HttpStatus.OK)\n");
        stringBuilder.append("                .body(updated")
                .append(entityName)
                .append(");\n");

        stringBuilder.append("    }\n\n");
    }


    /**
     * Appends the delete controller method.
     *
     * @param stringBuilder target builder
     * @param entityName entity simple name
     * @param displayLabel display label
     * @param lowerDisplayLabel lowercase display label
     * @param serviceName injected service variable name
     * @param primaryKeyType primary key type for single-key entities
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey true when the entity uses a composite primary key
     */
    private void appendDeleteMethod(
            StringBuilder stringBuilder,
            String entityName,
            String displayLabel,
            String lowerDisplayLabel,
            String serviceName,
            String primaryKeyType,
            List<Column> primaryKeyColumns,
            boolean compositePrimaryKey
    ) {
        String controllerMethodName = "delete" + entityName + "ById";

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

        if (compositePrimaryKey) {
            for (Column primaryKeyColumn : primaryKeyColumns) {
                String parameterName = resolvePkParamName(primaryKeyColumn);
                String readableParameterLabel = NamingConverter.toLogLabel(parameterName);

                stringBuilder.append("     * @param ")
                        .append(parameterName)
                        .append(" the ")
                        .append(readableParameterLabel)
                        .append(" of the composite key\n");
            }
        } else {
            stringBuilder.append("     * @param id the identifier of the ")
                    .append(lowerDisplayLabel)
                    .append(" to delete\n");
        }

        stringBuilder.append("     * @return a {@link ResponseEntity} with HTTP status 204 (No Content)\n");
        stringBuilder.append("     */\n");

        if (compositePrimaryKey) {
            stringBuilder.append("    @Operation(summary = \"Delete ")
                    .append(displayLabel)
                    .append(" by composite id\")\n");
        } else {
            stringBuilder.append("    @Operation(summary = \"Delete ")
                    .append(displayLabel)
                    .append("\")\n");
        }

        if (compositePrimaryKey) {
            stringBuilder.append("    @DeleteMapping(\"");
            appendCompositePath(stringBuilder, primaryKeyColumns);
            stringBuilder.append("\")\n");
            stringBuilder.append("    public ResponseEntity<Void> ")
                    .append(controllerMethodName)
                    .append("(\n");

            for (int index = 0; index < primaryKeyColumns.size(); index++) {
                Column primaryKeyColumn = primaryKeyColumns.get(index);
                String parameterName = resolvePkParamName(primaryKeyColumn);
                String parameterType = detectJavaTypeForPkColumn(primaryKeyColumn);

                stringBuilder.append("            @PathVariable ")
                        .append(parameterType)
                        .append(" ")
                        .append(parameterName);

                if (index < primaryKeyColumns.size() - 1) {
                    stringBuilder.append(",\n");
                } else {
                    stringBuilder.append(") {\n");
                }
            }

            stringBuilder.append("        ")
                    .append(serviceName)
                    .append(".delete")
                    .append(entityName)
                    .append("(");

            appendCompositeServiceArguments(stringBuilder, primaryKeyColumns);

            stringBuilder.append(");\n\n");
            stringBuilder.append("        return ResponseEntity\n");
            stringBuilder.append("                .status(HttpStatus.NO_CONTENT)\n");
            stringBuilder.append("                .build();\n");
            stringBuilder.append("    }\n\n");
            return;
        }

        stringBuilder.append("    @DeleteMapping(\"/{id}\")\n");
        stringBuilder.append("    public ResponseEntity<Void> ")
                .append(controllerMethodName)
                .append("(\n");
        stringBuilder.append("            @PathVariable ")
                .append(primaryKeyType)
                .append(" id) {\n");
        stringBuilder.append("        ")
                .append(serviceName)
                .append(".delete")
                .append(entityName)
                .append("(id);\n\n");
        stringBuilder.append("        return ResponseEntity\n");
        stringBuilder.append("                .status(HttpStatus.NO_CONTENT)\n");
        stringBuilder.append("                .build();\n");
        stringBuilder.append("    }\n\n");
    }

    /**
     * Appends the composite primary key path segment.
     *
     * @param sb target builder
     * @param pkColumns primary key columns
     */
    private void appendCompositePath(StringBuilder sb, List<Column> pkColumns) {
        for (Column pkCol : pkColumns) {
            sb.append("/").append("{").append(resolvePkParamName(pkCol)).append("}");
        }
    }

    /**
     * Appends ordered composite primary key arguments for service method calls.
     *
     * @param sb target builder
     * @param pkColumns primary key columns
     */
    private void appendCompositeServiceArguments(StringBuilder sb, List<Column> pkColumns) {
        for (int i = 0; i < pkColumns.size(); i++) {
            sb.append(resolvePkParamName(pkColumns.get(i)));
            if (i < pkColumns.size() - 1) {
                sb.append(", ");
            }
        }
    }



    /**
     * Checks whether a table uses a composite primary key.
     *
     * @param table source table metadata
     * @return true when more than one primary key column exists
     */
    private static boolean hasCompositePrimaryKey(Table table) {
        long pkCount = table.getColumns().stream()
                .filter(Objects::nonNull)
                .filter(Column::isPrimaryKey)
                .count();
        return pkCount > 1;
    }

    /**
     * Resolves the Java type to use for a primary key column.
     *
     * @param column primary key column metadata
     * @return resolved Java type name
     */
    private String detectJavaTypeForPkColumn(Column column) {
        if (column == null) {
            return Constants.DEFAULT_PK_TYPE;
        }

        String rawType = column.getJavaType();
        if (rawType == null || rawType.isBlank()) {
            return Constants.DEFAULT_PK_TYPE;
        }

        return JavaTypeSupport.resolveSimpleType(rawType);
    }



    /**
     * Returns all primary key columns of the table.
     *
     * @param table source table metadata
     * @return list of primary key columns
     */
    private static List<Column> getPrimaryKeyColumns(Table table) {
        Objects.requireNonNull(table, "table must not be null");

        return table.getColumns().stream()
                .filter(Objects::nonNull)
                .filter(Column::isPrimaryKey)
                .toList();
    }

    /**
     * Resolves the Java type for a single primary key.
     *
     * @param table source table metadata
     * @return resolved Java type name
     */
    private static String detectSinglePrimaryKeyType(Table table) {
        List<Column> primaryKeyColumns = getPrimaryKeyColumns(table);

        if (primaryKeyColumns.isEmpty()) {
            throw new IllegalStateException("No Primary Key found for table: " + table.getName());
        }

        if (primaryKeyColumns.size() > 1) {
            throw new IllegalStateException(
                    "Composite Primary Key found for table: " + table.getName()
                            + ". Single PK type cannot be detected."
            );
        }

        Column primaryKeyColumn = primaryKeyColumns.getFirst();

        String rawType = primaryKeyColumn.getJavaType();
        if (rawType == null || rawType.isBlank()) {
            return Constants.DEFAULT_PK_TYPE;
        }

        return JavaTypeSupport.resolveSimpleType(rawType);
    }


    /**
     * Resolves a safe camelCase parameter name for a primary key column.
     *
     * @param column primary key column metadata
     * @return normalized camelCase parameter name
     */
    private String resolvePkParamName(Column column) {
        if (column == null || column.getName() == null || column.getName().isBlank()) {
            return Constants.DEFAULT_ID_PARAM;
        }

        String columnName = GeneratorSupport.unquoteIdentifier(column.getName());
        if (columnName == null || columnName.isBlank()) {
            return Constants.DEFAULT_ID_PARAM;
        }

        return NamingConverter.toCamelCase(columnName);
    }

    /**
     * Resolves and creates the controller package directory.
     *
     * @param outputDir output directory
     * @param basePackage base package
     * @return controller directory path
     */
    private Path resolveControllerDirectory(String outputDir, String basePackage) {
        return GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(outputDir, basePackage, Constants.CONTROLLER_PACKAGE)
        );
    }

    /**
     * Resolves the controller package name.
     *
     * @param basePackage base package
     * @return resolved controller package name
     */
    private String resolveControllerPackage(String basePackage) {
        return PackageResolver.resolvePackageName(basePackage, Constants.CONTROLLER_PACKAGE);
    }

    /**
     * Resolves the normalized entity name for a table.
     *
     * @param table source table metadata
     * @return normalized entity name
     */
    private String resolveEntityName(Table table) {
        return NamingConverter.toPascalCase(
                GeneratorSupport.normalizeTableName(table.getName())
        );
    }

    /**
     * Resolves the REST API path for an entity.
     *
     * @param entityName entity simple name
     * @return resolved API path
     */
    private String resolveApiPath(String entityName) {
        String normalizedEntityName = entityName;

        if (entityName.startsWith(Constants.API_ENTITY_PREFIX) && entityName.length() > 3 && Character.isUpperCase(entityName.charAt(3))) {
            normalizedEntityName = entityName.substring(3);
        }

        return Constants.API_BASE_PATH + NamingConverter.toKebabCase(normalizedEntityName);
    }

    /**
     * Resolves the DTO package name.
     *
     * @param basePackage base package
     * @return resolved DTO package name
     */
    private String resolveDtoPackage(String basePackage) {
        return PackageResolver.resolvePackageName(basePackage, Constants.DTO_PACKAGE);
    }

    /**
     * Resolves the service package name.
     *
     * @param basePackage base package
     * @return resolved service package name
     */
    private String resolveServicePackage(String basePackage) {
        return PackageResolver.resolvePackageName(basePackage, Constants.SERVICE_PACKAGE);
    }

    /**
     * Builds imports required by a generated controller.
     *
     * @param dtoPackage DTO package name
     * @param dtoName DTO simple class name
     * @param servicePackage service package name
     * @param serviceName service simple interface name
     * @param primaryKeyColumns primary key columns
     * @return populated import collector
     */
    private JavaImportCollector buildControllerImports(
            String dtoPackage,
            String dtoName,
            String servicePackage,
            String serviceName,
            List<Column> primaryKeyColumns
    ) {
        JavaImportCollector importCollector = new JavaImportCollector();

        importCollector.addImport("import " + dtoPackage + "." + dtoName + ";");
        importCollector.addImport("import " + servicePackage + "." + serviceName + ";");

        GeneratorImportSupport.addControllerFrameworkImports(importCollector);

        primaryKeyColumns.forEach(column ->
                importCollector.addImportForType(detectJavaTypeForPkColumn(column))
        );

        return importCollector;
    }

}