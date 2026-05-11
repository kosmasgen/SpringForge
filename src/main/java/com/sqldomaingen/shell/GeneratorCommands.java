package com.sqldomaingen.shell;

import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.validation.GenerationValidationReport;
import com.sqldomaingen.validation.GenerationValidationRunner;
import com.sqldomaingen.validation.ValidationReportPdfWriter;
import com.sqldomaingen.generator.ConfigGenerator;
import com.sqldomaingen.generator.ControllerGenerator;
import com.sqldomaingen.generator.DTOGenerator;
import com.sqldomaingen.generator.EntityGenerator;
import com.sqldomaingen.generator.ExceptionGenerator;
import com.sqldomaingen.generator.LiquibaseGenerator;
import com.sqldomaingen.generator.MapperGenerator;
import com.sqldomaingen.generator.ProjectScaffoldGenerator;
import com.sqldomaingen.generator.RepositoryGenerator;
import com.sqldomaingen.generator.ServiceGenerator;
import com.sqldomaingen.generatorTest.TestGenerator;
import com.sqldomaingen.model.Entity;
import com.sqldomaingen.model.IndexDefinition;
import com.sqldomaingen.model.Table;
import com.sqldomaingen.parser.CreateIndexDefinition;
import com.sqldomaingen.parser.CreateTableDefinition;
import com.sqldomaingen.parser.PostgreSQLParser;
import com.sqldomaingen.parser.SQLParser;
import com.sqldomaingen.util.Constants;
import com.sqldomaingen.validation.ValidationReportXmlWriter;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.antlr.v4.runtime.TokenStream;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;
import com.sqldomaingen.config.GeneratorConfig;
import com.sqldomaingen.config.GeneratorConfigLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Spring Shell commands for generating a full Spring Boot backend from a SQL schema.
 */
@NoArgsConstructor
@ShellComponent
@Log4j2
public class GeneratorCommands {


    private final EntityGenerator entityGenerator = new EntityGenerator();

    /**
     * Generates the full Spring backend from the provided SQL file
     * and produces validation XML and PDF reports in the output directory.
     *
     * @param inputFile input SQL file path
     * @param outputDir output project directory
     * @param packageName base package name
     * @param overwrite whether existing files should be overwritten
     * @param useBuilder whether builder generation should be enabled
     * @param author Liquibase author
     * @return generation result message
     */
    @ShellMethod("Generate full Spring backend from a SQL file.")
    public String generateEntity(
            @ShellOption(value = {"--input-file", "-i"}) String inputFile,
            @ShellOption(value = {"--output-dir", "-o"}) String outputDir,
            @ShellOption(value = {"--package-name", "-p"}) String packageName,
            @ShellOption(value = {"--overwrite", "-w"}, defaultValue = "false") boolean overwrite,
            @ShellOption(value = {"--use-builder", "-b"}, defaultValue = "false") boolean useBuilder,
            @ShellOption(value = {"--author", "-a"}, defaultValue = ShellOption.NULL) String author
    ) {
        try {
            validateOutputDirectory(outputDir);

            List<Table> parsedTables = processSQLFile(inputFile);
            if (parsedTables.isEmpty()) {
                log.warn("No tables were produced from the SQL file.");
                return "No tables were produced from the SQL file.";
            }

            List<Table> javaGenerationTables = filterTablesForJavaGeneration(parsedTables);
            if (javaGenerationTables.isEmpty()) {
                log.warn("No eligible Java generation tables remained after exclusions.");
            }

            GeneratorConfig generatorConfig = GeneratorConfigLoader.load();
            List<Table> businessGenerationTables = filterLookupTablesForCrudGeneration(javaGenerationTables, generatorConfig);

            log.info("Starting generation pipeline...");

            String defaultSchemaName = resolveDefaultSchemaName(parsedTables);

            new ProjectScaffoldGenerator().generateScaffold(
                    outputDir,
                    packageName,
                    defaultSchemaName,
                    overwrite
            );
            new ConfigGenerator().generateConfigs(outputDir, packageName, overwrite);
            new ExceptionGenerator().generateExceptionHandling(outputDir, packageName, overwrite);

            entityGenerator.generate(javaGenerationTables, outputDir, packageName, overwrite, useBuilder);

            List<Entity> models = entityGenerator.toEntities(javaGenerationTables);

            new DTOGenerator().generateDTOs(models, outputDir, packageName);

            Map<String, Table> tableMap = javaGenerationTables.stream()
                    .collect(Collectors.toMap(Table::getName, tableValue -> tableValue));

            new MapperGenerator(tableMap).generateMappers(outputDir, packageName);
            new RepositoryGenerator().generateRepositories(javaGenerationTables, outputDir, packageName);
            new ServiceGenerator().generateAllServices(businessGenerationTables, outputDir, packageName);
            new ControllerGenerator().generateControllers(businessGenerationTables, outputDir, packageName, overwrite);
            new TestGenerator().generateTests(businessGenerationTables, javaGenerationTables, models, outputDir,
                    packageName, overwrite);

            new LiquibaseGenerator().generateLiquibaseFiles(outputDir, parsedTables, overwrite, author);

            Path validationDir = createValidationOutputDirectory(outputDir);

            GenerationValidationReport validationReport = new GenerationValidationRunner().run(
                    inputFile,
                    outputDir,
                    packageName,
                    author,
                    parsedTables,
                    models
            );

            Path xmlPath = validationDir.resolve("validation-report.xml");
            Path pdfPath = validationDir.resolve("validation-report.pdf");

            new ValidationReportXmlWriter().writeXml(validationReport, xmlPath);
            new ValidationReportPdfWriter().writePdf(validationReport, pdfPath);

            log.info("Backend generation completed successfully. Output dir: {}", outputDir);
            log.info("Validation reports written under: {}", validationDir.toAbsolutePath());

            System.exit(0);

            return "Backend generation completed successfully. Validation reports generated at: "
                    + validationDir.toAbsolutePath();

        } catch (Exception exception) {
            log.error("Generation failed", exception);
            return "Generation failed: " + exception.getMessage();
        }
    }

    /**
     * Creates the validation report output directory under the generated project root.
     *
     * @param outputDir output project directory
     * @return validation output directory
     * @throws IOException if directory creation fails
     */
    private Path createValidationOutputDirectory(String outputDir) throws IOException {
        Path validationDir = Paths.get(outputDir, "validation");
        Files.createDirectories(validationDir);
        return validationDir;
    }


    /**
     * Filters parsed tables and removes those that must not participate
     * in Java code generation.
     *
     * @param tables parsed table models
     * @return filtered list of Java generation tables
     */
    private List<Table> filterTablesForJavaGeneration(List<Table> tables) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }

        List<Table> filteredTables = new ArrayList<>();

        for (Table table : tables) {
            if (table == null || table.getName() == null || table.getName().isBlank()) {
                continue;
            }

            String normalizedTableName = GeneratorSupport.normalizeTableName(table.getName());

            if (Constants.JAVA_EXCLUDED_TABLES.contains(normalizedTableName)) {
                log.info("Skipping Java generation for excluded table: {}", table.getName());
                continue;
            }

            filteredTables.add(table);
        }

        return filteredTables;
    }


    /**
     * Ensures the output directory exists and is usable.
     * Creates it if missing.
     *
     * @param outputDirectory target output directory
     */
    public void validateOutputDirectory(String outputDirectory) {
        if (outputDirectory == null || outputDirectory.trim().isEmpty()) {
            throw new IllegalArgumentException("Output directory cannot be null or empty.");
        }

        Path path = Paths.get(outputDirectory);
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException exception) {
                throw new RuntimeException("Failed to create output directory: " + outputDirectory, exception);
            }
        }
    }

    /**
     * Reads a SQL file and converts its CREATE TABLE statements into {@link Table} objects.
     *
     * @param inputFile SQL file path
     * @return list of parsed tables
     * @throws IOException if the file exists but cannot be read
     */
    public List<Table> processSQLFile(String inputFile) throws IOException {
        if (inputFile == null || inputFile.isEmpty()) {
            log.error("Input SQL file is null or empty.");
            throw new IllegalArgumentException("Input SQL file cannot be null or empty.");
        }

        log.info("Processing SQL file: {}", inputFile);

        Path filePath = Paths.get(inputFile);
        if (!Files.exists(filePath)) {
            log.warn("SQL file does not exist: {}", inputFile);
            return List.of();
        }

        String sqlContent = Files.readString(filePath);
        log.debug("SQL content loaded from '{}'", inputFile);

        return parseSQLToTables(sqlContent);
    }

    /**
     * Parses SQL content using the ANTLR PostgreSQL grammar and returns table models.
     *
     * @param sqlContent raw SQL string
     * @return list of tables parsed from the SQL content
     */
    public List<Table> parseSQLToTables(String sqlContent) {
        try {
            SQLParser sqlParserInstance = new SQLParser(sqlContent);
            TokenStream tokenStream = sqlParserInstance.parseSQL();

            PostgreSQLParser parser = new PostgreSQLParser(tokenStream);
            PostgreSQLParser.SqlScriptContext context = parser.sqlScript();

            List<Table> tables = new ArrayList<>();
            Map<String, Table> tableMap = new HashMap<>();

            for (PostgreSQLParser.CreateTableStatementContext createContext : context.createTableStatement()) {
                CreateTableDefinition createTableDefinition = new CreateTableDefinition();
                createTableDefinition.processCreateTable(createContext);

                Table table = createTableDefinition.toTable();
                tables.add(table);
                tableMap.put(table.getName(), table);
            }

            CreateIndexDefinition createIndexDefinition = new CreateIndexDefinition();

            for (PostgreSQLParser.CreateIndexStatementContext indexContext : context.createIndexStatement()) {
                IndexDefinition indexDefinition = createIndexDefinition.processCreateIndex(indexContext);

                Table table = tableMap.get(indexDefinition.getTableName());

                if (table != null) {
                    table.getIndexes().add(indexDefinition);

                    log.info("Attached index '{}' to table '{}'",
                            indexDefinition.getName(),
                            table.getName());
                } else {
                    log.warn("Table '{}' not found for index '{}'",
                            indexDefinition.getTableName(),
                            indexDefinition.getName());
                }
            }

            return tables;

        } catch (Exception exception) {
            log.error("Error parsing SQL content", exception);
            return List.of();
        }
    }

    private String resolveDefaultSchemaName(List<Table> tables) {
        if (tables == null || tables.isEmpty()) {
            return "public";
        }

        for (Table table : tables) {
            if (table == null || table.getName() == null || table.getName().isBlank()) {
                continue;
            }

            String tableName = table.getName().trim();
            int dotIndex = tableName.indexOf('.');

            if (dotIndex > 0) {
                return tableName.substring(0, dotIndex);
            }
        }

        return "public";
    }

    /**
     * Filters lookup tables out of CRUD generation.
     *
     * @param tables Java generation tables
     * @param generatorConfig generator configuration
     * @return tables eligible for service, controller, and CRUD test generation
     */
    private List<Table> filterLookupTablesForCrudGeneration(List<Table> tables, GeneratorConfig generatorConfig) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }

        if (generatorConfig == null) {
            return tables;
        }

        List<Table> filteredTables = new ArrayList<>();

        for (Table table : tables) {
            if (table == null || table.getName() == null || table.getName().isBlank()) {
                continue;
            }

            if (generatorConfig.isLookupTable(table.getName())) {
                log.info("Skipping CRUD generation for lookup table: {}", table.getName());
                continue;
            }

            filteredTables.add(table);
        }

        return filteredTables;
    }


}