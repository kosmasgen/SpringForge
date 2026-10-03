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
        generateJwtService(securityDir, securityPackage);
        generateCustomUserDetailsService(securityDir, securityPackage, basePackage, security);

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

    /**
     * Generates the JWT service responsible for creating, parsing and validating
     * JSON Web Tokens used by the generated application.
     *
     * @param securityDir target security package directory
     * @param securityPackage generated security package name
     */
    private void generateJwtService(
            Path securityDir,
            String securityPackage
    ) {
        String content = """
            package %s;

            import io.jsonwebtoken.Claims;
            import io.jsonwebtoken.Jwts;
            import io.jsonwebtoken.io.Decoders;
            import io.jsonwebtoken.security.Keys;
            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.stereotype.Service;

            import javax.crypto.SecretKey;
            import java.util.Date;
            import java.util.function.Function;

            /**
             * Provides JWT creation, parsing and validation operations.
             */
            @Service
            public class JwtService {

                private final SecretKey signingKey;
                private final long expirationMinutes;

                /**
                 * Creates the JWT service using the configured secret and token lifetime.
                 *
                 * @param secret Base64 encoded JWT signing secret
                 * @param expirationMinutes access token lifetime in minutes
                 */
                public JwtService(
                        @Value("${security.jwt.secret}") String secret,
                        @Value("${security.jwt.expiration-minutes}") long expirationMinutes
                ) {
                    this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
                    this.expirationMinutes = expirationMinutes;
                }

                /**
                 * Generates an access token for the supplied username.
                 *
                 * @param username authenticated username
                 * @return generated JWT access token
                 */
                public String generateToken(String username) {
                    Date issuedAt = new Date();
                    Date expiration = new Date(
                            issuedAt.getTime() + expirationMinutes * 60_000L
                    );

                    return Jwts.builder()
                            .subject(username)
                            .issuedAt(issuedAt)
                            .expiration(expiration)
                            .signWith(signingKey)
                            .compact();
                }

                /**
                 * Extracts the username stored in the token subject.
                 *
                 * @param token JWT access token
                 * @return token subject
                 */
                public String extractUsername(String token) {
                    return extractClaim(token, Claims::getSubject);
                }

                /**
                 * Determines whether a token belongs to the supplied username
                 * and has not expired.
                 *
                 * @param token JWT access token
                 * @param username expected username
                 * @return true when the token is valid
                 */
                public boolean isTokenValid(String token, String username) {
                    String tokenUsername = extractUsername(token);

                    return tokenUsername.equals(username)
                            && !isTokenExpired(token);
                }

                /**
                 * Extracts a claim from the supplied token.
                 *
                 * @param token JWT access token
                 * @param claimsResolver claim extraction function
                 * @param <T> extracted claim type
                 * @return extracted claim value
                 */
                private <T> T extractClaim(
                        String token,
                        Function<Claims, T> claimsResolver
                ) {
                    Claims claims = extractAllClaims(token);
                    return claimsResolver.apply(claims);
                }

                /**
                 * Parses and verifies all claims contained in the supplied token.
                 *
                 * @param token JWT access token
                 * @return verified token claims
                 */
                private Claims extractAllClaims(String token) {
                    return Jwts.parser()
                            .verifyWith(signingKey)
                            .build()
                            .parseSignedClaims(token)
                            .getPayload();
                }

                /**
                 * Determines whether the supplied token has expired.
                 *
                 * @param token JWT access token
                 * @return true when the token expiration is before the current time
                 */
                private boolean isTokenExpired(String token) {
                    Date expiration = extractClaim(token, Claims::getExpiration);
                    return expiration.before(new Date());
                }
            }
            """.formatted(securityPackage);

        Path file = securityDir.resolve("JwtService.java");

        GeneratorSupport.writeFile(file, content);

        log.debug("Generated JwtService: {}", file.toAbsolutePath());
    }

    /**
     * Generates the Spring Security UserDetailsService implementation used
     * to load authentication users from the configured security user table.
     *
     * @param securityDir target security package directory
     * @param securityPackage generated security package name
     * @param basePackage generated application base package
     * @param security security generator configuration
     */
    private void generateCustomUserDetailsService(
            Path securityDir,
            String securityPackage,
            String basePackage,
            GeneratorConfig.Security security
    ) {
        String normalizedUserTable =
                GeneratorSupport.normalizeTableName(security.getUserTable());

        String entityName =
                com.sqldomaingen.util.NamingConverter.toPascalCase(normalizedUserTable);

        String repositoryName = entityName + "Repository";

        String entityPackage =
                PackageResolver.resolvePackageName(basePackage, "entity");

        String repositoryPackage =
                PackageResolver.resolvePackageName(basePackage, "repository");

        String usernameField =
                com.sqldomaingen.util.NamingConverter.toCamelCase(
                        security.getUsernameField()
                );

        String passwordField =
                com.sqldomaingen.util.NamingConverter.toCamelCase(
                        security.getPasswordField()
                );

        String usernameMethodSuffix =
                com.sqldomaingen.util.NamingConverter.toPascalCase(usernameField);

        String passwordMethodSuffix =
                com.sqldomaingen.util.NamingConverter.toPascalCase(passwordField);

        String repositoryVariable =
                com.sqldomaingen.util.NamingConverter.decapitalizeFirstLetter(repositoryName);

        String content = """
            package %s;

            import %s.%s;
            import %s.%s;
            import lombok.RequiredArgsConstructor;
            import org.springframework.security.core.userdetails.User;
            import org.springframework.security.core.userdetails.UserDetails;
            import org.springframework.security.core.userdetails.UserDetailsService;
            import org.springframework.security.core.userdetails.UsernameNotFoundException;
            import org.springframework.stereotype.Service;

            /**
             * Loads authentication users from the configured security user repository.
             */
            @Service
            @RequiredArgsConstructor
            public class CustomUserDetailsService implements UserDetailsService {

                private final %s %s;

                /**
                 * Loads a user by the configured authentication field.
                 *
                 * @param username authentication field value
                 * @return Spring Security user details
                 * @throws UsernameNotFoundException when no matching user exists
                 */
                @Override
                public UserDetails loadUserByUsername(String username)
                        throws UsernameNotFoundException {

                    %s user = %s.findBy%s(username)
                            .orElseThrow(() -> new UsernameNotFoundException(
                                    "User not found with %s: " + username
                            ));

                    return User.withUsername(user.get%s())
                            .password(user.get%s())
                            .authorities("USER")
                            .build();
                }
            }
            """.formatted(
                securityPackage,
                entityPackage,
                entityName,
                repositoryPackage,
                repositoryName,
                repositoryName,
                repositoryVariable,
                entityName,
                repositoryVariable,
                usernameMethodSuffix,
                usernameField,
                usernameMethodSuffix,
                passwordMethodSuffix
        );

        Path file = securityDir.resolve("CustomUserDetailsService.java");

        GeneratorSupport.writeFile(file, content);

        log.debug(
                "Generated CustomUserDetailsService: {}",
                file.toAbsolutePath()
        );
    }
}