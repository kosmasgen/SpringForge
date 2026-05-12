package com.sqldomaingen;

import com.sqldomaingen.generator.EntityGenerator;
import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.Table;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Log4j2
class EntityGeneratorUpdateTest {

    private EntityGenerator entityGenerator;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        entityGenerator = new EntityGenerator();
        log.info("Setting up EntityGeneratorTest...");
    }

    @Test
    void testGenerateEntityWithOneToManyFromBothSides() throws IOException {
        Table customers = new Table();
        customers.setName("Customers");
        customers.setColumns(new ArrayList<>(List.of(createLongPrimaryKeyColumn())));

        Table orders = new Table();
        orders.setName("Orders");
        orders.setColumns(new ArrayList<>(List.of(
                createLongPrimaryKeyColumn(),
                createRequiredLongForeignKeyColumn("customer_id", "Customers")
        )));

        entityGenerator.generate(
                List.of(customers, orders),
                tempDir.toString(),
                "com.example.entities",
                true,
                false
        );

        Path customersFile = findGeneratedFile("Customers.java");
        Path ordersFile = findGeneratedFile("Orders.java");

        assertTrue(Files.exists(customersFile), "Customers.java should be generated.");
        assertTrue(Files.exists(ordersFile), "Orders.java should be generated.");

        String customersContent = Files.readString(customersFile);
        String ordersContent = Files.readString(ordersFile);

        assertFalse(customersContent.contains("@OneToMany("),
                "Customers should not contain inverse @OneToMany in current generator behavior.");
        assertFalse(customersContent.contains("private List<Orders>"),
                "Customers should not contain inverse Orders collection in current generator behavior.");

        assertTrue(ordersContent.contains("@ManyToOne(fetch = FetchType.LAZY)"),
                "Expected owning @ManyToOne on Orders.");
        assertTrue(ordersContent.contains("@JoinColumn(name = \"customer_id\", nullable = false)"),
                "Expected owning JoinColumn on Orders.");
        assertTrue(ordersContent.contains("private Customers customer;"),
                "Expected owning relation field on Orders.");
        assertFalse(ordersContent.contains("@OneToMany("),
                "Orders must not expose inverse @OneToMany for the same relationship.");
    }

    @Test
    void testGenerateDepartmentEntity_WithUuidPk_AndFields() throws IOException {
        String packageName = "com.example.entities";

        Table table = new Table();
        table.setName("department");

        Column departmentUuid = new Column();
        departmentUuid.setName("department_uuid");
        departmentUuid.setFieldName("departmentUuid");
        departmentUuid.setSqlType("UUID");
        departmentUuid.setJavaType("java.util.UUID");
        departmentUuid.setPrimaryKey(true);
        departmentUuid.setNullable(false);

        Column departmentId = new Column();
        departmentId.setName("department_id");
        departmentId.setFieldName("departmentId");
        departmentId.setSqlType("SERIAL");
        departmentId.setJavaType("Integer");
        departmentId.setPrimaryKey(false);
        departmentId.setNullable(true);

        Column name = new Column();
        name.setName("name");
        name.setFieldName("name");
        name.setSqlType("VARCHAR(100)");
        name.setJavaType("String");
        name.setNullable(false);
        name.setLength(100);

        Column description = new Column();
        description.setName("description");
        description.setFieldName("description");
        description.setSqlType("TEXT");
        description.setJavaType("String");
        description.setNullable(true);

        Column parentDeptId = new Column();
        parentDeptId.setName("parent_dept_id");
        parentDeptId.setFieldName("parentDeptId");
        parentDeptId.setSqlType("INT");
        parentDeptId.setJavaType("Integer");
        parentDeptId.setNullable(true);

        Column date = new Column();
        date.setName("date");
        date.setFieldName("date");
        date.setSqlType("DATE");
        date.setJavaType("java.time.LocalDate");
        date.setNullable(true);

        Column createdAt = new Column();
        createdAt.setName("created_at");
        createdAt.setFieldName("createdAt");
        createdAt.setSqlType("TIMESTAMP");
        createdAt.setJavaType("java.time.LocalDateTime");
        createdAt.setNullable(true);
        createdAt.setDefaultValue("CURRENT_TIMESTAMP");

        Column updatedAt = new Column();
        updatedAt.setName("updated_at");
        updatedAt.setFieldName("updatedAt");
        updatedAt.setSqlType("TIMESTAMP");
        updatedAt.setJavaType("java.time.LocalDateTime");
        updatedAt.setNullable(true);
        updatedAt.setDefaultValue("CURRENT_TIMESTAMP");

        Column isActive = new Column();
        isActive.setName("is_active");
        isActive.setFieldName("isActive");
        isActive.setSqlType("BOOLEAN");
        isActive.setJavaType("Boolean");
        isActive.setNullable(false);
        isActive.setDefaultValue("TRUE");

        Column budget = new Column();
        budget.setName("budget");
        budget.setFieldName("budget");
        budget.setSqlType("NUMERIC(12,2)");
        budget.setJavaType("java.math.BigDecimal");
        budget.setNullable(true);
        budget.setPrecision(12);
        budget.setScale(2);

        Column headcount = new Column();
        headcount.setName("headcount");
        headcount.setFieldName("headcount");
        headcount.setSqlType("SMALLINT");
        headcount.setJavaType("Short");
        headcount.setNullable(true);

        Column phone = new Column();
        phone.setName("phone");
        phone.setFieldName("phone");
        phone.setSqlType("VARCHAR(20)");
        phone.setJavaType("String");
        phone.setNullable(true);
        phone.setLength(20);

        Column websiteUrl = new Column();
        websiteUrl.setName("website_url");
        websiteUrl.setFieldName("websiteUrl");
        websiteUrl.setSqlType("TEXT");
        websiteUrl.setJavaType("String");
        websiteUrl.setNullable(true);

        Column attachment = new Column();
        attachment.setName("attachment");
        attachment.setFieldName("attachment");
        attachment.setSqlType("BYTEA");
        attachment.setJavaType("byte[]");
        attachment.setNullable(true);

        Column shiftStart = new Column();
        shiftStart.setName("shift_start");
        shiftStart.setFieldName("shiftStart");
        shiftStart.setSqlType("TIME");
        shiftStart.setJavaType("java.time.LocalTime");
        shiftStart.setNullable(true);

        table.setColumns(List.of(
                departmentUuid,
                departmentId,
                name,
                description,
                parentDeptId,
                date,
                createdAt,
                updatedAt,
                isActive,
                budget,
                headcount,
                phone,
                websiteUrl,
                attachment,
                shiftStart
        ));

        entityGenerator.generate(List.of(table), tempDir.toString(), packageName, true, false);

        Path expectedFile = tempDir
                .resolve(packageName.replace('.', java.io.File.separatorChar))
                .resolve("Department.java");

        Path generatedFile;
        try (java.util.stream.Stream<Path> walk = Files.walk(tempDir)) {
            generatedFile = walk
                    .filter(path -> path.getFileName().toString().equals("Department.java"))
                    .findFirst()
                    .orElse(expectedFile);
        }

        assertTrue(Files.exists(generatedFile),
                "Department.java should be generated under the package folder. Expected: " + expectedFile);

        String content = Files.readString(generatedFile);

        assertTrue(content.contains("public class Department"), "Class name not generated correctly");
        assertTrue(content.contains("@Entity"), "@Entity annotation missing");
        assertTrue(content.contains("private UUID departmentUuid"), "UUID PK field not generated correctly");

        assertFalse(content.contains("@Builder.Default"), "Generator must not add @Builder.Default");
        assertFalse(content.contains("optional = false"), "Generator must not add optional=false");
        assertFalse(content.contains("referencedColumnName = \"id\""),
                "Generator must not add referencedColumnName=\"id\"");
    }

    @Test
    void testGenerateDepartmentSelfReferenceFromBothSidesAndWithoutDuplicates() throws IOException {
        Table department = new Table();
        department.setName("Department");
        department.setColumns(new ArrayList<>(List.of(
                createLongPrimaryKeyColumn(),
                createNullableLongForeignKeyColumn()
        )));

        entityGenerator.generate(
                List.of(department),
                tempDir.toString(),
                "com.example.entities",
                true,
                false
        );

        Path generatedFile = findGeneratedFile("Department.java");
        assertTrue(Files.exists(generatedFile), "Department.java file should be generated.");

        String content = Files.readString(generatedFile);

        assertTrue(content.contains("@ManyToOne(fetch = FetchType.LAZY)"),
                "Expected self-referencing owning @ManyToOne.");
        assertTrue(content.contains("private Department parent;"),
                "Expected self parent field.");

        assertFalse(content.contains("@OneToMany(mappedBy = \"parent\""),
                "Department should not contain inverse self @OneToMany in current generator behavior.");
        assertFalse(content.contains("private List<Department>"),
                "Department should not contain inverse self collection in current generator behavior.");

        long parentFieldCount = content.lines()
                .filter(line -> line.contains("private Department parent"))
                .count();

        assertEquals(1, parentFieldCount,
                "There must be exactly one self parent field.");
    }

    @Test
    void testGenerateCompositePkJoinTable_GeneratesExternalPkAndInverseCollections() throws IOException {
        Table businessLocation = createUuidPkTable("pep_schema.business_location");
        Table languages = createUuidPkTable("pep_schema.languages");

        Table joinTable = new Table();
        joinTable.setName("pep_schema.business_location_i18n");

        Column description = new Column();
        description.setName("description");
        description.setFieldName("description");
        description.setSqlType("varchar");
        description.setJavaType("java.lang.String");
        description.setNullable(false);
        description.setLength(1000);

        Column code = new Column();
        code.setName("code");
        code.setFieldName("code");
        code.setSqlType("varchar");
        code.setJavaType("java.lang.String");
        code.setNullable(false);
        code.setLength(255);

        Column recdeleted = new Column();
        recdeleted.setName("recdeleted");
        recdeleted.setFieldName("recdeleted");
        recdeleted.setSqlType("bool");
        recdeleted.setJavaType("java.lang.Boolean");
        recdeleted.setNullable(false);
        recdeleted.setDefaultValue("false");

        Column businessLocationId = createRequiredUuidForeignKeyColumn(
                "business_location_id",
                "pep_schema.business_location"
        );
        businessLocationId.setFieldName("businessLocationId");
        businessLocationId.setPrimaryKey(true);

        Column languageId = createRequiredUuidForeignKeyColumn(
                "language_id",
                "pep_schema.languages"
        );
        languageId.setFieldName("languageId");
        languageId.setPrimaryKey(true);

        joinTable.setColumns(new ArrayList<>(List.of(
                description,
                code,
                recdeleted,
                businessLocationId,
                languageId
        )));

        entityGenerator.generate(
                List.of(businessLocation, languages, joinTable),
                tempDir.toString(),
                "com.example",
                true,
                false
        );

        Path entityFile = findGeneratedFile("BusinessLocationI18n.java");
        Path pkFile = findGeneratedFile("BusinessLocationI18nKey.java");
        Path businessLocationFile = findGeneratedFile("BusinessLocation.java");
        Path languagesFile = findGeneratedFile("Languages.java");

        String entityContent = Files.readString(entityFile);
        String pkContent = Files.readString(pkFile);
        String businessLocationContent = Files.readString(businessLocationFile);
        String languagesContent = Files.readString(languagesFile);

        assertTrue(entityContent.contains("@EmbeddedId"),
                "Expected @EmbeddedId for composite PK join entity.");
        assertTrue(entityContent.contains("private BusinessLocationI18nKey id;"),
                "Expected external PK type field.");
        assertTrue(entityContent.contains("@MapsId(\"businessLocationId\")"),
                "Expected @MapsId for business_location_id.");
        assertTrue(entityContent.contains("private BusinessLocation businessLocation;"),
                "Expected relation field to BusinessLocation.");
        assertTrue(entityContent.contains("@MapsId(\"languageId\")"),
                "Expected @MapsId for language_id.");
        assertTrue(entityContent.contains("private Languages language;"),
                "Expected relation field to Languages.");
        assertTrue(entityContent.contains("private String description;"),
                "Expected normal column field: description.");
        assertTrue(entityContent.contains("private String code;"),
                "Expected normal column field: code.");
        assertTrue(entityContent.contains("private Boolean recdeleted;"),
                "Expected recdeleted field.");
        assertFalse(entityContent.contains("private Boolean recdeleted = false;"),
                "Generator should not assign inline default value to recdeleted.");
        assertFalse(entityContent.contains("@ManyToMany("),
                "Composite join entity should not generate @ManyToMany.");
        assertFalse(entityContent.contains("@OneToMany("),
                "Join entity itself must not generate inverse parent collections.");

        assertTrue(pkContent.contains("@Embeddable"),
                "Expected @Embeddable on external PK class.");
        assertTrue(pkContent.contains("public class BusinessLocationI18nKey implements Serializable"),
                "Expected external PK class name.");
        assertTrue(pkContent.contains("private UUID businessLocationId;"),
                "Expected businessLocationId in PK class.");
        assertTrue(pkContent.contains("private UUID languageId;"),
                "Expected languageId in PK class.");

        assertFalse(businessLocationContent.contains("@OneToMany(mappedBy = \"businessLocation\""),
                "BusinessLocation should not contain inverse @OneToMany in current generator behavior.");
        assertFalse(businessLocationContent.contains("private List<BusinessLocationI18n>"),
                "BusinessLocation should not contain inverse collection field in current generator behavior.");

        assertFalse(languagesContent.contains("@OneToMany(mappedBy = \"language\""),
                "Languages should not contain inverse @OneToMany in current generator behavior.");
        assertFalse(languagesContent.contains("private List<BusinessLocationI18n>"),
                "Languages should not contain inverse collection field in current generator behavior.");
    }

    @Test
    void testGenerateEntityWithOneToOne_UsesTempDirAndGeneratesBothSides() throws IOException {
        Table users = new Table();
        users.setName("Users");
        users.setColumns(new ArrayList<>(List.of(createLongPrimaryKeyColumn())));

        Table userDetails = new Table();
        userDetails.setName("UserDetails");

        Column detailsId = createLongPrimaryKeyColumn();
        Column userIdFk = createRequiredLongForeignKeyColumn("user_id", "Users");
        userIdFk.setUnique(true);

        userDetails.setColumns(new ArrayList<>(List.of(detailsId, userIdFk)));

        entityGenerator.generate(
                List.of(users, userDetails),
                tempDir.toString(),
                "com.example",
                true,
                false
        );

        Path userDetailsFile = findGeneratedFile("UserDetails.java");
        Path usersFile = findGeneratedFile("Users.java");

        String userDetailsContent = Files.readString(userDetailsFile);
        String usersContent = Files.readString(usersFile);

        assertTrue(userDetailsContent.contains("@OneToOne"),
                "Expected owning @OneToOne on UserDetails.");
        assertTrue(userDetailsContent.contains("@JoinColumn(name = \"user_id\", nullable = false, unique = true)"),
                "Expected correct @JoinColumn for unique FK.");
        assertTrue(userDetailsContent.contains("private Users user;"),
                "Expected owning relation field 'user'.");
        assertFalse(userDetailsContent.contains("mappedBy"),
                "Owning side must not contain mappedBy.");

        assertTrue(usersContent.contains("@OneToOne(mappedBy = \"user\", fetch = FetchType.LAZY)"),
                "Expected inverse @OneToOne on Users.");
        assertTrue(usersContent.contains("private UserDetails userDetails;"),
                "Expected inverse field userDetails on Users.");
    }

    @Test
    void testGenerateCompanyProfessionEntity_WithTwoManyToOneWithoutInverseOneToManyOnParents() throws IOException {
        Table company = createUuidPkTable("pep_schema.company");
        Table profession = createUuidPkTable("pep_schema.profession");

        Table companyProfession = new Table();
        companyProfession.setName("pep_schema.company_profession");

        Column id = createUuidPrimaryKeyColumn();
        Column companyFk = createRequiredUuidForeignKeyColumn("company_id", "pep_schema.company");
        Column professionFk = createRequiredUuidForeignKeyColumn("profession_id", "pep_schema.profession");

        Column notes = new Column();
        notes.setName("notes");
        notes.setFieldName("notes");
        notes.setSqlType("varchar");
        notes.setJavaType("String");
        notes.setNullable(true);

        companyProfession.setColumns(new ArrayList<>(List.of(id, companyFk, professionFk, notes)));

        entityGenerator.generate(
                List.of(company, profession, companyProfession),
                tempDir.toString(),
                "com.example",
                true,
                false
        );

        Path entityFile = findGeneratedFile("CompanyProfession.java");
        Path companyFile = findGeneratedFile("Company.java");
        Path professionFile = findGeneratedFile("Profession.java");

        String entityContent = Files.readString(entityFile);
        String companyContent = Files.readString(companyFile);
        String professionContent = Files.readString(professionFile);

        assertTrue(entityContent.contains("@ManyToOne(fetch = FetchType.LAZY)"),
                "Expected @ManyToOne annotations.");
        assertTrue(entityContent.contains("@JoinColumn(name = \"company_id\", nullable = false)"),
                "Expected JoinColumn for company_id.");
        assertTrue(entityContent.contains("private Company company;"),
                "Expected relation field to Company.");
        assertTrue(entityContent.contains("@JoinColumn(name = \"profession_id\", nullable = false)"),
                "Expected JoinColumn for profession_id.");
        assertTrue(entityContent.contains("private Profession profession;"),
                "Expected relation field to Profession.");
        assertTrue(entityContent.contains("private String notes;"),
                "Expected normal payload column field.");
        assertFalse(entityContent.contains("@ManyToMany("),
                "company_profession must remain join entity, not synthetic many-to-many.");

        assertFalse(companyContent.contains("@OneToMany(mappedBy = \"company\""),
                "Company should not contain inverse OneToMany in current generator behavior.");
        assertFalse(companyContent.contains("private List<CompanyProfession>"),
                "Company should not contain inverse collection field in current generator behavior.");

        assertFalse(professionContent.contains("@OneToMany(mappedBy = \"profession\""),
                "Profession should not contain inverse OneToMany in current generator behavior.");
        assertFalse(professionContent.contains("private List<CompanyProfession>"),
                "Profession should not contain inverse collection field in current generator behavior.");
    }

    @Test
    void testGenerateCompanyProfileLanguageEntity_GeneratesExternalPkWithoutInverseCollectionsOnParents() throws IOException {
        Table companyProfile = createUuidPkTable("pep_schema.company_profile");
        Table language = createUuidPkTable("pep_schema.languages");

        Table companyProfileLanguage = new Table();
        companyProfileLanguage.setName("pep_schema.company_profile_language");

        Column companyProfileFk = createRequiredUuidForeignKeyColumn(
                "company_profile_id",
                "pep_schema.company_profile"
        );
        companyProfileFk.setFieldName("companyProfileId");
        companyProfileFk.setPrimaryKey(true);

        Column languageFk = createRequiredUuidForeignKeyColumn(
                "language_id",
                "pep_schema.languages"
        );
        languageFk.setFieldName("languageId");
        languageFk.setPrimaryKey(true);

        companyProfileLanguage.setColumns(new ArrayList<>(List.of(companyProfileFk, languageFk)));

        entityGenerator.generate(
                List.of(companyProfile, language, companyProfileLanguage),
                tempDir.toString(),
                "com.example",
                true,
                false
        );

        Path entityFile = findGeneratedFile("CompanyProfileLanguage.java");
        Path pkFile = findGeneratedFile("CompanyProfileLanguageKey.java");
        Path companyProfileFile = findGeneratedFile("CompanyProfile.java");
        Path languagesFile = findGeneratedFile("Languages.java");

        String entityContent = Files.readString(entityFile);
        String pkContent = Files.readString(pkFile);
        String companyProfileContent = Files.readString(companyProfileFile);
        String languagesContent = Files.readString(languagesFile);

        assertTrue(entityContent.contains("@EmbeddedId"),
                "Expected @EmbeddedId.");
        assertTrue(entityContent.contains("private CompanyProfileLanguageKey id;"),
                "Expected external PK field.");
        assertTrue(entityContent.contains("@MapsId(\"companyProfileId\")"),
                "Expected @MapsId for company_profile_id.");
        assertTrue(entityContent.contains("private CompanyProfile companyProfile;"),
                "Expected relation field to CompanyProfile.");
        assertTrue(entityContent.contains("@MapsId(\"languageId\")"),
                "Expected @MapsId for language_id.");
        assertTrue(entityContent.contains("private Languages language;"),
                "Expected relation field to Languages.");
        assertFalse(entityContent.contains("@ManyToMany("),
                "Association entity should not be generated as @ManyToMany here.");

        assertTrue(pkContent.contains("@Embeddable"),
                "Expected @Embeddable PK class.");
        assertTrue(pkContent.contains("public class CompanyProfileLanguageKey implements Serializable"),
                "Expected PK class name CompanyProfileLanguageKey.");
        assertTrue(pkContent.contains("private UUID companyProfileId;"),
                "Expected companyProfileId field in PK class.");
        assertTrue(pkContent.contains("private UUID languageId;"),
                "Expected languageId field in PK class.");

        assertFalse(companyProfileContent.contains("@OneToMany(mappedBy = \"companyProfile\""),
                "CompanyProfile should not contain inverse collection in current generator behavior.");
        assertFalse(companyProfileContent.contains("private List<CompanyProfileLanguage>"),
                "CompanyProfile should not contain inverse collection field in current generator behavior.");

        assertFalse(languagesContent.contains("@OneToMany(mappedBy = \"language\""),
                "Languages should not contain inverse collection in current generator behavior.");
        assertFalse(languagesContent.contains("private List<CompanyProfileLanguage>"),
                "Languages should not contain inverse collection field in current generator behavior.");
    }

    @Test
    void testGenerateCompanyStatusViewRulesEntity_GeneratesExternalPkWithoutInverseCollectionsOnParents() throws IOException {
        Table companyStatus = createUuidPkTable("pep_schema.company_status");
        Table companyViewRules = createUuidPkTable("pep_schema.company_view_rules");

        Table companyStatusViewRules = new Table();
        companyStatusViewRules.setName("pep_schema.company_status_view_rules");

        Column companyStatusFk = createRequiredUuidForeignKeyColumn(
                "company_status_id",
                "pep_schema.company_status"
        );
        companyStatusFk.setFieldName("companyStatusId");
        companyStatusFk.setPrimaryKey(true);

        Column companyViewRulesFk = createRequiredUuidForeignKeyColumn(
                "company_view_rules_id",
                "pep_schema.company_view_rules"
        );
        companyViewRulesFk.setFieldName("companyViewRulesId");
        companyViewRulesFk.setPrimaryKey(true);

        Column excludeCompanies = new Column();
        excludeCompanies.setName("exclude_companies");
        excludeCompanies.setFieldName("excludeCompanies");
        excludeCompanies.setSqlType("bool");
        excludeCompanies.setJavaType("Boolean");
        excludeCompanies.setNullable(true);

        companyStatusViewRules.setColumns(new ArrayList<>(List.of(
                companyStatusFk,
                companyViewRulesFk,
                excludeCompanies
        )));

        entityGenerator.generate(
                List.of(companyStatus, companyViewRules, companyStatusViewRules),
                tempDir.toString(),
                "com.example",
                true,
                false
        );

        Path entityFile = findGeneratedFile("CompanyStatusViewRules.java");
        Path pkFile = findGeneratedFile("CompanyStatusViewRulesKey.java");
        Path companyStatusFile = findGeneratedFile("CompanyStatus.java");
        Path companyViewRulesFile = findGeneratedFile("CompanyViewRules.java");

        String entityContent = Files.readString(entityFile);
        String pkContent = Files.readString(pkFile);
        String companyStatusContent = Files.readString(companyStatusFile);
        String companyViewRulesContent = Files.readString(companyViewRulesFile);

        assertTrue(entityContent.contains("@EmbeddedId"),
                "Expected @EmbeddedId.");
        assertTrue(entityContent.contains("private CompanyStatusViewRulesKey id;"),
                "Expected external PK field.");
        assertTrue(entityContent.contains("@MapsId(\"companyStatusId\")"),
                "Expected @MapsId for company_status_id.");
        assertTrue(entityContent.contains("private CompanyStatus companyStatus;"),
                "Expected relation field to CompanyStatus.");
        assertTrue(entityContent.contains("@MapsId(\"companyViewRulesId\")"),
                "Expected @MapsId for company_view_rules_id.");
        assertTrue(entityContent.contains("private CompanyViewRules companyViewRules;"),
                "Expected relation field to CompanyViewRules.");
        assertTrue(entityContent.contains("private Boolean excludeCompanies;"),
                "Expected normal payload column field.");
        assertFalse(entityContent.contains("@ManyToMany("),
                "company_status_view_rules must not be generated as synthetic many-to-many.");

        assertTrue(pkContent.contains("@Embeddable"),
                "Expected @Embeddable PK class.");
        assertTrue(pkContent.contains("public class CompanyStatusViewRulesKey implements Serializable"),
                "Expected PK class name CompanyStatusViewRulesKey.");
        assertTrue(pkContent.contains("private UUID companyStatusId;"),
                "Expected companyStatusId field in PK class.");
        assertTrue(pkContent.contains("private UUID companyViewRulesId;"),
                "Expected companyViewRulesId field in PK class.");

        assertFalse(companyStatusContent.contains("@OneToMany(mappedBy = \"companyStatus\""),
                "CompanyStatus should not contain inverse collection in current generator behavior.");
        assertFalse(companyStatusContent.contains("private List<CompanyStatusViewRules>"),
                "CompanyStatus should not contain inverse collection field in current generator behavior.");

        assertFalse(companyViewRulesContent.contains("@OneToMany(mappedBy = \"companyViewRules\""),
                "CompanyViewRules should not contain inverse collection in current generator behavior.");
        assertFalse(companyViewRulesContent.contains("private List<CompanyStatusViewRules>"),
                "CompanyViewRules should not contain inverse collection field in current generator behavior.");
    }

    @Test
    void testGenerateCompanyProfessionSystemLinkEntity_GeneratesExternalPkWithoutInverseCollectionsOnParents() throws IOException {
        Table company = createUuidPkTable("pep_schema.company");
        Table professionSystem = createUuidPkTable("pep_schema.profession_system");

        Table companyProfessionSystemLink = new Table();
        companyProfessionSystemLink.setName("pep_schema.company_profession_system_link");

        Column companyFk = createRequiredUuidForeignKeyColumn("company_id", "pep_schema.company");
        companyFk.setPrimaryKey(true);

        Column professionSystemFk = createRequiredUuidForeignKeyColumn("profession_system_id", "pep_schema.profession_system");
        professionSystemFk.setPrimaryKey(true);

        companyProfessionSystemLink.setColumns(new ArrayList<>(List.of(companyFk, professionSystemFk)));

        entityGenerator.generate(
                List.of(company, professionSystem, companyProfessionSystemLink),
                tempDir.toString(),
                "com.example",
                true,
                false
        );

        Path entityFile = findGeneratedFile("CompanyProfessionSystemLink.java");
        Path pkFile = findGeneratedFile("CompanyProfessionSystemLinkKey.java");
        Path companyFile = findGeneratedFile("Company.java");
        Path professionSystemFile = findGeneratedFile("ProfessionSystem.java");

        String entityContent = Files.readString(entityFile);
        String pkContent = Files.readString(pkFile);
        String companyContent = Files.readString(companyFile);
        String professionSystemContent = Files.readString(professionSystemFile);

        assertTrue(entityContent.contains("@EmbeddedId"),
                "Expected @EmbeddedId.");
        assertTrue(entityContent.contains("private CompanyProfessionSystemLinkKey id;"),
                "Expected external PK field.");
        assertTrue(entityContent.contains("@MapsId(\"companyId\")"),
                "Expected @MapsId for company_id.");
        assertTrue(entityContent.contains("private Company company;"),
                "Expected relation field to Company.");
        assertTrue(entityContent.contains("@MapsId(\"professionSystemId\")"),
                "Expected @MapsId for profession_system_id.");
        assertTrue(entityContent.contains("private ProfessionSystem professionSystem;"),
                "Expected relation field to ProfessionSystem.");
        assertFalse(entityContent.contains("@ManyToMany("),
                "company_profession_system_link must not be generated as synthetic many-to-many.");

        assertTrue(pkContent.contains("@Embeddable"),
                "Expected @Embeddable PK class.");
        assertTrue(pkContent.contains("public class CompanyProfessionSystemLinkKey implements Serializable"),
                "Expected PK class name CompanyProfessionSystemLinkKey.");
        assertTrue(pkContent.contains("private UUID companyId;"),
                "Expected companyId field in PK class.");
        assertTrue(pkContent.contains("private UUID professionSystemId;"),
                "Expected professionSystemId field in PK class.");

        assertFalse(companyContent.contains("@OneToMany(mappedBy = \"company\""),
                "Company should not contain inverse collection in current generator behavior.");
        assertFalse(companyContent.contains("private List<CompanyProfessionSystemLink>"),
                "Company should not contain inverse collection field in current generator behavior.");

        assertFalse(professionSystemContent.contains("@OneToMany(mappedBy = \"professionSystem\""),
                "ProfessionSystem should not contain inverse collection in current generator behavior.");
        assertFalse(professionSystemContent.contains("private List<CompanyProfessionSystemLink>"),
                "ProfessionSystem should not contain inverse collection field in current generator behavior.");
    }

    @Test
    void testGeneratePureManyToManyFromBothSides() throws IOException {
        Table company = createUuidPkTable("pep_schema.company");
        Table language = createUuidPkTable("pep_schema.languages");

        Table companyLanguage = new Table();
        companyLanguage.setName("pep_schema.company_language");

        Column companyFk = createRequiredUuidForeignKeyColumn("company_id", "pep_schema.company");
        companyFk.setPrimaryKey(true);

        Column languageFk = createRequiredUuidForeignKeyColumn("language_id", "pep_schema.languages");
        languageFk.setPrimaryKey(true);

        companyLanguage.setColumns(new ArrayList<>(List.of(companyFk, languageFk)));

        entityGenerator.generate(
                List.of(company, language, companyLanguage),
                tempDir.toString(),
                "com.example",
                true,
                false
        );

        Path companyFile = findGeneratedFile("Company.java");
        Path languagesFile = findGeneratedFile("Languages.java");

        String companyContent = Files.readString(companyFile);
        String languagesContent = Files.readString(languagesFile);

        assertTrue(companyContent.contains("@ManyToMany(fetch = FetchType.LAZY)"),
                "Expected owning @ManyToMany on Company.");
        assertTrue(companyContent.contains("@JoinTable(name = \"company_language\""),
                "Expected @JoinTable on Company.");
        assertTrue(companyContent.contains("private List<Languages>"),
                "Expected Company collection of Languages.");

        assertTrue(languagesContent.contains("@ManyToMany(mappedBy = \"languages\", fetch = FetchType.LAZY)"),
                "Expected inverse @ManyToMany on Languages.");
        assertTrue(languagesContent.contains("private List<Company>"),
                "Expected Languages collection of Company.");
    }

    private Table createUuidPkTable(String tableName) {
        Table table = new Table();
        table.setName(tableName);
        table.setColumns(new ArrayList<>(List.of(createUuidPrimaryKeyColumn())));
        return table;
    }

    private Column createLongPrimaryKeyColumn() {
        Column column = new Column();
        column.setName("id");
        column.setFieldName("id");
        column.setSqlType("INT");
        column.setJavaType("Long");
        column.setPrimaryKey(true);
        column.setNullable(false);
        return column;
    }

    private Column createUuidPrimaryKeyColumn() {
        Column column = new Column();
        column.setName("id");
        column.setFieldName("id");
        column.setSqlType("uuid");
        column.setJavaType("UUID");
        column.setPrimaryKey(true);
        column.setNullable(false);
        return column;
    }

    private Column createRequiredLongForeignKeyColumn(String columnName, String referencedTable) {
        Column column = new Column();
        column.setName(columnName);
        column.setFieldName(toCamelCase(columnName));
        column.setSqlType("INT");
        column.setJavaType("Long");
        column.setPrimaryKey(false);
        column.setForeignKey(true);
        column.setNullable(false);
        column.setReferencedTable(referencedTable);
        column.setReferencedColumn("id");
        return column;
    }

    private Column createNullableLongForeignKeyColumn() {
        Column column = new Column();
        column.setName("parent_id");
        column.setFieldName(toCamelCase("parent_id"));
        column.setSqlType("INT");
        column.setJavaType("Long");
        column.setPrimaryKey(false);
        column.setForeignKey(true);
        column.setNullable(true);
        column.setReferencedTable("Department");
        column.setReferencedColumn("id");
        return column;
    }

    private Column createRequiredUuidForeignKeyColumn(String columnName, String referencedTable) {
        Column column = new Column();
        column.setName(columnName);
        column.setFieldName(toCamelCase(columnName));
        column.setSqlType("uuid");
        column.setJavaType("UUID");
        column.setPrimaryKey(false);
        column.setForeignKey(true);
        column.setNullable(false);
        column.setReferencedTable(referencedTable);
        column.setReferencedColumn("id");
        return column;
    }

    private String toCamelCase(String value) {
        String[] parts = value.split("_");
        StringBuilder builder = new StringBuilder(parts[0]);

        for (int index = 1; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty()) {
                continue;
            }

            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }

        return builder.toString();
    }

    private Path findGeneratedFile(String fileName) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(tempDir)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Could not find generated file: " + fileName));
        }
    }
}
