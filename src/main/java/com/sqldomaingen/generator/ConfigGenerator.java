package com.sqldomaingen.generator;

import com.sqldomaingen.util.Constants;
import com.sqldomaingen.util.PackageResolver;
import lombok.extern.log4j.Log4j2;
import com.sqldomaingen.util.GeneratorSupport;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Generates configuration classes for the produced Spring Boot project.
 */
@Log4j2
public class ConfigGenerator {

    /**
     * Generates all configuration files required by the generated project.
     * @param outputDir generated project root directory
     * @param basePackage generated project's base package
     * @param overwrite overwrite existing files if true
     */
    public void generateConfigs(String outputDir, String basePackage, boolean overwrite) {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        generateModelMapperConfig(outputDir, basePackage, overwrite);
        generateCorsConfig(outputDir, basePackage, overwrite);
    }

    /**
     * Generates a {@code ModelMapperConfig} class that exposes a {@code ModelMapper} bean.
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

        String content = """
            package %s;

            import org.modelmapper.ModelMapper;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;

            /**
             * Provides the {@link ModelMapper} bean used by generated mappers.
             */
            @Configuration
            public class ModelMapperConfig {

                /**
                 * Creates a {@link ModelMapper} instance configured for PATCH support.
                 * <p>
                 * Important: null values are skipped during mapping.
                 *
                 * @return a configured ModelMapper bean
                 */
                @Bean
                public ModelMapper modelMapper() {
                    ModelMapper modelMapper = new ModelMapper();

                    modelMapper.getConfiguration()
                            .setSkipNullEnabled(true);

                    return modelMapper;
                }
            }
            """.formatted(configPackage);

        GeneratorSupport.writeFile(file, content, overwrite);
        log.debug(" ModelMapperConfig generated: {}", file.toAbsolutePath());
    }

    /**
     * Generates a CorsConfig class.
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

        String content = """
            package %s;

            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.web.servlet.config.annotation.CorsRegistry;
            import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

            /**
             * CORS configuration.
             */
            @Configuration
            public class CorsConfig {

                /**
                 * Configures CORS mappings.
                 * @return configured Web Mvc Configurer
                 */
                @Bean
                public WebMvcConfigurer corsConfigurer() {
                    return new WebMvcConfigurer() {
                        @Override
                        public void addCorsMappings(@org.springframework.lang.NonNull CorsRegistry registry) {
                            registry.addMapping("/**")
                                    .allowedOriginPatterns("*") // TODO set specific origins when allowCredentials(true)
                                    .allowedMethods("*")
                                    .allowedHeaders("*")
                                    .exposedHeaders("X-Total-Count")
                                    .allowCredentials(true);
                        }
                    };
                }
            }
            """.formatted(configPackage);

        GeneratorSupport.writeFile(file, content, overwrite);
        log.debug("CorsConfig generated: {}", file.toAbsolutePath());
    }


    /**
     * Validates and trims generator path arguments.
     *
     * @param outputDir output directory
     * @param basePackage base package
     * @return normalized arguments
     */
    private String[] validateAndNormalizePaths(String outputDir, String basePackage) {
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