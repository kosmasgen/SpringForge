package com.sqldomaingen.generator;

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
                                 boolean overwrite) {
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
        createApplicationProperties(projectRoot, artifactId, defaultSchemaName, pkg, overwrite);
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

        String safeGroupId = (groupId == null || groupId.isBlank()) ? "com.generated" : groupId.trim();
        String safeArtifactId = (artifactId == null || artifactId.isBlank()) ? "generated-app" : artifactId.trim();

        String content = """
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>%s</version>
        <relativePath/>
    </parent>

    <groupId>%s</groupId>
    <artifactId>%s</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>%s</name>
    <description>Generated Spring Boot project</description>

    <properties>
        <java.version>21</java.version>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
        <springdoc.version>%s</springdoc.version>
        <modelmapper.version>%s</modelmapper.version>
        <lombok.version>1.18.36</lombok.version>
        <jacoco.version>0.8.12</jacoco.version>
        <jacoco.minimum.line.coverage>0.70</jacoco.minimum.line.coverage>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>${springdoc.version}</version>
        </dependency>

        <dependency>
            <groupId>org.modelmapper</groupId>
            <artifactId>modelmapper</artifactId>
            <version>${modelmapper.version}</version>
        </dependency>

        <dependency>
            <groupId>org.hibernate.orm</groupId>
            <artifactId>hibernate-envers</artifactId>
        </dependency>

        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <dependency>
            <groupId>org.liquibase</groupId>
            <artifactId>liquibase-core</artifactId>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>${lombok.version}</version>
            <scope>provided</scope>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <release>${maven.compiler.release}</release>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>

            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>

            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <version>${jacoco.version}</version>
                <executions>
                    <execution>
                        <id>prepare-agent</id>
                        <goals>
                            <goal>prepare-agent</goal>
                        </goals>
                    </execution>

                    <execution>
                        <id>report</id>
                        <phase>verify</phase>
                        <goals>
                            <goal>report</goal>
                        </goals>
                    </execution>

                    <execution>
                        <id>check</id>
                        <phase>verify</phase>
                        <goals>
                            <goal>check</goal>
                        </goals>
                        <configuration>
                            <rules>
                                <rule>
                                    <element>BUNDLE</element>
                                    <limits>
                                        <limit>
                                            <counter>LINE</counter>
                                            <value>COVEREDRATIO</value>
                                            <minimum>${jacoco.minimum.line.coverage}</minimum>
                                        </limit>
                                    </limits>
                                </rule>
                            </rules>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

</project>
""".formatted(
                SPRING_BOOT_VERSION,
                safeGroupId,
                safeArtifactId,
                safeArtifactId,
                SPRINGDOC_VERSION,
                MODELMAPPER_VERSION
        );

        GeneratorSupport.writeFile(pom, content, overwrite);
    }

    /**
     * Creates the MessageResolver utility class for resolving internationalized messages.
     *
     * @param projectRoot project root directory
     * @param basePackage base Java package
     * @param overwrite whether existing files should be overwritten
     */
    private void createMessageResolver(Path projectRoot, String basePackage, boolean overwrite) {
        Path utilDir = PackageResolver.resolvePath(
                projectRoot.toString(),
                basePackage,
                "util"
        );

        GeneratorSupport.ensureDirectory(utilDir);

        Path file = utilDir.resolve("MessageResolver.java");

        String utilPackage = PackageResolver.resolvePackageName(basePackage, "util");

        String content = """
package %s;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Resolves internationalized application messages.
 */
@Component
@RequiredArgsConstructor
public class MessageResolver {

    private final MessageSource messageSource;

    /**
     * Resolves a message by key using the current request locale.
     *
     * @param key message key
     * @param arguments message arguments
     * @return resolved message
     */
    public String resolve(String key, Object... arguments) {
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(key, arguments, key, locale);
    }
}
""".formatted(utilPackage);

        GeneratorSupport.writeFile(file, content, overwrite);
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

        String content = """
        package %s;

        import org.springframework.boot.SpringApplication;
        import org.springframework.boot.autoconfigure.SpringBootApplication;

        /**
         * Spring Boot entry point for the generated project.
         */
        @SpringBootApplication
        public class %s {

            public static void main(String[] args) {
                SpringApplication.run(%s.class, args);
            }
        }
        """.formatted(basePackage, applicationClassName, applicationClassName);

        GeneratorSupport.writeFile(appFile, content, overwrite);
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
     * @param overwrite whether existing files should be overwritten
     */
    private void createApplicationProperties(
            Path root,
            String applicationName,
            String defaultSchemaName,
            String basePackage,
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

        String props = """
spring.application.name=%s

############################
# PostgreSQL
############################
spring.datasource.url=jdbc:postgresql://localhost:5432/schooldb
spring.datasource.username=schooluser
spring.datasource.password=Strong_Pass_123!

############################
# Liquibase
############################
spring.liquibase.enabled=true
spring.liquibase.change-log=classpath:db/migration/changelog-master.xml
spring.liquibase.default-schema=%s

############################
# JPA
############################
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.jpa.properties.hibernate.default_schema=%s
spring.jpa.properties.org.hibernate.envers.default_schema=audit

############################
# MVC error handling
############################
spring.mvc.throw-exception-if-no-handler-found=true
spring.web.resources.add-mappings=false

server.port=8081

############################
# Swagger
############################
springdoc.default-produces-media-type=application/json
springdoc.api-docs.enabled=true
springdoc.swagger-ui.enabled=true
springdoc.swagger-ui.tagsSorter=alpha
springdoc.swagger-ui.operationsSorter=alpha
springdoc.writer-with-order-by-keys=true

############################
# Logging
############################
logging.level.root=INFO
logging.level.%s=INFO
""".formatted(name, resolvedSchemaName, resolvedSchemaName, resolvedBasePackage);

        Path file = root.resolve("src/main/resources/application.properties");
        GeneratorSupport.writeFile(file, props, overwrite);
    }

    /**
     * Creates the message bundle files for the generated project.
     *
     * @param root project root directory
     * @param overwrite whether existing files should be overwritten
     */
    private void createMessageProperties(Path root, boolean overwrite) {
        String messages = """
# Generic entity messages
entity.notFoundById={0} not found with id: {1}
entity.notFoundByCompositeId={0} not found with composite id: {1}
entity.alreadyExistsById={0} already exists with id: {1}
entity.uniqueConstraintViolation={0} with {1} already exists
entity.alreadyExistsByCompositeId={0} already exists with composite id: {1}

# Generic validation messages
validation.badRequest=Bad request
validation.required=Field is required
validation.invalidValue=Invalid value
""";

        String greekMessages = """
# Generic entity messages
entity.notFoundById=Δεν βρέθηκε {0} με id: {1}
entity.notFoundByCompositeId=Δεν βρέθηκε {0} με σύνθετο id: {1}
entity.alreadyExistsById=Το {0} υπάρχει ήδη με id: {1}
entity.uniqueConstraintViolation=Το {0} με {1} υπάρχει ήδη
entity.alreadyExistsByCompositeId=Το {0} υπάρχει ήδη με σύνθετο id: {1}

# Generic validation messages
validation.badRequest=Μη έγκυρο αίτημα
validation.required=Το πεδίο είναι υποχρεωτικό
validation.invalidValue=Μη έγκυρη τιμή
""";

        Path messagesFile = root.resolve("src/main/resources/messages.properties");
        Path greekMessagesFile = root.resolve("src/main/resources/messages_el.properties");

        GeneratorSupport.writeFile(messagesFile, messages, overwrite);
        GeneratorSupport.writeFile(greekMessagesFile, greekMessages, overwrite);
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

        String content = """
target/
*.class
*.jar
*.log

.idea/
*.iml
""";

        GeneratorSupport.writeFile(gitignore, content, overwrite);
    }
}
