package com.sqldomaingen.generator;

import com.sqldomaingen.config.GeneratorConfig;
import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.util.PackageResolver;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Objects;

@Log4j2
@Component
public class SecurityGenerator {

    public void generate(
            String outputDir,
            String basePackage,
            GeneratorConfig generatorConfig
    ) {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");
        Objects.requireNonNull(generatorConfig, "generatorConfig must not be null");

        GeneratorConfig.Security security = generatorConfig.getSecurity();

        if (security == null || !security.isEnabled()) {
            log.debug("Security generation is disabled.");
            return;
        }

        String securityPackage = PackageResolver.resolvePackageName(
                basePackage,
                "security"
        );

        Path securityDir = GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(
                        outputDir,
                        basePackage,
                        "security"
                )
        );

        generatePasswordConfig(securityDir, securityPackage);
        generateSecurityConfig(securityDir, securityPackage);

        log.debug(
                "Security generation completed under: {}",
                securityDir.toAbsolutePath()
        );
    }

    /**
     * Generates the password encoder configuration used by Spring Security.
     *
     * @param securityDir target security package directory
     * @param securityPackage generated security package name
     */
    private void generatePasswordConfig(
            Path securityDir,
            String securityPackage
    ) {
        String content = """
                package %s;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
                import org.springframework.security.crypto.password.PasswordEncoder;

                @Configuration
                public class PasswordConfig {

                    @Bean
                    public PasswordEncoder passwordEncoder() {
                        return new BCryptPasswordEncoder();
                    }
                }
                """.formatted(securityPackage);

        Path file = securityDir.resolve("PasswordConfig.java");

        GeneratorSupport.writeFile(file, content);

        log.debug("Generated PasswordConfig: {}", file.toAbsolutePath());
    }

    /**
     * Generates the main Spring Security filter chain configuration.
     *
     * @param securityDir target security package directory
     * @param securityPackage generated security package name
     */
    private void generateSecurityConfig(
            Path securityDir,
            String securityPackage
    ) {
        String content = """
            package %s;

            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.security.config.annotation.web.builders.HttpSecurity;
            import org.springframework.security.config.http.SessionCreationPolicy;
            import org.springframework.security.web.SecurityFilterChain;

            @Configuration
            public class SecurityConfig {

                @Bean
                public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
                    http
                            .csrf(csrf -> csrf.disable())
                            .sessionManagement(session -> session
                                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                            .authorizeHttpRequests(auth -> auth
                                    .requestMatchers("/api/auth/**").permitAll()
                                    .anyRequest().authenticated());

                    return http.build();
                }
            }
            """.formatted(securityPackage);

        Path file = securityDir.resolve("SecurityConfig.java");

        GeneratorSupport.writeFile(file, content);

        log.debug("Generated SecurityConfig: {}", file.toAbsolutePath());
    }
}