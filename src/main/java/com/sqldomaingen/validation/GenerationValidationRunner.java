package com.sqldomaingen.validation;

import com.sqldomaingen.model.Entity;
import com.sqldomaingen.model.Table;
import com.sqldomaingen.util.PackageResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Collects generation validation information and produces a unified report.
 */
public class GenerationValidationRunner {

    /**
     * Runs post-generation validations and returns one unified report.
     *
     * @param inputFile input SQL file path
     * @param outputDir output project directory
     * @param basePackage base Java package
     * @param author Liquibase/generation author
     * @param parsedTables all parsed tables
     * @param models generated entity models
     * @param liquibaseWarnings Liquibase generation warnings
     * @return unified validation report
     */
    public GenerationValidationReport run(
            String inputFile,
            String outputDir,
            String basePackage,
            String author,
            List<Table> parsedTables,
            List<Entity> models,
            List<String> liquibaseWarnings
    ) {
        GenerationValidationReport report =
                new GenerationValidationReport(inputFile, outputDir, basePackage, author);

        appendGenerationSummarySection(report, outputDir, basePackage, parsedTables);
        appendInfrastructureSection(report, outputDir, basePackage);
        appendI18nSupportSection(report, outputDir, basePackage);
        appendParsedTableNamesSection(report, parsedTables);
        appendGeneratedModelsSection(report, models);

        appendLiquibaseSection(report, outputDir, liquibaseWarnings);

        appendSchemaValidationChecklistSection(report, inputFile, outputDir);
        appendTodoEntitiesSection(report, inputFile, outputDir);
        appendSchemaValidationSection(report, inputFile, outputDir);

        return report;
    }

    /**
     * Appends the generation summary section using actual filesystem counts.
     *
     * @param report target report
     * @param outputDir output directory
     * @param basePackage base package
     * @param parsedTables parsed tables
     */
    private void appendGenerationSummarySection(
            GenerationValidationReport report,
            String outputDir,
            String basePackage,
            List<Table> parsedTables
    ) {
        List<String> details = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        Path entityDir = PackageResolver.resolvePath(outputDir, basePackage, "entity");
        Path dtoDir = PackageResolver.resolvePath(outputDir, basePackage, "dto");
        Path repositoryDir = PackageResolver.resolvePath(outputDir, basePackage, "repository");
        Path mapperDir = PackageResolver.resolvePath(outputDir, basePackage, "mapper");
        Path serviceDir = PackageResolver.resolvePath(outputDir, basePackage, "service");
        Path serviceImplDir = PackageResolver.resolvePath(outputDir, basePackage, "serviceImpl");
        Path controllerDir = PackageResolver.resolvePath(outputDir, basePackage, "controller");

        int entityCount = countJavaFiles(entityDir);
        int dtoCount = countJavaFiles(dtoDir);
        int repositoryCount = countJavaFiles(repositoryDir);
        int mapperCount = countJavaFiles(mapperDir);
        int serviceCount = countJavaFiles(serviceDir);
        int serviceImplCount = countJavaFiles(serviceImplDir);
        int controllerCount = countJavaFiles(controllerDir);

        details.add("Parsed tables: " + safeSize(parsedTables));
        details.add("Generated files:");
        details.add("Entities: " + entityCount);
        details.add("Dto: " + dtoCount);
        details.add("Repositories: " + repositoryCount);
        details.add("Mappers: " + mapperCount + " (include BaseMapper)");
        details.add("Services: " + serviceCount);
        details.add("ServiceImpls: " + serviceImplCount);
        details.add("Controllers: " + controllerCount);

        if (parsedTables == null || parsedTables.isEmpty()) {
            violations.add("Parsed tables list is empty.");
        }
        if (entityCount == 0) {
            violations.add("No entity files were found.");
        }
        if (dtoCount == 0) {
            violations.add("No dto files were found.");
        }
        if (repositoryCount == 0) {
            violations.add("No repository files were found.");
        }
        if (mapperCount == 0) {
            violations.add("No mapper files were found.");
        }
        if (serviceCount == 0) {
            violations.add("No service files were found.");
        }
        if (serviceImplCount == 0) {
            violations.add("No serviceImpl files were found.");
        }
        if (controllerCount == 0) {
            violations.add("No controller files were found.");
        }

        report.addSection("Generation Summary", details, violations);
    }

    /**
     * Appends the infrastructure section using actual filesystem counts.
     *
     * @param report target report
     * @param outputDir output directory
     * @param basePackage base package
     */
    private void appendInfrastructureSection(
            GenerationValidationReport report,
            String outputDir,
            String basePackage
    ) {
        List<String> details = new ArrayList<>();

        Path configDir = PackageResolver.resolvePath(outputDir, basePackage, "config");
        Path exceptionDir = PackageResolver.resolvePath(outputDir, basePackage, "exception");
        Path pomFile = Paths.get(outputDir, "pom.xml");
        Path applicationPropertiesFile = Paths.get(
                outputDir,
                "src",
                "main",
                "resources",
                "application.properties"
        );

        details.add("Config Classes: " + countJavaFiles(configDir));
        details.add("Exception Classes: " + countJavaFiles(exceptionDir));
        details.add("Build Files (pom.xml): " + (Files.exists(pomFile) ? 1 : 0));
        details.add("Configuration Files (application.properties): " + (Files.exists(applicationPropertiesFile) ? 1 : 0));

        report.addSection("Infrastructure", details, List.of());
    }

    /**
     * Appends the schema validation checklist section.
     *
     * @param report target report
     * @param inputFile input SQL file path
     * @param outputDir output project directory
     */
    private void appendSchemaValidationChecklistSection(
            GenerationValidationReport report,
            String inputFile,
            String outputDir
    ) {
        List<String> details = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        try {
            EntitySchemaValidator validator = new EntitySchemaValidator(
                    Paths.get(inputFile),
                    Paths.get(outputDir, "src", "main", "java")
            );

            List<String> checklist = validator.getValidationChecklistLines();

            details.add("Total checks: " + checklist.size());
            details.addAll(checklist);
        } catch (Exception exception) {
            violations.add("Could not build schema validation checklist: " + exception.getMessage());
        }

        report.addSection("Schema Validation Checklist", details, violations);
    }

    /**
     * Appends schema validation results into the report.
     * The section is added ONLY when violations exist.
     *
     * @param report target report
     * @param inputFile input SQL file path
     * @param outputDir output project directory
     */
    private void appendSchemaValidationSection(
            GenerationValidationReport report,
            String inputFile,
            String outputDir
    ) {
        List<String> details = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        try {
            EntitySchemaValidationService service = new EntitySchemaValidationService(
                    Paths.get(inputFile),
                    Paths.get(outputDir, "src", "main", "java")
            );

            List<String> results = service.validate();

            if (!results.isEmpty()) {
                details.add("Schema violations detected: " + results.size());
                violations.addAll(results);
                report.addSection("Schema Validation", details, violations);
            }

        } catch (Exception exception) {
            violations.add("Schema validation failed: " + exception.getMessage());
            report.addSection("Schema Validation", details, violations);
        }
    }


    /**
     * Appends the Liquibase section.
     *
     * @param report target report
     * @param outputDir output directory
     * @param liquibaseWarnings Liquibase generation warnings
     */
    private void appendLiquibaseSection(
            GenerationValidationReport report,
            String outputDir,
            List<String> liquibaseWarnings
    ) {
        List<String> details = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        Path changelogRoot = Paths.get(outputDir, "src", "main", "resources", "db", "migration", "changelogs", "v0.1.0");

        details.add("Liquibase xml files: " + countXmlFiles(changelogRoot));

        validateDirectoryExists(changelogRoot, violations);

        report.addSection("Liquibase Output", details, violations, liquibaseWarnings == null ? List.of() : liquibaseWarnings);
    }

    /**
     * Validates that the Liquibase changelog directory exists.
     *
     * @param directory directory path
     * @param violations target violations
     */
    private void validateDirectoryExists(Path directory, List<String> violations) {
        if (!Files.exists(directory)) {
            violations.add("Liquibase changelog directory does not exist: " + directory.toAbsolutePath());
            return;
        }

        if (!Files.isDirectory(directory)) {
            violations.add("Liquibase changelog directory is not a directory: " + directory.toAbsolutePath());
        }
    }


    /**
     * Counts Java source files in a directory.
     *
     * @param directory source directory
     * @return Java file count
     */
    private int countJavaFiles(Path directory) {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return 0;
        }

        try (var paths = Files.walk(directory)) {
            return (int) paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .count();
        } catch (Exception exception) {
            return 0;
        }
    }

    /**
     * Counts XML files in a directory.
     *
     * @param directory target directory
     * @return XML file count
     */
    private int countXmlFiles(Path directory) {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return 0;
        }

        try (var paths = Files.walk(directory)) {
            return (int) paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".xml"))
                    .count();
        } catch (Exception exception) {
            return 0;
        }
    }

    /**
     * Appends the generated entities with TODO comments section.
     *
     * @param report target report
     * @param inputFile input SQL file path
     * @param outputDir output project directory
     */
    private void appendTodoEntitiesSection(
            GenerationValidationReport report,
            String inputFile,
            String outputDir
    ) {
        List<String> details = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        try {
            EntitySchemaValidator validator = new EntitySchemaValidator(
                    Paths.get(inputFile),
                    Paths.get(outputDir, "src", "main", "java")
            );

            List<String> todoEntities = validator.findEntityDisplayNamesWithTodoComments();

            details.add("Total classes with TODO: " + todoEntities.size());

            if (todoEntities.isEmpty()) {
                details.add("No generated entity classes with TODO comments.");
            } else {
                details.addAll(todoEntities);
            }
        } catch (Exception exception) {
            violations.add("Could not collect generated entity classes with TODO comments: "
                    + exception.getMessage());
        }

        report.addSection("Generated Entity Classes With TODO Comments", details, violations);
    }

    /**
     * Appends a report section containing parsed table names in alphabetical order.
     *
     * @param report target report
     * @param parsedTables already parsed schema tables
     */
    private void appendParsedTableNamesSection(
            GenerationValidationReport report,
            List<Table> parsedTables
    ) {
        List<String> details = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        if (parsedTables == null) {
            violations.add("Parsed tables list is null.");
            report.addSection("Schema Tables", details, violations);
            return;
        }

        List<String> tableNames = parsedTables.stream()
                .filter(Objects::nonNull)
                .map(Table::getName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tableName -> !tableName.isBlank())
                .map(this::stripSchemaPrefix)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        if (tableNames.isEmpty()) {
            violations.add("No parsed table names were found.");
            report.addSection("Schema Tables", details, violations);
            return;
        }

        details.addAll(tableNames);

        report.addSection("Schema Tables", details, violations);
    }


    /**
     * Appends generated models section.
     *
     * @param report validation report
     * @param models generated entity models
     */
    private void appendGeneratedModelsSection(
            GenerationValidationReport report,
            List<Entity> models
    ) {
        List<String> details = new ArrayList<>();

        if (models == null || models.isEmpty()) {
            details.add("No models were generated.");
            report.addSection("Generated Models", details, List.of());
            return;
        }

        details.add("Total models: " + models.size());

        for (Entity entity : models) {
            details.add(entity.getName());
        }

        report.addSection("Generated Models", details, List.of());
    }

    /**
     * Removes the schema prefix from a physical table name.
     *
     * @param tableName physical table name
     * @return schema-free table name
     */
    private String stripSchemaPrefix(String tableName) {
        int dotIndex = tableName.indexOf('.');
        return dotIndex >= 0 ? tableName.substring(dotIndex + 1) : tableName;
    }

    /**
     * Appends the i18n support section using actual generated files.
     *
     * @param report target report
     * @param outputDir output directory
     * @param basePackage base package
     */
    private void appendI18nSupportSection(
            GenerationValidationReport report,
            String outputDir,
            String basePackage
    ) {
        List<String> details = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        Path utilDir = PackageResolver.resolvePath(outputDir, basePackage, "util");
        Path messageResolverFile = utilDir.resolve("MessageResolver.java");

        Path messagesFile = Paths.get(
                outputDir,
                "src",
                "main",
                "resources",
                "messages.properties"
        );

        Path greekMessagesFile = Paths.get(
                outputDir,
                "src",
                "main",
                "resources",
                "messages_el.properties"
        );

        boolean messageResolverExists = Files.exists(messageResolverFile);
        boolean defaultMessagesExists = Files.exists(messagesFile);
        boolean greekMessagesExists = Files.exists(greekMessagesFile);

        details.add("MessageResolver generated: " + messageResolverExists);
        details.add("Default message bundle generated: " + defaultMessagesExists);
        details.add("Greek message bundle generated: " + greekMessagesExists);
        details.add("Message bundle files: " + countExistingFiles(messagesFile, greekMessagesFile));

        if (!messageResolverExists) {
            violations.add("MessageResolver.java was not found: " + messageResolverFile.toAbsolutePath());
        }

        if (!defaultMessagesExists) {
            violations.add("messages.properties was not found: " + messagesFile.toAbsolutePath());
        }

        if (!greekMessagesExists) {
            violations.add("messages_el.properties was not found: " + greekMessagesFile.toAbsolutePath());
        }

        report.addSection("I18n Support", details, violations);
    }

    /**
     * Counts existing files from the provided paths.
     *
     * @param paths file paths
     * @return number of existing regular files
     */
    private int countExistingFiles(Path... paths) {
        int count = 0;

        if (paths == null) {
            return count;
        }

        for (Path path : paths) {
            if (path != null && Files.isRegularFile(path)) {
                count++;
            }
        }

        return count;
    }

    /**
     * Returns a safe size for a list.
     *
     * @param values source list
     * @return list size or zero
     */
    private int safeSize(List<?> values) {
        return values == null ? 0 : values.size();
    }


}