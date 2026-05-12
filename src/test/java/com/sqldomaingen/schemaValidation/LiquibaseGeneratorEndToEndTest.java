package com.sqldomaingen.schemaValidation;

import com.sqldomaingen.generator.LiquibaseGenerator;
import com.sqldomaingen.model.Table;
import com.sqldomaingen.parser.CreateTableDefinition;
import com.sqldomaingen.parser.PostgreSQLBaseListener;
import com.sqldomaingen.parser.PostgreSQLParser;
import com.sqldomaingen.parser.SQLParser;
import com.sqldomaingen.util.Constants;
import lombok.Getter;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiquibaseGeneratorEndToEndTest {

    @TempDir
    Path tempDir;

    /**
     * Verifies that the generator produces a valid main.xml for the real schema file.
     *
     * <p>The test confirms that:
     * <ul>
     *     <li>the schema file exists and can be parsed,</li>
     *     <li>the Liquibase generator creates the main.xml file,</li>
     *     <li>every declared table is included exactly once, and</li>
     *     <li>parent tables appear before their dependent child tables.</li>
     * </ul>
     *
     * @throws Exception if parsing, file generation, or file reading fails
     */
    @Test
    void shouldGenerateValidMainXmlFromRealSchemaFile() throws Exception {
        Path schemaPath = Constants.SCHEMA_PATH;
        assertTrue(Files.exists(schemaPath), "Missing schema file: " + schemaPath.toAbsolutePath());

        String sql = Files.readString(schemaPath);

        SQLParser sqlParser = new SQLParser();
        sqlParser.setSqlContent(sql);

        ParseTree parseTree = sqlParser.parseTreeFromSQL();

        CreateTableStatementCollector collector = new CreateTableStatementCollector();
        ParseTreeWalker.DEFAULT.walk(collector, parseTree);

        CreateTableDefinition createTableDefinition = new CreateTableDefinition();
        Map<String, Table> tableMap = createTableDefinition.parseAllTables(collector.getCreateTableStatements());

        LiquibaseGenerator liquibaseGenerator = new LiquibaseGenerator();
        liquibaseGenerator.generateLiquibaseFiles(
                tempDir.toString(),
                new ArrayList<>(tableMap.values()),
                true
        );

        Path mainXml = tempDir.resolve(Constants.MAIN_XML_RELATIVE_PATH);
        assertTrue(Files.exists(mainXml), "Generated main.xml was not found: " + mainXml.toAbsolutePath());

        List<String> actualIncludes = extractIncludeFiles(mainXml);
        List<String> declaredTables = extractTableNames(sql);
        Map<String, Set<String>> dependencyGraph = extractDependencies(sql, declaredTables);

        printIncludeOrder(actualIncludes);
        System.out.println("Generated main.xml path: " + mainXml.toAbsolutePath());

        assertEveryDeclaredTableIsIncludedExactlyOnce(actualIncludes, declaredTables);
        assertParentTablesAppearBeforeChildren(actualIncludes, dependencyGraph);
    }


    /**
     * Verifies that every declared table is included exactly once in the generated main.xml.
     * Infrastructure includes are ignored because they are generated only when needed.
     *
     * @param actualIncludes include filenames extracted from generated main.xml
     * @param declaredTables physical table names declared in the source SQL
     */
    private void assertEveryDeclaredTableIsIncludedExactlyOnce(List<String> actualIncludes, List<String> declaredTables) {
        Set<String> infrastructureIncludes = Set.of(
                "audit.xml",
                "extensions.xml"
        );

        List<String> expectedTableIncludes = declaredTables.stream()
                .map(this::toIncludeFileName)
                .distinct()
                .sorted()
                .toList();

        List<String> actualTableIncludes = actualIncludes.stream()
                .filter(include -> !infrastructureIncludes.contains(include))
                .sorted()
                .toList();

        assertEquals(expectedTableIncludes, actualTableIncludes, "Generated table includes do not match declared tables");
        assertEquals(new LinkedHashSet<>(actualIncludes).size(), actualIncludes.size(), "Duplicate includes found");
    }





    /**
     * Extracts table names from the raw SQL file.
     *
     * @param sql raw schema SQL
     * @return declared physical table names
     */
    private List<String> extractTableNames(String sql) {
        Pattern createTablePattern = Pattern.compile(
                "(?i)CREATE\\s+TABLE\\s+([\\w.]+)\\s*\\(",
                Pattern.MULTILINE
        );

        Matcher matcher = createTablePattern.matcher(sql);
        List<String> tableNames = new ArrayList<>();

        while (matcher.find()) {
            tableNames.add(matcher.group(1).trim());
        }

        return tableNames;
    }

    /**
     * Extracts parent dependencies from FOREIGN KEY constraints.
     *
     * @param sql raw schema SQL
     * @param knownTables tables declared in the file
     * @return dependency graph where key is child table and value is its parent tables
     */
    private Map<String, Set<String>> extractDependencies(String sql, List<String> knownTables) {
        Set<String> knownTableSet = new TreeSet<>(knownTables);
        Map<String, Set<String>> dependencyGraph = new HashMap<>();

        for (String tableName : knownTables) {
            dependencyGraph.put(tableName, new TreeSet<>());
        }

        Pattern createTableBlockPattern = Pattern.compile(
                "(?is)CREATE\\s+TABLE\\s+([\\w.]+)\\s*\\((.*?)\\)\\s*;",
                Pattern.DOTALL
        );

        Pattern foreignKeyPattern = Pattern.compile(
                "(?i)FOREIGN\\s+KEY\\s*\\([^)]+\\)\\s*REFERENCES\\s+([\\w.]+)\\s*\\(",
                Pattern.MULTILINE
        );

        Matcher tableMatcher = createTableBlockPattern.matcher(sql);

        while (tableMatcher.find()) {
            String childTable = tableMatcher.group(1).trim();
            String tableBody = tableMatcher.group(2);

            Set<String> parentTables = dependencyGraph.computeIfAbsent(childTable, key -> new TreeSet<>());

            Matcher foreignKeyMatcher = foreignKeyPattern.matcher(tableBody);
            while (foreignKeyMatcher.find()) {
                String referencedTable = foreignKeyMatcher.group(1).trim();

                if (!knownTableSet.contains(referencedTable)) {
                    continue;
                }

                if (childTable.equals(referencedTable)) {
                    continue;
                }

                parentTables.add(referencedTable);
            }
        }

        return dependencyGraph;
    }



    /**
     * Reads include filenames from generated main.xml.
     *
     * @param mainXml generated main.xml path
     * @return ordered include filenames
     * @throws Exception if reading fails
     */
    private List<String> extractIncludeFiles(Path mainXml) throws Exception {
        String content = Files.readString(mainXml);
        Pattern includePattern = Pattern.compile("<include\\s+file=\"([^\"]+)\"");
        Matcher matcher = includePattern.matcher(content);

        List<String> includes = new ArrayList<>();
        while (matcher.find()) {
            includes.add(matcher.group(1));
        }

        return includes;
    }

    /**
     * Converts a physical table name to Liquibase include filename.
     *
     * @param physicalTableName physical table name
     * @return include filename
     */
    private String toIncludeFileName(String physicalTableName) {
        String schemaFreeName = stripSchema(physicalTableName);
        String[] parts = schemaFreeName.split("_");

        StringBuilder builder = new StringBuilder(parts[0]);
        for (int index = 1; index < parts.length; index++) {
            builder.append(capitalize(parts[index]));
        }

        return builder + ".xml";
    }

    /**
     * Removes schema prefix from physical table name.
     *
     * @param tableName physical table name
     * @return schema-free table name
     */
    private String stripSchema(String tableName) {
        int dotIndex = tableName.indexOf('.');
        return dotIndex >= 0 ? tableName.substring(dotIndex + 1) : tableName;
    }

    /**
     * Capitalizes the first character of the given value.
     *
     * @param value input text
     * @return capitalized text
     */
    private String capitalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }

        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }


    /**
     * Collects all CREATE TABLE statement contexts from the parsed SQL tree.
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


    /**
     * Verifies that every parent table include appears before its dependent child table include.
     *
     * @param actualIncludes ordered include filenames extracted from generated main.xml
     * @param dependencyGraph dependency graph where the key is the child table and the value contains its parent tables
     */
    private void assertParentTablesAppearBeforeChildren(
            List<String> actualIncludes,
            Map<String, Set<String>> dependencyGraph
    ) {
        Map<String, Integer> positions = new HashMap<>();

        for (int index = 0; index < actualIncludes.size(); index++) {
            positions.put(actualIncludes.get(index), index);
        }

        for (Map.Entry<String, Set<String>> entry : dependencyGraph.entrySet()) {
            String childTable = entry.getKey();
            String childInclude = toIncludeFileName(childTable);

            for (String parentTable : entry.getValue()) {
                String parentInclude = toIncludeFileName(parentTable);

                Integer parentPosition = positions.get(parentInclude);
                Integer childPosition = positions.get(childInclude);

                assertNotNull(parentPosition, "Missing parent include: " + parentInclude);
                assertNotNull(childPosition, "Missing child include: " + childInclude);
                assertTrue(
                        parentPosition < childPosition,
                        "Parent must appear before child: parent=" + parentInclude + ", child=" + childInclude
                );
            }
        }
    }




    private void printIncludeOrder(List<String> includes) {
        System.out.println();
        System.out.println("==================================================");
        System.out.println("ACTUAL include order from generated main.xml");
        System.out.println("==================================================");

        for (int index = 0; index < includes.size(); index++) {
            System.out.printf("%3d. %s%n", index + 1, includes.get(index));
        }

        System.out.println("Total includes: " + includes.size());
        System.out.println("==================================================");
        System.out.println();
    }


}