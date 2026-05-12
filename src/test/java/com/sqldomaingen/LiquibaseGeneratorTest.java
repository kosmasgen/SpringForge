package com.sqldomaingen;

import com.sqldomaingen.model.IndexDefinition;
import com.sqldomaingen.generator.LiquibaseGenerator;
import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiquibaseGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldGenerateMainXmlWithParentsBeforeChildren() throws Exception {
        Table languages = table("pep_schema.languages");
        Table companyProfile = table("pep_schema.company_profile");
        Table companyProfileI18n = table(
                "pep_schema.company_profile_i18n",
                foreignKeyColumn("company_profile_id", "pep_schema.company_profile"),
                foreignKeyColumn("language_id", "pep_schema.languages")
        );

        LiquibaseGenerator generator = new LiquibaseGenerator();

        generator.generateLiquibaseFiles(
                tempDir.toString(),
                List.of(companyProfileI18n, companyProfile, languages),
                true
        );

        Path mainXml = tempDir.resolve("src/main/resources/db/migration/changelogs/v0.1.0/main.xml");
        String content = Files.readString(mainXml);

        int languagesIndex = content.indexOf("languages.xml");
        int companyProfileIndex = content.indexOf("companyProfile.xml");
        int companyProfileI18nIndex = content.indexOf("companyProfileI18n.xml");

        assertTrue(languagesIndex >= 0, "languages include missing");
        assertTrue(companyProfileIndex >= 0, "companyProfile include missing");
        assertTrue(companyProfileI18nIndex >= 0, "companyProfileI18n include missing");

        assertTrue(
                languagesIndex < companyProfileI18nIndex,
                "languages must come before companyProfileI18n"
        );
        assertTrue(
                companyProfileIndex < companyProfileI18nIndex,
                "companyProfile must come before companyProfileI18n"
        );
    }

    @Test
    void shouldGenerateSchemaFreeCamelCaseIncludeNames() throws Exception {
        Table auditTrail = table("pep_schema.audit_trail");

        LiquibaseGenerator generator = new LiquibaseGenerator();

        generator.generateLiquibaseFiles(
                tempDir.toString(),
                List.of(auditTrail),
                true
        );

        Path mainXml = tempDir.resolve("src/main/resources/db/migration/changelogs/v0.1.0/main.xml");
        String content = Files.readString(mainXml);

        assertTrue(
                content.contains("<include file=\"auditTrail.xml\" relativeToChangelogFile=\"true\" />"),
                "Expected schema-free camelCase include file name"
        );
        assertFalse(
                content.contains("pep_schema.audit_trail.xml"),
                "Schema-qualified snake_case include file name must not appear"
        );
    }


    @Test
    void shouldGenerateMasterXmlPointingToVersionMain() throws Exception {
        LiquibaseGenerator generator = new LiquibaseGenerator();

        generator.generateLiquibaseFiles(tempDir.toString(), List.of(), true);

        Path masterXml = tempDir.resolve("src/main/resources/db/migration/changelog-master.xml");
        String content = Files.readString(masterXml);

        assertTrue(
                content.contains("<include file=\"changelogs/v0.1.0/main.xml\" relativeToChangelogFile=\"true\" />")
        );
    }

    @Test
    void shouldGenerateJsonbDefaultCorrectly() throws Exception {
        Column photos = new Column();
        photos.setName("photos");
        photos.setSqlType("JSONB");
        photos.setDefaultValue("'[]'::jsonb");

        Table table = table("public.conservation_intervention", photos);

        LiquibaseGenerator generator = new LiquibaseGenerator();

        generator.generateLiquibaseFiles(
                tempDir.toString(),
                List.of(table),
                true
        );

        Path xml = tempDir.resolve("src/main/resources/db/migration/changelogs/v0.1.0/conservationIntervention.xml");
        String content = Files.readString(xml);

        assertTrue(
                content.contains("defaultValueComputed=\"'[]'::jsonb\""),
                "JSONB default must keep quotes and cast"
        );

        assertFalse(
                content.contains("'[]::jsonb'"),
                "Broken JSONB default must not be generated"
        );
    }

    @Test
    void shouldRepairJsonbDefaultWhenParserDropsLiteralQuotes() throws Exception {
        Column photos = new Column();
        photos.setName("photos");
        photos.setSqlType("JSONB");
        photos.setDefaultValue("[]::jsonb");

        Table table = table("public.conservation_intervention", photos);

        LiquibaseGenerator generator = new LiquibaseGenerator();

        generator.generateLiquibaseFiles(
                tempDir.toString(),
                List.of(table),
                true
        );

        Path xml = tempDir.resolve("src/main/resources/db/migration/changelogs/v0.1.0/conservationIntervention.xml");
        String content = Files.readString(xml);

        // DEBUG LOGS
        System.out.println("=== DEFAULT VALUE FROM COLUMN ===");
        System.out.println(photos.getDefaultValue());

        System.out.println("=== GENERATED XML ===");
        System.out.println(content);

        assertTrue(
                content.contains("defaultValueComputed=\"'[]'::jsonb\""),
                "JSONB default must restore missing quotes before cast"
        );

        assertFalse(
                content.contains("defaultValueComputed=\"[]::jsonb\""),
                "Broken unquoted JSONB default must not be generated"
        );

        assertFalse(
                content.contains("defaultValue=\"[]::jsonb\""),
                "JSONB cast must not be generated as plain defaultValue"
        );
    }

    @Test
    void shouldGenerateRawSqlForPartialExpressionIndex() throws Exception {
        Column id = new Column();
        id.setName("id");
        id.setSqlType("int8");
        id.setPrimaryKey(true);
        id.setNullable(false);

        Column email = new Column();
        email.setName("email");
        email.setSqlType("varchar(255)");

        Column deletedAt = new Column();
        deletedAt.setName("deleted_at");
        deletedAt.setSqlType("timestamp");

        IndexDefinition indexDefinition = new IndexDefinition();
        indexDefinition.setName("uq_test_email_active");
        indexDefinition.setTableName("public.index_expression_test");
        indexDefinition.setUsingMethod("btree");
        indexDefinition.setUnique(true);
        indexDefinition.setWhereClause("deleted_at IS NULL");
        indexDefinition.setColumns(List.of("lower((email)::text)"));

        Table table = table("public.index_expression_test", id, email, deletedAt);
        table.setIndexes(List.of(indexDefinition));

        LiquibaseGenerator generator = new LiquibaseGenerator();

        generator.generateLiquibaseFiles(
                tempDir.toString(),
                List.of(table),
                true
        );

        Path xml = tempDir.resolve("src/main/resources/db/migration/changelogs/v0.1.0/indexExpressionTest.xml");
        String content = Files.readString(xml);

        assertTrue(
                content.contains("CREATE UNIQUE INDEX uq_test_email_active ON public.index_expression_test USING btree (lower((email)::text)) WHERE deleted_at IS NULL;"),
                "Partial expression index must be generated as raw SQL with WHERE clause"
        );

        assertFalse(
                content.contains("<createIndex"),
                "Partial expression index must not be generated as Liquibase createIndex"
        );
    }

    @Test
    void shouldGenerateRawSqlForTrigramExpressionIndex() throws Exception {
        Column id = new Column();
        id.setName("id");
        id.setSqlType("int8");
        id.setPrimaryKey(true);
        id.setNullable(false);

        Column address = new Column();
        address.setName("address");
        address.setSqlType("varchar(255)");

        IndexDefinition indexDefinition = new IndexDefinition();
        indexDefinition.setName("idx_locations_address_lower_trgm");
        indexDefinition.setTableName("public.locations");
        indexDefinition.setUsingMethod("gin");
        indexDefinition.setColumns(List.of("lower((address)::text) gin_trgm_ops"));

        Table table = table("public.locations", id, address);
        table.setIndexes(List.of(indexDefinition));

        LiquibaseGenerator generator = new LiquibaseGenerator();

        generator.generateLiquibaseFiles(
                tempDir.toString(),
                List.of(table),
                true
        );

        Path xml = tempDir.resolve("src/main/resources/db/migration/changelogs/v0.1.0/locations.xml");
        String content = Files.readString(xml);

        assertTrue(
                content.contains("CREATE INDEX idx_locations_address_lower_trgm ON public.locations USING gin (lower((address)::text) gin_trgm_ops);"),
                "Trigram expression index must be generated as raw SQL"
        );

        assertFalse(
                content.contains("Skipped index"),
                "Trigram expression index must not be skipped"
        );

        assertTrue(
                generator.getGenerationWarnings().isEmpty(),
                "Trigram expression index must not produce generation warning"
        );
    }

    private Table table(String tableName, Column... columns) {
        Table table = new Table();
        table.setName(tableName);
        table.setColumns(new ArrayList<>(List.of(columns)));
        return table;
    }

    private Column foreignKeyColumn(String columnName, String referencedTable) {
        Column column = new Column();
        column.setName(columnName);
        column.setForeignKey(true);
        column.setReferencedTable(referencedTable);
        column.setReferencedColumn("id");
        return column;
    }
}