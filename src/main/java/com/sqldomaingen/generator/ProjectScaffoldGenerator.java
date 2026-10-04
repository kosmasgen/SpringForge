package com.sqldomaingen.generator;

import com.sqldomaingen.config.GeneratorConfig;
import com.sqldomaingen.util.PackageResolver;
import lombok.extern.log4j.Log4j2;

import com.sqldomaingen.util.GeneratorSupport;
import java.nio.file.Path;
import java.util.Objects;

import static com.sqldomaingen.util.Constants.*;

/**
 * Generates a minimal, buildable Spring Boot Maven project scaffold:
 * pom.xml, Application class, and application.properties
 * Target structure:
 * {outputDir}/pom.xml
 * {outputDir}/src/main/java/{basePackagePath}/Application.java
 * {outputDir}/src/main/resources/application.properties
 * {outputDir}/src/test/java/{basePackagePath}/ (directory only)
 */
@Log4j2
public class ProjectScaffoldGenerator {

    /**
     * Generates the project scaffold under outputDir.
     *
     * @param outputDir target project root directory
     * @param basePackage Java base package (e.g. gr.knowledge.schoolmanagement)
     * @param overwrite if true, overwrites existing files
     */
    public void generateScaffold(String outputDir,
                                 String basePackage,
                                 String defaultSchemaName,
                                 GeneratorConfig generatorConfig,
                                 boolean overwrite) {
        Objects.requireNonNull(generatorConfig, "generatorConfig must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        String out = outputDir.trim();
        String pkg = basePackage.trim();

        if (out.isEmpty()) {
            throw new IllegalArgumentException("outputDir must not be blank");
        }
        if (pkg.isEmpty()) {
            throw new IllegalArgumentException("basePackage must not be blank");
        }

        Path projectRoot = Path.of(out);
        GeneratorSupport.ensureDirectory(projectRoot);

        String artifactId = resolveArtifactId(pkg);

        writePom(projectRoot, pkg, artifactId, overwrite);
        writeApplication(projectRoot, pkg, overwrite);
        createApplicationProperties(projectRoot, artifactId, defaultSchemaName, pkg, generatorConfig, overwrite);
        createMessageProperties(projectRoot, overwrite);
        createMessageResolver(projectRoot, pkg, overwrite);
        writeGitignore(projectRoot, overwrite);

        GeneratorSupport.ensureDirectory(resolveBaseJavaDir(projectRoot, pkg, true));

        copyMavenWrapper(projectRoot);
        log.info("Project scaffold created under: {}", projectRoot.toAbsolutePath());
    }



    /**
     * Resolves the Maven artifactId from the base package.
     *
     * @param basePackage base Java package
     * @return resolved artifactId, or generated-app when blank
     */
    private static String resolveArtifactId(String basePackage) {
        int lastDotIndex = basePackage.lastIndexOf('.');
        String lastSegment = lastDotIndex >= 0
                ? basePackage.substring(lastDotIndex + 1)
                : basePackage;

        String artifactId = lastSegment.trim();
        if (artifactId.isEmpty()) {
            return "generated-app";
        }

        return artifactId;
    }

    /**
     * Generates the Maven pom.xml file for the project.
     *
     * @param projectRoot target project root directory
     * @param groupId Maven groupId
     * @param artifactId Maven artifactId
     * @param overwrite true to overwrite an existing file
     */
    private void writePom(Path projectRoot, String groupId, String artifactId, boolean overwrite) {
        Path pom = projectRoot.resolve("pom.xml");

        String safeGroupId = (groupId == null || groupId.isBlank())
                ? "com.generated"
                : groupId.trim();

        String safeArtifactId = (artifactId == null || artifactId.isBlank())
                ? "generated-app"
                : artifactId.trim();

        StringBuilder builder = new StringBuilder();

        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        builder.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
        builder.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        builder.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n");
        builder.append("    <modelVersion>4.0.0</modelVersion>\n\n");

        builder.append("    <parent>\n");
        builder.append("        <groupId>org.springframework.boot</groupId>\n");
        builder.append("        <artifactId>spring-boot-starter-parent</artifactId>\n");
        builder.append("        <version>").append(SPRING_BOOT_VERSION).append("</version>\n");
        builder.append("        <relativePath/>\n");
        builder.append("    </parent>\n\n");

        builder.append("    <groupId>").append(safeGroupId).append("</groupId>\n");
        builder.append("    <artifactId>").append(safeArtifactId).append("</artifactId>\n");
        builder.append("    <version>0.0.1-SNAPSHOT</version>\n");
        builder.append("    <name>").append(safeArtifactId).append("</name>\n");
        builder.append("    <description>Generated Spring Boot project</description>\n\n");

        builder.append("    <properties>\n");
        builder.append("        <java.version>21</java.version>\n");
        builder.append("        <maven.compiler.release>21</maven.compiler.release>\n");
        builder.append("        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n");
        builder.append("        <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>\n");
        builder.append("        <springdoc.version>").append(SPRINGDOC_VERSION).append("</springdoc.version>\n");
        builder.append("        <modelmapper.version>").append(MODELMAPPER_VERSION).append("</modelmapper.version>\n");
        builder.append("        <jjwt.version>0.12.6</jjwt.version>\n");
        builder.append("        <lombok.version>1.18.36</lombok.version>\n");
        builder.append("        <jacoco.version>0.8.12</jacoco.version>\n");
        builder.append("        <jacoco.minimum.line.coverage>0.70</jacoco.minimum.line.coverage>\n");
        builder.append("    </properties>\n\n");

        builder.append("    <dependencies>\n");

        builder.append("        <dependency>\n");
        builder.append("            <groupId>org.springframework.boot</groupId>\n");
        builder.append("            <artifactId>spring-boot-starter-web</artifactId>\n");
        builder.append("        </dependency>\n\n");

        builder.append("        <dependency>\n");
        builder.append("            <groupId>org.springframework.boot</groupId>\n");
        builder.append("            <artifactId>spring-boot-starter-data-jpa</artifactId>\n");
        builder.append("        </dependency>\n\n");

        builder.append("        <dependency>\n");
        builder.append("            <groupId>org.springframework.boot</groupId>\n");
        builder.append("            <artifactId>spring-boot-starter-validation</artifactId>\n");
        builder.append("        </dependency>\n\n");

        builder.append("        <dependency>\n");
        builder.append("            <groupId>org.springframework.boot</groupId>\n");
        builder.append("            <artifactId>spring-boot-starter-security</artifactId>\n");
        builder.append("        </dependency>\n\n");

        builder.append("        <!-- JWT API -->\n");
        builder.append("        <dependency>\n");
        builder.append("            <groupId>io.jsonwebtoken</groupId>\n");
        builder.append("            <artifactId>jjwt-api</artifactId>\n");
        builder.append("            <version>${jjwt.version}</version>\n");
        builder.append("        </dependency>\n\n");

        builder.append("        <!-- JWT implementation -->\n");
        builder.append("        <dependency>\n");
        builder.append("            <groupId>io.jsonwebtoken</groupId>\n");
        builder.append("            <artifactId>jjwt-impl</artifactId>\n");
        builder.append("            <version>${jjwt.version}</version>\n");
        builder.append("            <scope>runtime</scope>\n");
        builder.append("        </dependency>\n\n");

        builder.append("        <!-- JWT Jackson integration -->\n");
        builder.append("        <dependency>\n");
        builder.append("            <groupId>io.jsonwebtoken</groupId>\n");
        builder.append("            <artifactId>jjwt-jackson</artifactId>\n");
        builder.append("            <version>${jjwt.version}</version>\n");
        builder.append("            <scope>runtime</scope>\n");
        builder.append("        </dependency>\n\n");

        builder.append("        <dependency>\n");
        builder.append("            <groupId>org.springdoc</groupId>\n");
        builder.append("            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>\n");
        builder.append("            <version>${springdoc.version}</version>\n");
        builder.append("        </dependency>\n\n");

        builder.append("        <dependency>\n");
        builder.append("            <groupId>org.modelmapper</groupId>\n");
        builder.append("            <artifactId>modelmapper</artifactId>\n");
        builder.append("            <version>${modelmapper.version}</version>\n");
        builder.append("        </dependency>\n\n");

        builder.append("        <dependency>\n");
        builder.append("            <groupId>org.hibernate.orm</groupId>\n");
        builder.append("            <artifactId>hibernate-envers</artifactId>\n");
        builder.append("        </dependency>\n\n");

        builder.append("        <dependency>\n");
        builder.append("            <groupId>org.postgresql</groupId>\n");
        builder.append("            <artifactId>postgresql</artifactId>\n");
        builder.append("            <scope>runtime</scope>\n");
        builder.append("        </dependency>\n\n");

        builder.append("        <dependency>\n");
        builder.append("            <groupId>org.liquibase</groupId>\n");
        builder.append("            <artifactId>liquibase-core</artifactId>\n");
        builder.append("        </dependency>\n\n");

        builder.append("        <dependency>\n");
        builder.append("            <groupId>org.projectlombok</groupId>\n");
        builder.append("            <artifactId>lombok</artifactId>\n");
        builder.append("            <version>${lombok.version}</version>\n");
        builder.append("            <scope>provided</scope>\n");
        builder.append("        </dependency>\n\n");

        builder.append("        <dependency>\n");
        builder.append("            <groupId>org.springframework.boot</groupId>\n");
        builder.append("            <artifactId>spring-boot-starter-test</artifactId>\n");
        builder.append("            <scope>test</scope>\n");
        builder.append("        </dependency>\n");

        builder.append("    </dependencies>\n\n");

        builder.append("    <build>\n");
        builder.append("        <plugins>\n");

        builder.append("            <plugin>\n");
        builder.append("                <groupId>org.apache.maven.plugins</groupId>\n");
        builder.append("                <artifactId>maven-compiler-plugin</artifactId>\n");
        builder.append("                <configuration>\n");
        builder.append("                    <release>${maven.compiler.release}</release>\n");
        builder.append("                    <annotationProcessorPaths>\n");
        builder.append("                        <path>\n");
        builder.append("                            <groupId>org.projectlombok</groupId>\n");
        builder.append("                            <artifactId>lombok</artifactId>\n");
        builder.append("                            <version>${lombok.version}</version>\n");
        builder.append("                        </path>\n");
        builder.append("                    </annotationProcessorPaths>\n");
        builder.append("                </configuration>\n");
        builder.append("            </plugin>\n\n");

        builder.append("            <plugin>\n");
        builder.append("                <groupId>org.springframework.boot</groupId>\n");
        builder.append("                <artifactId>spring-boot-maven-plugin</artifactId>\n");
        builder.append("            </plugin>\n\n");

        builder.append("            <plugin>\n");
        builder.append("                <groupId>org.jacoco</groupId>\n");
        builder.append("                <artifactId>jacoco-maven-plugin</artifactId>\n");
        builder.append("                <version>${jacoco.version}</version>\n");
        builder.append("                <executions>\n");

        builder.append("                    <execution>\n");
        builder.append("                        <id>prepare-agent</id>\n");
        builder.append("                        <goals>\n");
        builder.append("                            <goal>prepare-agent</goal>\n");
        builder.append("                        </goals>\n");
        builder.append("                    </execution>\n\n");

        builder.append("                    <execution>\n");
        builder.append("                        <id>report</id>\n");
        builder.append("                        <phase>verify</phase>\n");
        builder.append("                        <goals>\n");
        builder.append("                            <goal>report</goal>\n");
        builder.append("                        </goals>\n");
        builder.append("                    </execution>\n\n");

        builder.append("                    <execution>\n");
        builder.append("                        <id>check</id>\n");
        builder.append("                        <phase>verify</phase>\n");
        builder.append("                        <goals>\n");
        builder.append("                            <goal>check</goal>\n");
        builder.append("                        </goals>\n");
        builder.append("                        <configuration>\n");
        builder.append("                            <rules>\n");
        builder.append("                                <rule>\n");
        builder.append("                                    <element>BUNDLE</element>\n");
        builder.append("                                    <limits>\n");
        builder.append("                                        <limit>\n");
        builder.append("                                            <counter>LINE</counter>\n");
        builder.append("                                            <value>COVEREDRATIO</value>\n");
        builder.append("                                            <minimum>${jacoco.minimum.line.coverage}</minimum>\n");
        builder.append("                                        </limit>\n");
        builder.append("                                    </limits>\n");
        builder.append("                                </rule>\n");
        builder.append("                            </rules>\n");
        builder.append("                        </configuration>\n");
        builder.append("                    </execution>\n");

        builder.append("                </executions>\n");
        builder.append("            </plugin>\n");

        builder.append("        </plugins>\n");
        builder.append("    </build>\n\n");
        builder.append("</project>\n");

        GeneratorSupport.writeFile(pom, builder.toString(), overwrite);
    }

    /**
     * Creates the MessageResolver utility class for resolving internationalized messages.
     *
     * @param projectRoot project root directory
     * @param basePackage base Java package
     * @param overwrite whether existing files should be overwritten
     */
    private void createMessageResolver(Path projectRoot, String basePackage, boolean overwrite) {
        Path utilDir = PackageResolver.resolvePath(projectRoot.toString(), basePackage, "util");
        GeneratorSupport.ensureDirectory(utilDir);

        Path file = utilDir.resolve("MessageResolver.java");
        String utilPackage = PackageResolver.resolvePackageName(basePackage, "util");

        StringBuilder builder = new StringBuilder();

        builder.append("package ").append(utilPackage).append(";\n\n");
        builder.append("import lombok.RequiredArgsConstructor;\n");
        builder.append("import org.springframework.context.MessageSource;\n");
        builder.append("import org.springframework.context.i18n.LocaleContextHolder;\n");
        builder.append("import org.springframework.stereotype.Component;\n\n");
        builder.append("import java.util.Locale;\n\n");
        builder.append("/**\n");
        builder.append(" * Resolves internationalized application messages.\n");
        builder.append(" */\n");
        builder.append("@Component\n");
        builder.append("@RequiredArgsConstructor\n");
        builder.append("public class MessageResolver {\n\n");
        builder.append("    private final MessageSource messageSource;\n\n");
        builder.append("    /**\n");
        builder.append("     * Resolves a message by key using the current request locale.\n");
        builder.append("     *\n");
        builder.append("     * @param key message key\n");
        builder.append("     * @param arguments message arguments\n");
        builder.append("     * @return resolved message\n");
        builder.append("     */\n");
        builder.append("    public String resolve(String key, Object... arguments) {\n");
        builder.append("        Locale locale = LocaleContextHolder.getLocale();\n");
        builder.append("        return messageSource.getMessage(key, arguments, key, locale);\n");
        builder.append("    }\n");
        builder.append("}\n");

        GeneratorSupport.writeFile(file, builder.toString(), overwrite);
    }


    /**
     * Generates the Spring Boot application entry point class.
     *
     * @param projectRoot target project root directory
     * @param basePackage base Java package
     * @param overwrite true to overwrite an existing file
     */
    private void writeApplication(Path projectRoot, String basePackage, boolean overwrite) {
        Path baseJavaDir = resolveBaseJavaDir(projectRoot, basePackage, false);
        GeneratorSupport.ensureDirectory(baseJavaDir);

        String applicationClassName = resolveApplicationClassName(basePackage);
        Path appFile = baseJavaDir.resolve(applicationClassName + ".java");

        StringBuilder builder = new StringBuilder();

        builder.append("package ").append(basePackage).append(";\n\n");
        builder.append("import org.springframework.boot.SpringApplication;\n");
        builder.append("import org.springframework.boot.autoconfigure.SpringBootApplication;\n\n");
        builder.append("/**\n");
        builder.append(" * Spring Boot entry point for the generated project.\n");
        builder.append(" */\n");
        builder.append("@SpringBootApplication\n");
        builder.append("public class ").append(applicationClassName).append(" {\n\n");
        builder.append("    public static void main(String[] args) {\n");
        builder.append("        SpringApplication.run(").append(applicationClassName).append(".class, args);\n");
        builder.append("    }\n");
        builder.append("}\n");

        GeneratorSupport.writeFile(appFile, builder.toString(), overwrite);
    }


    /**
     * Resolves the Spring Boot application class name from the base package.
     *
     * @param basePackage base Java package
     * @return resolved application class name
     */
    private static String resolveApplicationClassName(String basePackage) {
        String leaf = basePackage.substring(basePackage.lastIndexOf('.') + 1).trim();

        // Handle snake/kebab etc: med_heritage -> MedHeritage
        String cleaned = leaf.replaceAll("[^A-Za-z0-9]+", " ").trim();
        if (cleaned.contains(" ")) {
            String[] parts = cleaned.split("\\s+");
            StringBuilder sb = new StringBuilder();
            for (String p : parts) sb.append(capitalize(p));
            return sb.append("Application").toString();
        }

        // Handle common lowercase compounds: schoolmanagement -> SchoolManagement
        String lower = leaf.toLowerCase();
        String[] suffixes = {"management", "service", "api", "core", "app"};
        for (String suf : suffixes) {
            if (lower.endsWith(suf) && lower.length() > suf.length()) {
                String prefix = lower.substring(0, lower.length() - suf.length());
                return capitalize(prefix) + capitalize(suf) + "Application";
            }
        }

        return capitalize(leaf) + "Application";
    }

    /**
     * Capitalizes the first character of the given string.
     * @return value with uppercase first character, or empty string when blank
     */
    private static String capitalize(String s) {
        if (s == null || s.isBlank()) return "";
        String t = s.trim();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }


    /**
     * Creates the application.properties file for the generated project.
     *
     * @param root project root directory
     * @param applicationName Spring application name
     * @param defaultSchemaName default database schema name
     * @param basePackage generated project base package
     * @param generatorConfig generator configuration used to create application properties
     * @param overwrite whether existing files should be overwritten
     */
    private void createApplicationProperties(
            Path root,
            String applicationName,
            String defaultSchemaName,
            String basePackage,
            GeneratorConfig generatorConfig,
            boolean overwrite
    ) {
        String name = (applicationName == null || applicationName.isBlank())
                ? "generated-app"
                : applicationName.trim();

        String resolvedSchemaName = (defaultSchemaName == null || defaultSchemaName.isBlank())
                ? "public"
                : defaultSchemaName.trim();

        String resolvedBasePackage = (basePackage == null || basePackage.isBlank())
                ? "com.generated"
                : basePackage.trim();

        long jwtExpirationMinutes = generatorConfig
                .getSecurity()
                .getJwt()
                .getExpirationMinutes();

        StringBuilder builder = new StringBuilder();

        builder.append("spring.application.name=").append(name).append("\n\n");

        builder.append("############################\n");
        builder.append("# PostgreSQL\n");
        builder.append("############################\n");
        builder.append("spring.datasource.url=jdbc:postgresql://localhost:5432/").append(resolvedSchemaName).append("\n");
        builder.append("spring.datasource.username=postgres\n");
        builder.append("spring.datasource.password=postgres\n\n");

        builder.append("############################\n");
        builder.append("# Liquibase\n");
        builder.append("############################\n");
        builder.append("spring.liquibase.enabled=true\n");
        builder.append("spring.liquibase.change-log=classpath:db/migration/changelog-master.xml\n");
        builder.append("spring.liquibase.liquibase-schema=public\n\n");

        builder.append("############################\n");
        builder.append("# JPA\n");
        builder.append("############################\n");
        builder.append("spring.jpa.hibernate.ddl-auto=validate\n");
        builder.append("spring.jpa.open-in-view=false\n");
        builder.append("spring.jpa.properties.hibernate.default_schema=").append(resolvedSchemaName).append("\n");
        builder.append("spring.jpa.properties.org.hibernate.envers.default_schema=audit\n\n");

        builder.append("############################\n");
        builder.append("# MVC error handling\n");
        builder.append("############################\n");
        builder.append("spring.mvc.throw-exception-if-no-handler-found=true\n");
        builder.append("spring.web.resources.add-mappings=false\n\n");

        builder.append("server.port=8081\n\n");

        builder.append("############################\n");
        builder.append("# JWT\n");
        builder.append("############################\n");
        builder.append("security.jwt.secret=${JWT_SECRET}\n");
        builder.append("security.jwt.expiration-minutes=").append(jwtExpirationMinutes).append("\n\n");

        builder.append("############################\n");
        builder.append("# Swagger\n");
        builder.append("############################\n");
        builder.append("springdoc.default-produces-media-type=application/json\n");
        builder.append("springdoc.api-docs.enabled=true\n");
        builder.append("springdoc.swagger-ui.enabled=true\n");
        builder.append("springdoc.swagger-ui.tagsSorter=alpha\n");
        builder.append("springdoc.swagger-ui.operationsSorter=alpha\n");
        builder.append("springdoc.writer-with-order-by-keys=true\n\n");

        builder.append("############################\n");
        builder.append("# Logging\n");
        builder.append("############################\n");
        builder.append("logging.level.root=INFO\n");
        builder.append("logging.level.").append(resolvedBasePackage).append("=INFO\n");

        Path file = root.resolve("src/main/resources/application.properties");
        GeneratorSupport.writeFile(file, builder.toString(), overwrite);
    }

    /**
     * Creates the message bundle files for the generated project.
     *
     * @param root project root directory
     * @param overwrite whether existing files should be overwritten
     */
    private void createMessageProperties(Path root, boolean overwrite) {
        StringBuilder messages = new StringBuilder();

        messages.append("# Generic entity messages\n");
        messages.append("entity.notFoundById={0} not found with id: {1}\n");
        messages.append("entity.notFoundByCompositeId={0} not found with composite id: {1}\n");
        messages.append("entity.alreadyExistsById={0} already exists with id: {1}\n");
        messages.append("entity.uniqueConstraintViolation={0} with {1} already exists\n");
        messages.append("entity.alreadyExistsByCompositeId={0} already exists with composite id: {1}\n\n");

        messages.append("# Generic validation messages\n");
        messages.append("validation.badRequest=Bad request\n");
        messages.append("validation.required=Field is required\n");
        messages.append("validation.invalidValue=Invalid value\n\n");

        messages.append("# Error messages\n");
        messages.append("error.unexpected=Unexpected error\n");
        messages.append("error.endpointNotFound=Endpoint not found: {0}\n");
        messages.append("error.invalidRequestBody=Invalid request body\n");
        messages.append("error.validationFailed=Validation failed\n");
        messages.append("error.invalid=Invalid\n");
        messages.append("error.usernameAlreadyExists=Username {0} already exists\n");
        messages.append("error.emailAlreadyExists=Email {0} already exists\n");
        messages.append("error.invalidCredentials=Invalid username or password\n");

        StringBuilder greekMessages = new StringBuilder();

        greekMessages.append("# Generic entity messages\n");
        greekMessages.append("entity.notFoundById=Δεν βρέθηκε {0} με id: {1}\n");
        greekMessages.append("entity.notFoundByCompositeId=Δεν βρέθηκε {0} με σύνθετο id: {1}\n");
        greekMessages.append("entity.alreadyExistsById=Το {0} υπάρχει ήδη με id: {1}\n");
        greekMessages.append("entity.uniqueConstraintViolation=Το {0} με {1} υπάρχει ήδη\n");
        greekMessages.append("entity.alreadyExistsByCompositeId=Το {0} υπάρχει ήδη με σύνθετο id: {1}\n\n");

        greekMessages.append("# Generic validation messages\n");
        greekMessages.append("validation.badRequest=Μη έγκυρο αίτημα\n");
        greekMessages.append("validation.required=Το πεδίο είναι υποχρεωτικό\n");
        greekMessages.append("validation.invalidValue=Μη έγκυρη τιμή\n\n");

        greekMessages.append("# Error messages\n");
        greekMessages.append("error.unexpected=Μη αναμενόμενο σφάλμα\n");
        greekMessages.append("error.endpointNotFound=Το endpoint δεν βρέθηκε: {0}\n");
        greekMessages.append("error.invalidRequestBody=Μη έγκυρο σώμα αιτήματος\n");
        greekMessages.append("error.validationFailed=Η επικύρωση απέτυχε\n");
        greekMessages.append("error.invalid=Μη έγκυρο\n");
        greekMessages.append("error.usernameAlreadyExists=Το όνομα χρήστη {0} υπάρχει ήδη\n");
        greekMessages.append("error.emailAlreadyExists=Το email {0} υπάρχει ήδη\n");
        greekMessages.append("error.invalidCredentials=Μη έγκυρο όνομα χρήστη ή κωδικός πρόσβασης\n");

        Path messagesFile = root.resolve("src/main/resources/messages.properties");
        Path greekMessagesFile = root.resolve("src/main/resources/messages_el.properties");

        GeneratorSupport.writeFile(messagesFile, messages.toString(), overwrite);
        GeneratorSupport.writeFile(greekMessagesFile, greekMessages.toString(), overwrite);
    }




    /**
     * Resolves the base Java source directory for the given package.
     *
     * @param projectRoot target project root directory
     * @param basePackage base Java package
     * @param testSources true to resolve the test source path, false for main source path
     * @return resolved base Java directory path
     */
    private static Path resolveBaseJavaDir(Path projectRoot, String basePackage, boolean testSources) {
        String basePath = basePackage.replace('.', '/');

        if (testSources) {
            return projectRoot.resolve(Path.of("src", "test", "java", basePath));
        }

        return projectRoot.resolve(Path.of("src", "main", "java", basePath));
    }

    /**
     * Copies the Maven Wrapper files into the generated project root
     * so the generated project can run Maven commands independently.
     *
     * @param projectRoot generated project root directory
     */
    private void copyMavenWrapper(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot must not be null");

        Path generatorRoot = Path.of("").toAbsolutePath().normalize();
        Path sourceMvnwCmd = generatorRoot.resolve("mvnw.cmd");
        Path sourceMvnw = generatorRoot.resolve("mvnw");
        Path sourceMvnDir = generatorRoot.resolve(".mvn");

        if (!java.nio.file.Files.exists(sourceMvnwCmd)) {
            throw new IllegalStateException("Missing Maven wrapper file: " + sourceMvnwCmd);
        }

        if (!java.nio.file.Files.exists(sourceMvnw)) {
            throw new IllegalStateException("Missing Maven wrapper file: " + sourceMvnw);
        }

        if (!java.nio.file.Files.isDirectory(sourceMvnDir)) {
            throw new IllegalStateException("Missing Maven wrapper directory: " + sourceMvnDir);
        }

        try {
            java.nio.file.Files.copy(
                    sourceMvnwCmd,
                    projectRoot.resolve("mvnw.cmd"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );

            java.nio.file.Files.copy(
                    sourceMvnw,
                    projectRoot.resolve("mvnw"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );

            Path targetMvnDir = projectRoot.resolve(".mvn");

            if (java.nio.file.Files.exists(targetMvnDir)) {
                try (java.util.stream.Stream<Path> targetPaths = java.nio.file.Files.walk(targetMvnDir)) {
                    targetPaths.sorted(java.util.Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    java.nio.file.Files.delete(path);
                                } catch (java.io.IOException exception) {
                                    throw new RuntimeException("Failed to delete existing wrapper path: " + path, exception);
                                }
                            });
                }
            }

            try (java.util.stream.Stream<Path> sourcePaths = java.nio.file.Files.walk(sourceMvnDir)) {
                sourcePaths.forEach(sourcePath -> {
                    try {
                        Path relativePath = sourceMvnDir.relativize(sourcePath);
                        Path targetPath = targetMvnDir.resolve(relativePath);

                        if (java.nio.file.Files.isDirectory(sourcePath)) {
                            java.nio.file.Files.createDirectories(targetPath);
                        } else {
                            java.nio.file.Files.createDirectories(targetPath.getParent());
                            java.nio.file.Files.copy(
                                    sourcePath,
                                    targetPath,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                            );
                        }
                    } catch (java.io.IOException exception) {
                        throw new RuntimeException("Failed to copy wrapper path: " + sourcePath, exception);
                    }
                });
            }

            log.info("Maven wrapper copied to generated project root: {}", projectRoot.toAbsolutePath());

        } catch (java.io.IOException exception) {
            throw new RuntimeException("Failed to copy Maven wrapper into generated project.", exception);
        }
    }

    /**
     * Generates a minimal .gitignore file for the project.
     *
     * @param projectRoot project root directory
     * @param overwrite whether to overwrite existing file
     */
    private void writeGitignore(Path projectRoot, boolean overwrite) {
        Path gitignore = projectRoot.resolve(".gitignore");

        StringBuilder builder = new StringBuilder();

        builder.append("target/\n");
        builder.append("*.class\n");
        builder.append("*.jar\n");
        builder.append("*.log\n\n");
        builder.append(".idea/\n");
        builder.append("*.iml\n");

        GeneratorSupport.writeFile(gitignore, builder.toString(), overwrite);
    }
}
