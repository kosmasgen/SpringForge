package com.sqldomaingen.generator;

import com.sqldomaingen.config.GeneratorConfig;
import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.util.PackageResolver;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Generates security infrastructure classes.
 */
@Log4j2
@Component
public class SecurityGenerator {

    /**
     * Generates the JWT authentication infrastructure when security and JWT are enabled.
     *
     * <p>
     * Generated classes:
     * <ul>
     *     <li>JwtAuthenticationFilter</li>
     *     <li>RestAuthenticationEntryPoint</li>
     *     <li>RestAccessDeniedHandler</li>
     * </ul>
     *
     * @param outputDir base output directory
     * @param basePackage base Java package
     * @param generatorConfig generator configuration
     */
    public void generate(String outputDir, String basePackage, GeneratorConfig generatorConfig) {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");
        Objects.requireNonNull(generatorConfig, "generatorConfig must not be null");

        GeneratorConfig.Security security = generatorConfig.getSecurity();

        if (security == null || !security.isEnabled()) {
            log.debug("Security generation is disabled.");
            return;
        }

        if (security.getJwt() == null || !security.getJwt().isEnabled()) {
            log.debug("JWT generation is disabled.");
            return;
        }

        String securityPackage = PackageResolver.resolvePackageName(basePackage, "security");
        String servicePackage = PackageResolver.resolvePackageName(basePackage, "service");

        Path securityDir = GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(outputDir, basePackage, "security")
        );

        // Generate the JWT authentication filter.
        generateJwtAuthenticationFilter(securityDir, securityPackage, servicePackage);

        // Generate the handler used for unauthenticated requests.
        generateRestAuthenticationEntryPoint(securityDir, securityPackage, basePackage);

        // Generate the handler used for authenticated requests without sufficient permissions.
        generateRestAccessDeniedHandler(securityDir, securityPackage, basePackage);

        log.debug("Security generated under: {}", securityDir.toAbsolutePath());
    }

    /**
     * Generates the REST authentication entry point used for unauthenticated requests.
     *
     * @param securityDir target security package directory
     * @param securityPackage generated security package name
     * @param basePackage generated application base package
     */
    private void generateRestAuthenticationEntryPoint(Path securityDir, String securityPackage, String basePackage) {
        String utilPackage = PackageResolver.resolvePackageName(basePackage, "util");
        String exceptionPackage = PackageResolver.resolvePackageName(basePackage, "exception");
        StringBuilder builder = new StringBuilder();

        builder.append("package ").append(securityPackage).append(";\n\n");
        builder.append("import ").append(exceptionPackage).append(".ErrorMessages;\n");
        builder.append("import ").append(utilPackage).append(".MessageResolver;\n");
        builder.append("import jakarta.servlet.http.HttpServletRequest;\n");
        builder.append("import jakarta.servlet.http.HttpServletResponse;\n");
        builder.append("import lombok.RequiredArgsConstructor;\n");
        builder.append("import org.springframework.http.MediaType;\n");
        builder.append("import org.springframework.security.core.AuthenticationException;\n");
        builder.append("import org.springframework.security.web.AuthenticationEntryPoint;\n");
        builder.append("import org.springframework.stereotype.Component;\n\n");
        builder.append("import java.io.IOException;\n\n");

        builder.append("/**\n");
        builder.append(" * Returns a localized JSON response when authentication is required.\n");
        builder.append(" */\n");
        builder.append("@Component\n");
        builder.append("@RequiredArgsConstructor\n");
        builder.append("public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {\n\n");

        builder.append("    private final MessageResolver messageResolver;\n\n");

        builder.append("    /**\n");
        builder.append("     * Handles requests that require authentication.\n");
        builder.append("     *\n");
        builder.append("     * @param request current HTTP request\n");
        builder.append("     * @param response current HTTP response\n");
        builder.append("     * @param authException authentication exception\n");
        builder.append("     * @throws IOException when the response cannot be written\n");
        builder.append("     */\n");
        builder.append("    @Override\n");
        builder.append("    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {\n");
        builder.append("        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);\n");
        builder.append("        response.setContentType(MediaType.APPLICATION_JSON_VALUE);\n");
        builder.append("        response.setCharacterEncoding(\"UTF-8\");\n");
        builder.append("        String message = messageResolver.resolve(ErrorMessages.ERROR_UNAUTHORIZED);\n");
        builder.append("        response.getWriter().write(\"{\\\"message\\\":\\\"\" + message + \"\\\"}\");\n");
        builder.append("    }\n");

        builder.append("}\n");

        Path file = securityDir.resolve("RestAuthenticationEntryPoint.java");
        GeneratorSupport.writeFile(file, builder.toString());

        log.debug("Generated RestAuthenticationEntryPoint: {}", file.toAbsolutePath());
    }

    /**
     * Generates the REST access denied handler used for forbidden requests.
     *
     * @param securityDir target security package directory
     * @param securityPackage generated security package name
     * @param basePackage generated application base package
     */
    private void generateRestAccessDeniedHandler(Path securityDir, String securityPackage, String basePackage) {
        String utilPackage = PackageResolver.resolvePackageName(basePackage, "util");
        String exceptionPackage = PackageResolver.resolvePackageName(basePackage, "exception");
        StringBuilder builder = new StringBuilder();

        builder.append("package ").append(securityPackage).append(";\n\n");
        builder.append("import ").append(exceptionPackage).append(".ErrorMessages;\n");
        builder.append("import ").append(utilPackage).append(".MessageResolver;\n");
        builder.append("import jakarta.servlet.http.HttpServletRequest;\n");
        builder.append("import jakarta.servlet.http.HttpServletResponse;\n");
        builder.append("import lombok.RequiredArgsConstructor;\n");
        builder.append("import org.springframework.http.MediaType;\n");
        builder.append("import org.springframework.security.access.AccessDeniedException;\n");
        builder.append("import org.springframework.security.web.access.AccessDeniedHandler;\n");
        builder.append("import org.springframework.stereotype.Component;\n\n");
        builder.append("import java.io.IOException;\n\n");

        builder.append("/**\n");
        builder.append(" * Returns a localized JSON response when access to a resource is forbidden.\n");
        builder.append(" */\n");
        builder.append("@Component\n");
        builder.append("@RequiredArgsConstructor\n");
        builder.append("public class RestAccessDeniedHandler implements AccessDeniedHandler {\n\n");

        builder.append("    private final MessageResolver messageResolver;\n\n");

        builder.append("    /**\n");
        builder.append("     * Handles authenticated requests without sufficient permissions.\n");
        builder.append("     *\n");
        builder.append("     * @param request current HTTP request\n");
        builder.append("     * @param response current HTTP response\n");
        builder.append("     * @param accessDeniedException access denied exception\n");
        builder.append("     * @throws IOException when the response cannot be written\n");
        builder.append("     */\n");
        builder.append("    @Override\n");
        builder.append("    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException {\n");
        builder.append("        response.setStatus(HttpServletResponse.SC_FORBIDDEN);\n");
        builder.append("        response.setContentType(MediaType.APPLICATION_JSON_VALUE);\n");
        builder.append("        response.setCharacterEncoding(\"UTF-8\");\n");
        builder.append("        String message = messageResolver.resolve(ErrorMessages.ERROR_FORBIDDEN);\n");
        builder.append("        response.getWriter().write(\"{\\\"message\\\":\\\"\" + message + \"\\\"}\");\n");
        builder.append("    }\n");

        builder.append("}\n");

        Path file = securityDir.resolve("RestAccessDeniedHandler.java");
        GeneratorSupport.writeFile(file, builder.toString());

        log.debug("Generated RestAccessDeniedHandler: {}", file.toAbsolutePath());
    }

    /**
     * Generates the JWT authentication filter.
     *
     * @param securityDir target security package directory
     * @param securityPackage generated security package name
     * @param servicePackage generated service package name
     */
    private void generateJwtAuthenticationFilter(Path securityDir, String securityPackage, String servicePackage) {
        StringBuilder stringBuilder = new StringBuilder();

        appendJwtFilterPackageAndImports(stringBuilder, securityPackage, servicePackage);
        appendJwtFilterClassDeclaration(stringBuilder);
        appendJwtFilterFields(stringBuilder);
        appendJwtFilterDoFilterInternal(stringBuilder);

        stringBuilder.append("}\n");

        Path file = securityDir.resolve("JwtAuthenticationFilter.java");
        GeneratorSupport.writeFile(file, stringBuilder.toString());

        log.debug("Generated JwtAuthenticationFilter: {}", file.toAbsolutePath());
    }

    /**
     * Appends the package declaration and imports required by the JWT filter.
     *
     * @param stringBuilder target source builder
     * @param securityPackage generated security package name
     * @param servicePackage generated service package name
     */
    private void appendJwtFilterPackageAndImports(
            StringBuilder stringBuilder,
            String securityPackage,
            String servicePackage
    ) {
        stringBuilder.append("package ").append(securityPackage).append(";\n\n");

        stringBuilder.append("import ").append(servicePackage).append(".CustomUserDetailsService;\n");
        stringBuilder.append("import ").append(servicePackage).append(".JwtService;\n");
        stringBuilder.append("import io.jsonwebtoken.JwtException;\n");
        stringBuilder.append("import jakarta.servlet.FilterChain;\n");
        stringBuilder.append("import jakarta.servlet.ServletException;\n");
        stringBuilder.append("import jakarta.servlet.http.HttpServletRequest;\n");
        stringBuilder.append("import jakarta.servlet.http.HttpServletResponse;\n");
        stringBuilder.append("import lombok.RequiredArgsConstructor;\n");
        stringBuilder.append("import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;\n");
        stringBuilder.append("import org.springframework.security.core.context.SecurityContextHolder;\n");
        stringBuilder.append("import org.springframework.security.core.userdetails.UserDetails;\n");
        stringBuilder.append("import org.springframework.security.core.userdetails.UsernameNotFoundException;\n");
        stringBuilder.append("import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;\n");
        stringBuilder.append("import org.springframework.stereotype.Component;\n");
        stringBuilder.append("import org.springframework.web.filter.OncePerRequestFilter;\n");
        stringBuilder.append("\n");
        stringBuilder.append("import java.io.IOException;\n");
        stringBuilder.append("\n");
    }

    /**
     * Appends the JWT filter class declaration.
     *
     * @param stringBuilder target source builder
     */
    private void appendJwtFilterClassDeclaration(StringBuilder stringBuilder) {
        stringBuilder.append("/**\n");
        stringBuilder.append(" * Authenticates requests containing a valid JWT Bearer token.\n");
        stringBuilder.append(" */\n");
        stringBuilder.append("@Component\n");
        stringBuilder.append("@RequiredArgsConstructor\n");
        stringBuilder.append("public class JwtAuthenticationFilter extends OncePerRequestFilter {\n");
        stringBuilder.append("\n");
    }

    /**
     * Appends the constants and dependencies used by the JWT filter.
     *
     * @param stringBuilder target source builder
     */
    private void appendJwtFilterFields(StringBuilder stringBuilder) {
        stringBuilder.append("    private static final String AUTHORIZATION_HEADER = \"Authorization\";\n");
        stringBuilder.append("    private static final String BEARER_PREFIX = \"Bearer \";\n");
        stringBuilder.append("\n");
        stringBuilder.append("    private final JwtService jwtService;\n");
        stringBuilder.append("    private final CustomUserDetailsService customUserDetailsService;\n");
        stringBuilder.append("\n");
    }

    /**
     * Appends the request filtering and JWT authentication logic.
     *
     * @param stringBuilder target source builder
     */
    private void appendJwtFilterDoFilterInternal(StringBuilder stringBuilder) {
        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Extracts and validates the JWT token and initializes the Spring Security context.\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param request current HTTP request\n");
        stringBuilder.append("     * @param response current HTTP response\n");
        stringBuilder.append("     * @param filterChain current filter chain\n");
        stringBuilder.append("     * @throws ServletException when request filtering fails\n");
        stringBuilder.append("     * @throws IOException when request processing fails\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    @Override\n");
        stringBuilder.append("    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)\n");
        stringBuilder.append("            throws ServletException, IOException {\n");
        stringBuilder.append("\n");
        stringBuilder.append("        String authorizationHeader = request.getHeader(AUTHORIZATION_HEADER);\n");
        stringBuilder.append("\n");
        stringBuilder.append("        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {\n");
        stringBuilder.append("            filterChain.doFilter(request, response);\n");
        stringBuilder.append("            return;\n");
        stringBuilder.append("        }\n");
        stringBuilder.append("\n");
        stringBuilder.append("        String token = authorizationHeader.substring(BEARER_PREFIX.length());\n");
        stringBuilder.append("\n");
        stringBuilder.append("        try {\n");
        stringBuilder.append("            String username = jwtService.extractUsername(token);\n");
        stringBuilder.append("\n");
        stringBuilder.append("            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {\n");
        stringBuilder.append("                UserDetails userDetails = customUserDetailsService.loadUserByUsername(username);\n");
        stringBuilder.append("\n");
        stringBuilder.append("                if (jwtService.isTokenValid(token, userDetails.getUsername())) {\n");
        stringBuilder.append("                    UsernamePasswordAuthenticationToken authentication =\n");
        stringBuilder.append("                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());\n");
        stringBuilder.append("\n");
        stringBuilder.append("                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));\n");
        stringBuilder.append("                    SecurityContextHolder.getContext().setAuthentication(authentication);\n");
        stringBuilder.append("                }\n");
        stringBuilder.append("            }\n");
        stringBuilder.append("        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException exception) {\n");
        stringBuilder.append("            SecurityContextHolder.clearContext();\n");
        stringBuilder.append("        }\n");
        stringBuilder.append("\n");
        stringBuilder.append("        filterChain.doFilter(request, response);\n");
        stringBuilder.append("    }\n");
        stringBuilder.append("\n");
    }
}