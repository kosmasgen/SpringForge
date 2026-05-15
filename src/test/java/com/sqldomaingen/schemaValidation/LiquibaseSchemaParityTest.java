package com.sqldomaingen.schemaValidation;

import com.sqldomaingen.generator.LiquibaseGenerator;
import com.sqldomaingen.model.Table;
import com.sqldomaingen.parser.CreateTableDefinition;
import com.sqldomaingen.parser.PostgreSQLBaseListener;
import com.sqldomaingen.parser.PostgreSQLParser;
import com.sqldomaingen.parser.SQLParser;
import com.sqldomaingen.util.Constants;
import com.sqldomaingen.validation.LiquibaseSchemaParityValidation;
import lombok.Getter;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class LiquibaseSchemaParityTest {

    @TempDir
    Path tempDir;

    /**
     * Validates generated Liquibase changelogs against the parsed SQL schema.
     *
     * @throws Exception if parsing, generation, or validation fails unexpectedly
     */
    @Test
    void shouldGenerateLiquibaseFilesThatMatchParsedSqlSchema() throws Exception {
        Path schemaPath = Constants.SCHEMA_PATH;

        if (!Files.exists(schemaPath)) {
            throw new AssertionError("Missing schema file: " + schemaPath.toAbsolutePath());
        }

        List<Table> parsedTables = parseTables(Files.readString(schemaPath));

        LiquibaseGenerator liquibaseGenerator = new LiquibaseGenerator();
        liquibaseGenerator.generateLiquibaseFiles(
                tempDir.toString(),
                parsedTables,
                true,
                resolveLiquibaseAuthor()
        );

        Path liquibaseRoot = tempDir.resolve("src/main/resources/db/migration/changelogs/v0.1.0");

        LiquibaseSchemaParityValidation validation =
                new LiquibaseSchemaParityValidation(
                        schemaPath,
                        liquibaseRoot,
                        parsedTables
                );

        List<String> violations = validation.validate();

        if (!violations.isEmpty()) {
            throw new AssertionError(buildViolationReport(violations));
        }
    }

    /**
     * Resolves the Liquibase author used by generated changelogs.
     *
     * @return resolved Liquibase author
     */
    private String resolveLiquibaseAuthor() {
        String liquibaseAuthor = System.getProperty("liquibase.author");

        if (liquibaseAuthor == null || liquibaseAuthor.isBlank()) {
            liquibaseAuthor = System.getenv("LIQUIBASE_AUTHOR");
        }

        if (liquibaseAuthor == null || liquibaseAuthor.isBlank()) {
            return "kosmasgenaris@knowledge.gr";
        }

        return liquibaseAuthor;
    }

    /**
     * Parses CREATE TABLE and CREATE INDEX statements from the SQL schema file.
     *
     * @param sql raw schema SQL
     * @return parsed tables enriched with index definitions
     */
    private List<Table> parseTables(String sql) {
        SQLParser sqlParser = new SQLParser();
        sqlParser.setSqlContent(sql);

        ParseTree parseTree = sqlParser.parseTreeFromSQL();

        CreateTableStatementCollector collector = new CreateTableStatementCollector();
        ParseTreeWalker.DEFAULT.walk(collector, parseTree);

        CreateTableDefinition createTableDefinition = new CreateTableDefinition();
        Map<String, Table> tableMap = createTableDefinition.parseAllTables(collector.getCreateTableStatements());

        PostgreSQLParser.SqlScriptContext sqlScriptContext = (PostgreSQLParser.SqlScriptContext) parseTree;
        com.sqldomaingen.parser.CreateIndexDefinition createIndexDefinition =
                new com.sqldomaingen.parser.CreateIndexDefinition();

        for (PostgreSQLParser.CreateIndexStatementContext indexContext : sqlScriptContext.createIndexStatement()) {
            com.sqldomaingen.model.IndexDefinition indexDefinition =
                    createIndexDefinition.processCreateIndex(indexContext);

            if (indexDefinition == null || indexDefinition.getTableName() == null || indexDefinition.getTableName().isBlank()) {
                continue;
            }

            String normalizedIndexTableName = normalizeParsedIndexTableName(indexDefinition.getTableName());

            Table table = tableMap.get(normalizedIndexTableName);
            if (table == null) {
                continue;
            }

            if (table.getIndexes() == null) {
                table.setIndexes(new ArrayList<>());
            }

            table.getIndexes().add(indexDefinition);
        }

        return new ArrayList<>(tableMap.values());
    }

    /**
     * Normalizes an index table reference so it matches parsed table map keys.
     *
     * @param tableName raw index table reference
     * @return normalized table reference
     */
    private String normalizeParsedIndexTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return "";
        }

        return tableName.trim().replace("\"", "");
    }

    /**
     * Builds the final validation report.
     *
     * @param violations collected violations
     * @return formatted report
     */
    private String buildViolationReport(List<String> violations) {
        StringBuilder builder = new StringBuilder();

        builder.append(System.lineSeparator());
        builder.append("==================================================").append(System.lineSeparator());
        builder.append("LIQUIBASE / SCHEMA PARITY REPORT").append(System.lineSeparator());
        builder.append("==================================================").append(System.lineSeparator());

        for (int index = 0; index < violations.size(); index++) {
            builder.append(index + 1)
                    .append(". ")
                    .append(violations.get(index))
                    .append(System.lineSeparator());
        }

        builder.append("==================================================").append(System.lineSeparator());
        builder.append("Total issues: ").append(violations.size()).append(System.lineSeparator());
        builder.append("==================================================");

        return builder.toString();
    }

    /**
     * Collects CREATE TABLE statement contexts from the parsed SQL tree.
     */
    @Getter
    private static class CreateTableStatementCollector extends PostgreSQLBaseListener {

        /**
         * Collected CREATE TABLE statement contexts in encounter order.
         */
        private final List<PostgreSQLParser.CreateTableStatementContext> createTableStatements = new ArrayList<>();

        /**
         * Stores each encountered CREATE TABLE statement context.
         *
         * @param context the current CREATE TABLE statement context
         */
        @Override
        public void enterCreateTableStatement(PostgreSQLParser.CreateTableStatementContext context) {
            createTableStatements.add(context);
        }
    }
}