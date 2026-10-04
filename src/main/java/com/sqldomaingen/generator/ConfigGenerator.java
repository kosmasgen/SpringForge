package com.sqldomaingen.generator;

import com.sqldomaingen.config.GeneratorConfig;
import com.sqldomaingen.util.Constants;
import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.util.PackageResolver;
import lombok.extern.log4j.Log4j2;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Generates configuration classes for the produced Spring Boot project.
 */
@Log4j2
public class ConfigGenerator {


    /**
     * Generates all configuration files required by the generated project.
     *
     * @param outputDir generated project root directory
     * @param basePackage generated project's base package
     * @param generatorConfig generator configuration
     * @param overwrite overwrite existing files if true
     */
    public void generateConfigs(String outputDir, String basePackage, GeneratorConfig generatorConfig, boolean overwrite) {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");
        Objects.requireNonNull(generatorConfig, "generatorConfig must not be null");

        generateModelMapperConfig(outputDir, basePackage, overwrite);
        generateCorsConfig(outputDir, basePackage, overwrite);

        GeneratorConfig.Security security = generatorConfig.getSecurity();

        if (security == null || !security.isEnabled()) {
            return;
        }

        generatePasswordConfig(outputDir, basePackage, overwrite);

        if (security.getJwt() != null && security.getJwt().isEnabled()) {
            generateSecurityConfig(outputDir, basePackage, overwrite);
            generateOpenApiConfig(outputDir, basePackage, overwrite);
        }
    }

    /**
     * Generates the PasswordConfig class used to expose the application PasswordEncoder.
     *
     * @param outputDir generated project root directory
     * @param basePackage generated project's base package
     * @param overwrite overwrite existing file if true
     */
    public void generatePasswordConfig(String outputDir, String basePackage, boolean overwrite) {
        String[] normalized = validateAndNormalizePaths(outputDir, basePackage);
        String out = normalized[0];
        String pkg = normalized[1];

        Path configDir = resolveConfigDirectory(out, pkg);
        String configPackage = resolveConfigPackage(pkg);
        Path file = configDir.resolve("PasswordConfig.java");

        StringBuilder builder = new StringBuilder();

        appendPasswordConfigPackageAndImports(builder, configPackage);
        appendPasswordConfigClassDeclaration(builder);
        appendPasswordEncoderBean(builder);
        builder.append("}\n");

        GeneratorSupport.writeFile(file, builder.toString(), overwrite);
        log.debug("PasswordConfig generated: {}", file.toAbsolutePath());
    }

    /**
     * Appends the package declaration and imports required by PasswordConfig.
     *
     * @param builder target source builder
     * @param configPackage target configuration package
     */
    private void appendPasswordConfigPackageAndImports(StringBuilder builder, String configPackage) {
        builder.append("package ").append(configPackage).append(";\n\n");
        builder.append("import org.springframework.context.annotation.Bean;\n");
        builder.append("import org.springframework.context.annotation.Configuration;\n");
        builder.append("import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;\n");
        builder.append("import org.springframework.security.crypto.password.PasswordEncoder;\n\n");
    }

    /**
     * Appends the PasswordConfig class declaration.
     *
     * @param builder target source builder
     */
    private void appendPasswordConfigClassDeclaration(StringBuilder builder) {
        builder.append("/**\n");
        builder.append(" * Password encoding configuration.\n");
        builder.append(" */\n");
        builder.append("@Configuration\n");
        builder.append("public class PasswordConfig {\n\n");
    }

    /**
     * Appends the PasswordEncoder bean definition.
     *
     * @param builder target source builder
     */
    private void appendPasswordEncoderBean(StringBuilder builder) {
        builder.append("    /**\n");
        builder.append("     * Creates the password encoder used by the authentication flow.\n");
        builder.append("     *\n");
        builder.append("     * @return configured password encoder\n");
        builder.append("     */\n");
        builder.append("    @Bean\n");
        builder.append("    public PasswordEncoder passwordEncoder() {\n");
        builder.append("        return new BCryptPasswordEncoder();\n");
        builder.append("    }\n");
    }

    /**
     * Generates the Spring Security configuration used by the generated application.
     *
     * @param outputDir generated project root directory
     * @param basePackage generated project's base package
     * @param overwrite overwrite existing file if true
     */
    public void generateSecurityConfig(String outputDir, String basePackage, boolean overwrite) {
        String[] normalized = validateAndNormalizePaths(outputDir, basePackage);
        String out = normalized[0];
        String pkg = normalized[1];

        Path configDir = resolveConfigDirectory(out, pkg);
        String configPackage = resolveConfigPackage(pkg);
        String securityPackage = PackageResolver.resolvePackageName(pkg, "security");
        Path file = configDir.resolve("SecurityConfig.java");

        StringBuilder builder = new StringBuilder();

        appendSecurityConfigPackageAndImports(builder, configPackage, securityPackage);
        appendSecurityConfigClassDeclaration(builder);
        appendSecurityConfigFields(builder);
        appendAuthenticationManagerBean(builder);
        appendSecurityFilterChainBean(builder);
        builder.append("}\n");

        GeneratorSupport.writeFile(file, builder.toString(), overwrite);
        log.debug("SecurityConfig generated: {}", file.toAbsolutePath());
    }

    /**
     * Appends the package declaration and imports required by SecurityConfig.
     *
     * @param builder target source builder
     * @param configPackage target configuration package
     * @param securityPackage target security package
     */
    private void appendSecurityConfigPackageAndImports(StringBuilder builder, String configPackage, String securityPackage) {
        builder.append("package ").append(configPackage).append(";\n\n");
        builder.append("import ").append(securityPackage).append(".JwtAuthenticationFilter;\n");
        builder.append("import ").append(securityPackage).append(".RestAuthenticationEntryPoint;\n");
        builder.append("import ").append(securityPackage).append(".RestAccessDeniedHandler;\n");
        builder.append("import lombok.RequiredArgsConstructor;\n");
        builder.append("import org.springframework.context.annotation.Bean;\n");
        builder.append("import org.springframework.context.annotation.Configuration;\n");
        builder.append("import org.springframework.security.authentication.AuthenticationManager;\n");
        builder.append("import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;\n");
        builder.append("import org.springframework.security.config.annotation.web.builders.HttpSecurity;\n");
        builder.append("import org.springframework.security.config.http.SessionCreationPolicy;\n");
        builder.append("import org.springframework.security.web.SecurityFilterChain;\n");
        builder.append("import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;\n\n");
    }

    /**
     * Appends the AuthenticationManager bean used by the authentication service.
     *
     * @param builder target source builder
     */
    private void appendAuthenticationManagerBean(StringBuilder builder) {
        builder.append("    /**\n");
        builder.append("     * Creates the authentication manager used for username and password authentication.\n");
        builder.append("     *\n");
        builder.append("     * @param authenticationConfiguration Spring Security authentication configuration\n");
        builder.append("     * @return configured authentication manager\n");
        builder.append("     * @throws Exception when the authentication manager cannot be created\n");
        builder.append("     */\n");
        builder.append("    @Bean\n");
        builder.append("    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {\n");
        builder.append("        return authenticationConfiguration.getAuthenticationManager();\n");
        builder.append("    }\n\n");
    }

    /**
     * Appends the SecurityConfig class declaration.
     *
     * @param builder target source builder
     */
    private void appendSecurityConfigClassDeclaration(StringBuilder builder) {
        builder.append("/**\n");
        builder.append(" * Spring Security configuration for JWT authentication.\n");
        builder.append(" */\n");
        builder.append("@Configuration\n");
        builder.append("@RequiredArgsConstructor\n");
        builder.append("public class SecurityConfig {\n\n");
    }

    /**
     * Appends the dependencies required by SecurityConfig.
     *
     * @param builder target source builder
     */
    private void appendSecurityConfigFields(StringBuilder builder) {
        builder.append("    private final JwtAuthenticationFilter jwtAuthenticationFilter;\n");
        builder.append("    private final RestAuthenticationEntryPoint authenticationEntryPoint;\n");
        builder.append("    private final RestAccessDeniedHandler accessDeniedHandler;\n\n");
    }

    /**
     * Appends the SecurityFilterChain bean used by the generated application.
     *
     * @param builder target source builder
     */
    private void appendSecurityFilterChainBean(StringBuilder builder) {
        builder.append("    /**\n");
        builder.append("     * Configures HTTP security, JWT authentication, and REST security error handling.\n");
        builder.append("     *\n");
        builder.append("     * @param http Spring Security HTTP configuration\n");
        builder.append("     * @return configured security filter chain\n");
        builder.append("     * @throws Exception when security configuration fails\n");
        builder.append("     */\n");
        builder.append("    @Bean\n");
        builder.append("    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {\n");
        builder.append("        http\n");
        builder.append("                .csrf(csrf -> csrf.disable())\n");
        builder.append("                .authorizeHttpRequests(auth -> auth\n");
        builder.append("                        .requestMatchers(\"/api/auth/**\").permitAll()\n");
        builder.append("                        .requestMatchers(\"/swagger-ui/**\", \"/v3/api-docs/**\", \"/swagger-language.js\").permitAll()\n");
        builder.append("                        .anyRequest().authenticated()\n");
        builder.append("                )\n");
        builder.append("                .exceptionHandling(exceptions -> exceptions\n");
        builder.append("                        .authenticationEntryPoint(authenticationEntryPoint)\n");
        builder.append("                        .accessDeniedHandler(accessDeniedHandler)\n");
        builder.append("                )\n");
        builder.append("                .sessionManagement(session -> session\n");
        builder.append("                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)\n");
        builder.append("                )\n");
        builder.append("                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);\n\n");
        builder.append("        return http.build();\n");
        builder.append("    }\n");
    }

    /**
     * Generates a ModelMapperConfig class that exposes a ModelMapper bean.
     *
     * @param outputDir generated project root directory
     * @param basePackage generated project's base package
     * @param overwrite overwrite existing file if true
     */
    public void generateModelMapperConfig(String outputDir, String basePackage, boolean overwrite) {
        String[] normalized = validateAndNormalizePaths(outputDir, basePackage);
        String out = normalized[0];
        String pkg = normalized[1];

        Path configDir = resolveConfigDirectory(out, pkg);
        String configPackage = resolveConfigPackage(pkg);
        Path file = configDir.resolve("ModelMapperConfig.java");

        StringBuilder builder = new StringBuilder();

        appendModelMapperPackageAndImports(builder, configPackage);
        appendModelMapperClassDeclaration(builder);
        appendModelMapperBean(builder);
        builder.append("}\n");

        GeneratorSupport.writeFile(file, builder.toString(), overwrite);
        log.debug("ModelMapperConfig generated: {}", file.toAbsolutePath());
    }

    /**
     * Appends the package declaration and imports required by ModelMapperConfig.
     *
     * @param builder target source builder
     * @param configPackage target configuration package
     */
    private void appendModelMapperPackageAndImports(StringBuilder builder, String configPackage) {
        builder.append("package ").append(configPackage).append(";\n\n");
        builder.append("import org.modelmapper.ModelMapper;\n");
        builder.append("import org.springframework.context.annotation.Bean;\n");
        builder.append("import org.springframework.context.annotation.Configuration;\n\n");
    }

    /**
     * Appends the ModelMapperConfig class declaration.
     *
     * @param builder target source builder
     */
    private void appendModelMapperClassDeclaration(StringBuilder builder) {
        builder.append("/**\n");
        builder.append(" * Provides the {@link ModelMapper} bean used by generated mappers.\n");
        builder.append(" */\n");
        builder.append("@Configuration\n");
        builder.append("public class ModelMapperConfig {\n\n");
    }

    /**
     * Appends the ModelMapper bean definition.
     *
     * @param builder target source builder
     */
    private void appendModelMapperBean(StringBuilder builder) {
        builder.append("    /**\n");
        builder.append("     * Creates a {@link ModelMapper} instance configured for PATCH support.\n");
        builder.append("     * <p>\n");
        builder.append("     * Important: null values are skipped during mapping.\n");
        builder.append("     *\n");
        builder.append("     * @return a configured ModelMapper bean\n");
        builder.append("     */\n");
        builder.append("    @Bean\n");
        builder.append("    public ModelMapper modelMapper() {\n");
        builder.append("        ModelMapper modelMapper = new ModelMapper();\n\n");
        builder.append("        modelMapper.getConfiguration()\n");
        builder.append("                .setSkipNullEnabled(true);\n\n");
        builder.append("        return modelMapper;\n");
        builder.append("    }\n");
    }

    /**
     * Generates the CorsConfig class.
     *
     * @param outputDir generated project root directory
     * @param basePackage generated project's base package
     * @param overwrite overwrite existing file if true
     */
    public void generateCorsConfig(String outputDir, String basePackage, boolean overwrite) {
        String[] normalized = validateAndNormalizePaths(outputDir, basePackage);
        String out = normalized[0];
        String pkg = normalized[1];

        Path configDir = resolveConfigDirectory(out, pkg);
        String configPackage = resolveConfigPackage(pkg);
        Path file = configDir.resolve(Constants.CORS_CONFIG_FILE_NAME);

        StringBuilder builder = new StringBuilder();

        appendCorsPackageAndImports(builder, configPackage);
        appendCorsClassDeclaration(builder);
        appendCorsConfigurerBean(builder);
        builder.append("}\n");

        GeneratorSupport.writeFile(file, builder.toString(), overwrite);
        log.debug("CorsConfig generated: {}", file.toAbsolutePath());
    }

    /**
     * Appends the package declaration and imports required by CorsConfig.
     *
     * @param builder target source builder
     * @param configPackage target configuration package
     */
    private void appendCorsPackageAndImports(StringBuilder builder, String configPackage) {
        builder.append("package ").append(configPackage).append(";\n\n");
        builder.append("import org.springframework.context.annotation.Bean;\n");
        builder.append("import org.springframework.context.annotation.Configuration;\n");
        builder.append("import org.springframework.web.servlet.config.annotation.CorsRegistry;\n");
        builder.append("import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;\n\n");
    }

    /**
     * Appends the CorsConfig class declaration.
     *
     * @param builder target source builder
     */
    private void appendCorsClassDeclaration(StringBuilder builder) {
        builder.append("/**\n");
        builder.append(" * CORS configuration.\n");
        builder.append(" */\n");
        builder.append("@Configuration\n");
        builder.append("public class CorsConfig {\n\n");
    }

    /**
     * Appends the WebMvcConfigurer bean used for CORS configuration.
     *
     * @param builder target source builder
     */
    private void appendCorsConfigurerBean(StringBuilder builder) {
        builder.append("    /**\n");
        builder.append("     * Configures CORS mappings.\n");
        builder.append("     *\n");
        builder.append("     * @return configured WebMvcConfigurer\n");
        builder.append("     */\n");
        builder.append("    @Bean\n");
        builder.append("    public WebMvcConfigurer corsConfigurer() {\n");
        builder.append("        return new WebMvcConfigurer() {\n");
        builder.append("            @Override\n");
        builder.append("            public void addCorsMappings(@org.springframework.lang.NonNull CorsRegistry registry) {\n");
        builder.append("                registry.addMapping(\"/**\")\n");
        builder.append("                        .allowedOriginPatterns(\"*\") // TODO set specific origins when allowCredentials(true)\n");
        builder.append("                        .allowedMethods(\"*\")\n");
        builder.append("                        .allowedHeaders(\"*\")\n");
        builder.append("                        .exposedHeaders(\"X-Total-Count\")\n");
        builder.append("                        .allowCredentials(true);\n");
        builder.append("            }\n");
        builder.append("        };\n");
        builder.append("    }\n");
    }

    /**
     * Generates the OpenAPI configuration used by Swagger UI for JWT authentication.
     *
     * @param outputDir generated project root directory
     * @param basePackage generated project's base package
     * @param overwrite overwrite existing file if true
     */
    public void generateOpenApiConfig(String outputDir, String basePackage, boolean overwrite) {
        String[] normalized = validateAndNormalizePaths(outputDir, basePackage);
        String out = normalized[0];
        String pkg = normalized[1];

        Path configDir = resolveConfigDirectory(out, pkg);
        String configPackage = resolveConfigPackage(pkg);
        Path file = configDir.resolve("OpenApiConfig.java");

        StringBuilder builder = new StringBuilder();

        appendOpenApiPackageAndImports(builder, configPackage);
        appendOpenApiClassDeclaration(builder);
        appendOpenApiBean(builder);
        appendSwaggerUiTransformerBean(builder);
        builder.append("}\n");

        GeneratorSupport.writeFile(file, builder.toString(), overwrite);
        log.debug("OpenApiConfig generated: {}", file.toAbsolutePath());
    }

    /**
     * Appends the package declaration and imports required by OpenApiConfig.
     *
     * @param builder target source builder
     * @param configPackage target configuration package
     */
    private void appendOpenApiPackageAndImports(StringBuilder builder, String configPackage) {
        builder.append("package ").append(configPackage).append(";\n\n");

        builder.append("import io.swagger.v3.oas.models.Components;\n");
        builder.append("import io.swagger.v3.oas.models.OpenAPI;\n");
        builder.append("import io.swagger.v3.oas.models.security.SecurityRequirement;\n");
        builder.append("import io.swagger.v3.oas.models.security.SecurityScheme;\n");
        builder.append("import org.springdoc.core.properties.SwaggerUiConfigProperties;\n");
        builder.append("import org.springdoc.core.properties.SwaggerUiOAuthProperties;\n");
        builder.append("import org.springdoc.core.providers.ObjectMapperProvider;\n");
        builder.append("import org.springdoc.webmvc.ui.SwaggerIndexTransformer;\n");
        builder.append("import org.springdoc.webmvc.ui.SwaggerWelcomeCommon;\n");
        builder.append("import org.springframework.context.annotation.Bean;\n");
        builder.append("import org.springframework.context.annotation.Configuration;\n\n");
    }

    /**
     * Appends the Swagger UI transformer bean used to inject the global
     * language selector script into the Swagger UI page.
     *
     * @param builder target source builder
     */
    private void appendSwaggerUiTransformerBean(StringBuilder builder) {
        builder.append("\n");
        builder.append("    /**\n");
        builder.append("     * Creates the Swagger UI transformer used to load the global language selector.\n");
        builder.append("     *\n");
        builder.append("     * @param swaggerUiConfig Swagger UI configuration\n");
        builder.append("     * @param swaggerUiOAuthProperties Swagger OAuth configuration\n");
        builder.append("     * @param swaggerWelcomeCommon Springdoc Swagger welcome configuration\n");
        builder.append("     * @param objectMapperProvider Springdoc object mapper provider\n");
        builder.append("     * @return configured Swagger UI index transformer\n");
        builder.append("     */\n");
        builder.append("    @Bean\n");
        builder.append("    public SwaggerIndexTransformer swaggerIndexTransformer(\n");
        builder.append("            SwaggerUiConfigProperties swaggerUiConfig,\n");
        builder.append("            SwaggerUiOAuthProperties swaggerUiOAuthProperties,\n");
        builder.append("            SwaggerWelcomeCommon swaggerWelcomeCommon,\n");
        builder.append("            ObjectMapperProvider objectMapperProvider) {\n");
        builder.append("        return new SwaggerUiTransformer(\n");
        builder.append("                swaggerUiConfig,\n");
        builder.append("                swaggerUiOAuthProperties,\n");
        builder.append("                swaggerWelcomeCommon,\n");
        builder.append("                objectMapperProvider\n");
        builder.append("        );\n");
        builder.append("    }\n");
    }

    /**
     * Appends the OpenApiConfig class declaration.
     *
     * @param builder target source builder
     */
    private void appendOpenApiClassDeclaration(StringBuilder builder) {
        builder.append("/**\n");
        builder.append(" * OpenAPI configuration for JWT Bearer authentication in Swagger UI.\n");
        builder.append(" */\n");
        builder.append("@Configuration\n");
        builder.append("public class OpenApiConfig {\n\n");
        builder.append("    private static final String SECURITY_SCHEME_NAME = \"bearerAuth\";\n\n");
    }

    /**
     * Appends the OpenAPI bean configured with JWT Bearer authentication.
     *
     * @param builder target source builder
     */
    private void appendOpenApiBean(StringBuilder builder) {
        builder.append("    /**\n");
        builder.append("     * Configures JWT Bearer authentication for the generated OpenAPI documentation.\n");
        builder.append("     *\n");
        builder.append("     * @return configured OpenAPI definition\n");
        builder.append("     */\n");
        builder.append("    @Bean\n");
        builder.append("    public OpenAPI openAPI() {\n");
        builder.append("        SecurityScheme securityScheme = new SecurityScheme()\n");
        builder.append("                .type(SecurityScheme.Type.HTTP)\n");
        builder.append("                .scheme(\"bearer\")\n");
        builder.append("                .bearerFormat(\"JWT\");\n\n");
        builder.append("        return new OpenAPI()\n");
        builder.append("                .components(new Components()\n");
        builder.append("                        .addSecuritySchemes(SECURITY_SCHEME_NAME, securityScheme))\n");
        builder.append("                .addSecurityItem(new SecurityRequirement()\n");
        builder.append("                        .addList(SECURITY_SCHEME_NAME));\n");
        builder.append("    }\n");
    }

    /**
     * Validates and normalizes generator path arguments.
     *
     * @param outputDir output directory
     * @param basePackage base package
     * @return normalized output directory and base package
     */
    private String[] validateAndNormalizePaths(String outputDir, String basePackage) {
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

        return new String[]{out, pkg};
    }

    /**
     * Resolves and creates the config package directory.
     *
     * @param outputDir output directory
     * @param basePackage base package
     * @return config directory path
     */
    private Path resolveConfigDirectory(String outputDir, String basePackage) {
        return GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(outputDir, basePackage, Constants.CONFIG_PACKAGE)
        );
    }

    /**
     * Resolves the config package name.
     *
     * @param basePackage base package
     * @return resolved config package name
     */
    private String resolveConfigPackage(String basePackage) {
        return PackageResolver.resolvePackageName(basePackage, Constants.CONFIG_PACKAGE);
    }
}