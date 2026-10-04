package com.sqldomaingen.generator;

import com.sqldomaingen.config.GeneratorConfig;
import com.sqldomaingen.util.Constants;
import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.util.JavaTypeSupport;
import com.sqldomaingen.util.NamingConverter;
import com.sqldomaingen.util.PackageResolver;
import com.sqldomaingen.util.PrimaryKeySupport;
import com.sqldomaingen.model.Table;
import lombok.extern.log4j.Log4j2;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Generates Service interfaces and ServiceImpl classes (domain style),
 * using the project's base package and placing output under src/main/java.
 * Conventions:
 * service package:     {basePackage}.service
 * serviceImpl package: {basePackage}.serviceImpl
 * DTO suffix:          Dto
 * Repository suffix:   Repository
 * Mapper suffix:       Mapper
 */
@Log4j2
public class ServiceGenerator {

    private final ServiceImplGenerator serviceImplGenerator = new ServiceImplGenerator();

    /**
     * Generates service interfaces and implementations for all parsed tables.
     *
     * @param tables parsed SQL tables
     * @param outputDir base output directory
     * @param basePackage base package
     */
    public void generateAllServices(List<Table> tables, String outputDir, String basePackage, GeneratorConfig generatorConfig) {
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");
        Objects.requireNonNull(generatorConfig, "generatorConfig must not be null");

        Path serviceDir = GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(outputDir, basePackage, "service")
        );
        Path serviceImplDir = GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(outputDir, basePackage, "serviceImpl")
        );

        for (Table table : tables) {
            String entityName = NamingConverter.toPascalCase(
                    GeneratorSupport.normalizeTableName(table.getName())
            );

            String serviceInterfaceCode = generateServiceInterface(table, basePackage);
            GeneratorSupport.writeFile(serviceDir.resolve(entityName + "Service.java"), serviceInterfaceCode);

            String serviceImplCode = serviceImplGenerator.generateServiceImpl(table, basePackage);
            GeneratorSupport.writeFile(serviceImplDir.resolve(entityName + "ServiceImpl.java"), serviceImplCode);
        }

        GeneratorConfig.Security security = generatorConfig.getSecurity();

        if (security != null && security.isEnabled()) {
            generateAuthService(serviceDir, basePackage, security);
            generateCustomUserDetailsService(serviceDir, basePackage, security);
            generateAuthServiceImpl(tables, serviceImplDir, basePackage, security);

            // Generate JWT-specific services only when JWT authentication is enabled.
            if (security.getJwt() != null && security.getJwt().isEnabled()) {
                generateJwtService(serviceDir, basePackage);
            }
        }

        log.debug("Services generated under: {}", serviceDir.getParent().toAbsolutePath());
    }

    /**
     * Generates the authentication service contract.
     *
     * @param serviceDir target service directory
     * @param basePackage base Java package
     * @param security security generator configuration
     */
    private void generateAuthService(Path serviceDir, String basePackage, GeneratorConfig.Security security) {
        Objects.requireNonNull(serviceDir, "serviceDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");
        Objects.requireNonNull(security, "security must not be null");

        // Resolve packages used by the generated authentication service.
        String servicePackage = PackageResolver.resolvePackageName(basePackage, "service");
        String dtoPackage = PackageResolver.resolvePackageName(basePackage, Constants.DTO_PACKAGE);

        StringBuilder stringBuilder = new StringBuilder();

        stringBuilder.append("package ").append(servicePackage).append(";\n\n");

        stringBuilder.append("import ").append(dtoPackage).append(".LoginRequest;\n");
        stringBuilder.append("import ").append(dtoPackage).append(".LoginResponse;\n");
        stringBuilder.append("import ").append(dtoPackage).append(".RegisterRequest;\n\n");

        stringBuilder.append("/**\n");
        stringBuilder.append(" * Service contract for user registration and authentication.\n");
        stringBuilder.append(" */\n");
        stringBuilder.append("public interface AuthService {\n\n");

        appendRegisterMethodSignature(stringBuilder);
        appendLoginMethodSignature(stringBuilder);

        stringBuilder.append("}\n");

        GeneratorSupport.writeFile(serviceDir.resolve("AuthService.java"), stringBuilder.toString());
    }


    /**
     * Generates the authentication service implementation.
     *
     * @param tables parsed SQL tables
     * @param serviceImplDir target service implementation directory
     * @param basePackage base Java package
     * @param security security generator configuration
     */
    private void generateAuthServiceImpl(List<Table> tables, Path serviceImplDir, String basePackage, GeneratorConfig.Security security) {
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(serviceImplDir, "serviceImplDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");
        Objects.requireNonNull(security, "security must not be null");

        Table userTable = tables.stream()
                .filter(Objects::nonNull)
                .filter(table -> GeneratorSupport.normalizeTableName(table.getName()).equalsIgnoreCase(GeneratorSupport.normalizeTableName(security.getUserTable())))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Security user table not found: " + security.getUserTable()));

        String authServiceImplCode = serviceImplGenerator.generateAuthServiceImpl(userTable, basePackage, security);
        GeneratorSupport.writeFile(serviceImplDir.resolve("AuthServiceImpl.java"), authServiceImplCode);
    }

    /**
     * Generates the JWT service used to create, parse and validate access tokens.
     *
     * @param serviceDir target service directory
     * @param basePackage base Java package
     */
    private void generateJwtService(Path serviceDir, String basePackage) {
        Objects.requireNonNull(serviceDir, "serviceDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        String servicePackage = PackageResolver.resolvePackageName(basePackage, "service");
        StringBuilder stringBuilder = new StringBuilder();

        appendJwtServicePackageAndImports(stringBuilder, servicePackage);
        appendJwtServiceClassDeclaration(stringBuilder);
        appendJwtServiceFields(stringBuilder);
        appendJwtServiceConstructor(stringBuilder);
        appendGenerateTokenMethod(stringBuilder);
        appendExtractUsernameMethod(stringBuilder);
        appendIsTokenValidMethod(stringBuilder);
        appendExtractClaimMethod(stringBuilder);
        appendExtractAllClaimsMethod(stringBuilder);
        appendIsTokenExpiredMethod(stringBuilder);

        stringBuilder.append("}\n");

        GeneratorSupport.writeFile(serviceDir.resolve("JwtService.java"), stringBuilder.toString());
    }

    private void appendJwtServicePackageAndImports(StringBuilder stringBuilder, String servicePackage) {
        stringBuilder.append("package ").append(servicePackage).append(";\n\n");

        stringBuilder.append("import io.jsonwebtoken.Claims;\n");
        stringBuilder.append("import io.jsonwebtoken.Jwts;\n");
        stringBuilder.append("import io.jsonwebtoken.io.Decoders;\n");
        stringBuilder.append("import io.jsonwebtoken.security.Keys;\n");
        stringBuilder.append("import org.springframework.beans.factory.annotation.Value;\n");
        stringBuilder.append("import org.springframework.stereotype.Service;\n\n");

        stringBuilder.append("import javax.crypto.SecretKey;\n");
        stringBuilder.append("import java.util.Date;\n");
        stringBuilder.append("import java.util.function.Function;\n\n");
    }

    private void appendJwtServiceClassDeclaration(StringBuilder stringBuilder) {
        stringBuilder.append("/**\n");
        stringBuilder.append(" * Provides JWT creation, parsing and validation operations.\n");
        stringBuilder.append(" */\n");
        stringBuilder.append("@Service\n");
        stringBuilder.append("public class JwtService {\n\n");
    }

    private void appendJwtServiceFields(StringBuilder stringBuilder) {
        stringBuilder.append("    private final SecretKey signingKey;\n");
        stringBuilder.append("    private final long expirationMinutes;\n\n");
    }

    private void appendJwtServiceConstructor(StringBuilder stringBuilder) {
        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Creates the JWT service using the configured signing secret\n");
        stringBuilder.append("     * and access token lifetime.\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param secret Base64 encoded JWT signing secret\n");
        stringBuilder.append("     * @param expirationMinutes access token lifetime in minutes\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    public JwtService(\n");
        stringBuilder.append("            @Value(\"${security.jwt.secret}\") String secret,\n");
        stringBuilder.append("            @Value(\"${security.jwt.expiration-minutes}\") long expirationMinutes\n");
        stringBuilder.append("    ) {\n");
        stringBuilder.append("        this.signingKey = Keys.hmacShaKeyFor(\n");
        stringBuilder.append("                Decoders.BASE64.decode(secret)\n");
        stringBuilder.append("        );\n");
        stringBuilder.append("        this.expirationMinutes = expirationMinutes;\n");
        stringBuilder.append("    }\n\n");
    }

    private void appendGenerateTokenMethod(StringBuilder stringBuilder) {
        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Generates an access token for the supplied username.\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param username authenticated username\n");
        stringBuilder.append("     * @return generated JWT access token\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    public String generateToken(String username) {\n");
        stringBuilder.append("        Date issuedAt = new Date();\n\n");
        stringBuilder.append("        Date expiration = new Date(\n");
        stringBuilder.append("                issuedAt.getTime() + expirationMinutes * 60_000L\n");
        stringBuilder.append("        );\n\n");
        stringBuilder.append("        return Jwts.builder()\n");
        stringBuilder.append("                .subject(username)\n");
        stringBuilder.append("                .issuedAt(issuedAt)\n");
        stringBuilder.append("                .expiration(expiration)\n");
        stringBuilder.append("                .signWith(signingKey)\n");
        stringBuilder.append("                .compact();\n");
        stringBuilder.append("    }\n\n");
    }

    private void appendExtractUsernameMethod(StringBuilder stringBuilder) {
        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Extracts the username stored in the token subject.\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param token JWT access token\n");
        stringBuilder.append("     * @return token subject\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    public String extractUsername(String token) {\n");
        stringBuilder.append("        return extractClaim(\n");
        stringBuilder.append("                token,\n");
        stringBuilder.append("                Claims::getSubject\n");
        stringBuilder.append("        );\n");
        stringBuilder.append("    }\n\n");
    }

    private void appendIsTokenValidMethod(StringBuilder stringBuilder) {
        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Determines whether the supplied token belongs to the expected\n");
        stringBuilder.append("     * username and has not expired.\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param token JWT access token\n");
        stringBuilder.append("     * @param username expected username\n");
        stringBuilder.append("     * @return true when the token is valid\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    public boolean isTokenValid(\n");
        stringBuilder.append("            String token,\n");
        stringBuilder.append("            String username\n");
        stringBuilder.append("    ) {\n");
        stringBuilder.append("        String tokenUsername = extractUsername(token);\n\n");
        stringBuilder.append("        return tokenUsername.equals(username)\n");
        stringBuilder.append("                && !isTokenExpired(token);\n");
        stringBuilder.append("    }\n\n");
    }

    private void appendExtractClaimMethod(StringBuilder stringBuilder) {
        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Extracts a specific claim from the supplied token.\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param token JWT access token\n");
        stringBuilder.append("     * @param claimsResolver claim extraction function\n");
        stringBuilder.append("     * @param <T> extracted claim type\n");
        stringBuilder.append("     * @return extracted claim value\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    private <T> T extractClaim(\n");
        stringBuilder.append("            String token,\n");
        stringBuilder.append("            Function<Claims, T> claimsResolver\n");
        stringBuilder.append("    ) {\n");
        stringBuilder.append("        Claims claims = extractAllClaims(token);\n\n");
        stringBuilder.append("        return claimsResolver.apply(claims);\n");
        stringBuilder.append("    }\n\n");
    }

    private void appendExtractAllClaimsMethod(StringBuilder stringBuilder) {
        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Parses and verifies all claims contained in the supplied token.\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param token JWT access token\n");
        stringBuilder.append("     * @return verified token claims\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    private Claims extractAllClaims(String token) {\n");
        stringBuilder.append("        return Jwts.parser()\n");
        stringBuilder.append("                .verifyWith(signingKey)\n");
        stringBuilder.append("                .build()\n");
        stringBuilder.append("                .parseSignedClaims(token)\n");
        stringBuilder.append("                .getPayload();\n");
        stringBuilder.append("    }\n\n");
    }

    private void appendIsTokenExpiredMethod(StringBuilder stringBuilder) {
        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Determines whether the supplied token has expired.\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param token JWT access token\n");
        stringBuilder.append("     * @return true when the token has expired\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    private boolean isTokenExpired(String token) {\n");
        stringBuilder.append("        Date expiration = extractClaim(\n");
        stringBuilder.append("                token,\n");
        stringBuilder.append("                Claims::getExpiration\n");
        stringBuilder.append("        );\n\n");
        stringBuilder.append("        return expiration.before(new Date());\n");
        stringBuilder.append("    }\n");
    }

    /**
     * Generates the Spring Security UserDetailsService implementation used
     * to load authentication users from the configured user table.
     *
     * @param serviceDir target service directory
     * @param basePackage base Java package
     * @param security security generator configuration
     */
    private void generateCustomUserDetailsService(Path serviceDir, String basePackage, GeneratorConfig.Security security) {
        Objects.requireNonNull(serviceDir, "serviceDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");
        Objects.requireNonNull(security, "security must not be null");

        String servicePackage = PackageResolver.resolvePackageName(basePackage, "service");
        String entityPackage = PackageResolver.resolvePackageName(basePackage, "entity");
        String repositoryPackage = PackageResolver.resolvePackageName(basePackage, "repository");

        String normalizedUserTable = GeneratorSupport.normalizeTableName(security.getUserTable());
        String entityName = NamingConverter.toPascalCase(normalizedUserTable);
        String repositoryName = entityName + "Repository";
        String repositoryVariableName = NamingConverter.decapitalizeFirstLetter(repositoryName);

        String usernameField = NamingConverter.toCamelCase(security.getUsernameField());
        String passwordField = NamingConverter.toCamelCase(security.getPasswordField());
        String usernameMethodSuffix = NamingConverter.toPascalCase(usernameField);
        String passwordMethodSuffix = NamingConverter.toPascalCase(passwordField);

        StringBuilder stringBuilder = new StringBuilder();

        appendCustomUserDetailsPackageAndImports(
                stringBuilder, servicePackage, entityPackage, repositoryPackage, entityName, repositoryName
        );
        appendCustomUserDetailsClassDeclaration(stringBuilder);
        appendCustomUserDetailsFields(stringBuilder, repositoryName, repositoryVariableName);
        appendLoadUserByUsernameMethod(
                stringBuilder, entityName, repositoryVariableName,
                usernameMethodSuffix, usernameField, passwordMethodSuffix
        );

        stringBuilder.append("}\n");

        GeneratorSupport.writeFile(serviceDir.resolve("CustomUserDetailsService.java"), stringBuilder.toString());
    }

    private void appendCustomUserDetailsPackageAndImports(
            StringBuilder stringBuilder,
            String servicePackage,
            String entityPackage,
            String repositoryPackage,
            String entityName,
            String repositoryName
    ) {
        stringBuilder.append("package ").append(servicePackage).append(";\n\n");

        stringBuilder.append("import ").append(entityPackage).append(".").append(entityName).append(";\n");
        stringBuilder.append("import ").append(repositoryPackage).append(".").append(repositoryName).append(";\n");
        stringBuilder.append("import lombok.RequiredArgsConstructor;\n");
        stringBuilder.append("import org.springframework.security.core.userdetails.User;\n");
        stringBuilder.append("import org.springframework.security.core.userdetails.UserDetails;\n");
        stringBuilder.append("import org.springframework.security.core.userdetails.UserDetailsService;\n");
        stringBuilder.append("import org.springframework.security.core.userdetails.UsernameNotFoundException;\n");
        stringBuilder.append("import org.springframework.stereotype.Service;\n\n");
    }

    private void appendCustomUserDetailsClassDeclaration(StringBuilder stringBuilder) {
        stringBuilder.append("/**\n");
        stringBuilder.append(" * Loads authentication users from the configured user repository.\n");
        stringBuilder.append(" */\n");
        stringBuilder.append("@Service\n");
        stringBuilder.append("@RequiredArgsConstructor\n");
        stringBuilder.append("public class CustomUserDetailsService implements UserDetailsService {\n\n");
    }

    private void appendCustomUserDetailsFields(
            StringBuilder stringBuilder,
            String repositoryName,
            String repositoryVariableName
    ) {
        stringBuilder.append("    private final ")
                .append(repositoryName)
                .append(" ")
                .append(repositoryVariableName)
                .append(";\n\n");
    }

    private void appendLoadUserByUsernameMethod(
            StringBuilder stringBuilder,
            String entityName,
            String repositoryVariableName,
            String usernameMethodSuffix,
            String usernameField,
            String passwordMethodSuffix
    ) {
        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Loads a user using the configured authentication field.\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param username authentication field value\n");
        stringBuilder.append("     * @return Spring Security user details\n");
        stringBuilder.append("     * @throws UsernameNotFoundException when no matching user exists\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    @Override\n");
        stringBuilder.append("    public UserDetails loadUserByUsername(String username)\n");
        stringBuilder.append("            throws UsernameNotFoundException {\n\n");

        stringBuilder.append("        ").append(entityName).append(" user = ")
                .append(repositoryVariableName)
                .append(".findBy")
                .append(usernameMethodSuffix)
                .append("(username)\n");
        stringBuilder.append("                .orElseThrow(() -> new UsernameNotFoundException(\n");
        stringBuilder.append("                        \"User not found with ")
                .append(usernameField)
                .append(": \" + username\n");
        stringBuilder.append("                ));\n\n");

        stringBuilder.append("        return User.withUsername(\n");
        stringBuilder.append("                        user.get").append(usernameMethodSuffix).append("()\n");
        stringBuilder.append("                )\n");
        stringBuilder.append("                .password(\n");
        stringBuilder.append("                        user.get").append(passwordMethodSuffix).append("()\n");
        stringBuilder.append("                )\n");
        stringBuilder.append("                .authorities(\"USER\")\n");
        stringBuilder.append("                .build();\n");
        stringBuilder.append("    }\n");
    }

    /**
     * Appends the user registration method signature.
     *
     * @param stringBuilder target source builder
     */
    private void appendRegisterMethodSignature(StringBuilder stringBuilder) {
        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Registers a new application user.\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param request user registration request\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    void register(RegisterRequest request);\n\n");
    }

    /**
     * Appends the user login method signature.
     *
     * @param stringBuilder target source builder
     */
    private void appendLoginMethodSignature(StringBuilder stringBuilder) {
        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Authenticates an existing application user.\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param request user login request\n");
        stringBuilder.append("     * @return authentication response containing the generated access token\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    LoginResponse login(LoginRequest request);\n\n");
    }

    /**
     * Generates the Service interface for one entity.
     *
     * @param table source table metadata
     * @param basePackage base package
     * @return Java source code
     */
    public String generateServiceInterface(Table table, String basePackage) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        String entityName = buildEntityName(table);
        String dtoName = entityName + Constants.DTO_SUFFIX;
        String servicePackage = PackageResolver.resolvePackageName(basePackage, "service");
        String dtoPackage = PackageResolver.resolvePackageName(basePackage, "dto");
        String modelPackage = PackageResolver.resolvePackageName(basePackage, "entity");

        List<com.sqldomaingen.model.Column> primaryKeyColumns = getPrimaryKeyColumns(table);
        boolean compositePrimaryKey = primaryKeyColumns.size() > 1;

        PrimaryKeySupport.TypeRef primaryKeyType = compositePrimaryKey
                ? null
                : PrimaryKeySupport.resolvePrimaryKeyTypeRef(table, entityName, modelPackage);

        ServiceMethodParameters serviceMethodParameters = buildServiceMethodParameters(
                primaryKeyColumns,
                primaryKeyType,
                dtoName,
                compositePrimaryKey
        );

        java.util.LinkedHashSet<String> importLines = buildServiceInterfaceImports(
                dtoPackage,
                dtoName,
                primaryKeyColumns,
                primaryKeyType,
                compositePrimaryKey
        );

        StringBuilder stringBuilder = new StringBuilder();

        appendServiceInterfacePackageAndImports(stringBuilder, servicePackage, importLines);
        appendServiceInterfaceHeader(stringBuilder, entityName);
        appendGetAllMethodSignature(stringBuilder, entityName, dtoName);
        appendGetByIdMethodSignature(
                stringBuilder,
                entityName,
                dtoName,
                primaryKeyColumns,
                compositePrimaryKey,
                serviceMethodParameters.idMethodParameters()
        );
        appendCreateMethodSignature(stringBuilder, entityName, dtoName);
        appendUpdateMethodSignature(
                stringBuilder,
                entityName,
                dtoName,
                primaryKeyColumns,
                compositePrimaryKey,
                serviceMethodParameters.updateMethodParameters()
        );
        appendDeleteMethodSignature(
                stringBuilder,
                entityName,
                primaryKeyColumns,
                compositePrimaryKey,
                serviceMethodParameters.idMethodParameters()
        );
        appendServiceInterfaceFooter(stringBuilder);

        return stringBuilder.toString();
    }

    /**
     * Holds generated service method parameter declarations.
     *
     * @param idMethodParameters parameters used by get/delete methods
     * @param updateMethodParameters parameters used by update method
     */
    private record ServiceMethodParameters(
            String idMethodParameters,
            String updateMethodParameters
    ) {
    }

    /**
     * Builds the generated entity name for a table.
     *
     * @param table source table
     * @return entity simple name
     */
    private String buildEntityName(Table table) {
        return NamingConverter.toPascalCase(
                GeneratorSupport.normalizeTableName(table.getName())
        );
    }

    /**
     * Returns all primary key columns from the table.
     *
     * @param table source table
     * @return primary key columns
     */
    private List<com.sqldomaingen.model.Column> getPrimaryKeyColumns(Table table) {
        return table.getColumns().stream()
                .filter(Objects::nonNull)
                .filter(com.sqldomaingen.model.Column::isPrimaryKey)
                .toList();
    }

    /**
     * Builds all imports required by a generated service interface.
     *
     * @param dtoPackage DTO package name
     * @param dtoName DTO simple name
     * @param primaryKeyColumns primary key columns
     * @param primaryKeyType primary key type reference
     * @param compositePrimaryKey true when the entity uses a composite primary key
     * @return ordered import lines
     */
    private LinkedHashSet<String> buildServiceInterfaceImports(
            String dtoPackage,
            String dtoName,
            List<com.sqldomaingen.model.Column> primaryKeyColumns,
            PrimaryKeySupport.TypeRef primaryKeyType,
            boolean compositePrimaryKey
    ) {
        LinkedHashSet<String> importLines = new LinkedHashSet<>();

        importLines.add("import " + dtoPackage + "." + dtoName + ";");
        importLines.add("import java.util.List;");

        if (!compositePrimaryKey && primaryKeyType.importLine() != null && !primaryKeyType.importLine().isBlank()) {
            importLines.add(primaryKeyType.importLine());
            return importLines;
        }

        if (compositePrimaryKey) {
            for (com.sqldomaingen.model.Column primaryKeyColumn : primaryKeyColumns) {
                String importLine = JavaTypeSupport.resolveImportLine(primaryKeyColumn.getJavaType());

                if (importLine != null && !importLine.isBlank()) {
                    importLines.add(importLine);
                }
            }
        }

        return importLines;
    }

    /**
     * Builds method parameter declarations for service interface methods.
     *
     * @param primaryKeyColumns primary key columns
     * @param primaryKeyType single primary key type reference
     * @param dtoName DTO simple name
     * @param compositePrimaryKey true when the entity uses a composite primary key
     * @return service method parameters
     */
    private ServiceMethodParameters buildServiceMethodParameters(
            List<com.sqldomaingen.model.Column> primaryKeyColumns,
            PrimaryKeySupport.TypeRef primaryKeyType,
            String dtoName,
            boolean compositePrimaryKey
    ) {
        if (!compositePrimaryKey) {
            String idMethodParameters = primaryKeyType.simpleName() + " id";
            String updateMethodParameters = primaryKeyType.simpleName() + " id, " + dtoName + " dto";
            return new ServiceMethodParameters(idMethodParameters, updateMethodParameters);
        }

        StringBuilder idMethodParameters = new StringBuilder();
        StringBuilder updateMethodParameters = new StringBuilder();

        for (int index = 0; index < primaryKeyColumns.size(); index++) {
            com.sqldomaingen.model.Column primaryKeyColumn = primaryKeyColumns.get(index);

            if (index > 0) {
                idMethodParameters.append(", ");
                updateMethodParameters.append(", ");
            }

            String parameterType = resolvePrimaryKeyParameterType(primaryKeyColumn);
            String parameterName = resolvePrimaryKeyParameterName(primaryKeyColumn);

            idMethodParameters.append(parameterType).append(" ").append(parameterName);
            updateMethodParameters.append(parameterType).append(" ").append(parameterName);
        }

        if (!updateMethodParameters.isEmpty()) {
            updateMethodParameters.append(", ");
        }

        updateMethodParameters.append(dtoName).append(" dto");

        return new ServiceMethodParameters(
                idMethodParameters.toString(),
                updateMethodParameters.toString()
        );
    }

    /**
     * Resolves the Java parameter type for a primary key column.
     *
     * @param primaryKeyColumn primary key column
     * @return simple Java parameter type
     */
    private String resolvePrimaryKeyParameterType(com.sqldomaingen.model.Column primaryKeyColumn) {
        String rawJavaType = primaryKeyColumn.getJavaType();

        if (rawJavaType == null || rawJavaType.isBlank()) {
            return "Long";
        }

        return JavaTypeSupport.resolveSimpleType(rawJavaType);
    }

    /**
     * Resolves the Java parameter name for a primary key column.
     *
     * @param primaryKeyColumn primary key column
     * @return camelCase parameter name
     */
    private String resolvePrimaryKeyParameterName(com.sqldomaingen.model.Column primaryKeyColumn) {
        String columnName = GeneratorSupport.unquoteIdentifier(primaryKeyColumn.getName());

        if (columnName == null || columnName.isBlank()) {
            columnName = "id";
        }

        return NamingConverter.toCamelCase(columnName);
    }

    /**
     * Appends package declaration and imports for the service interface.
     *
     * @param stringBuilder target source builder
     * @param servicePackage service package name
     * @param importLines ordered import lines
     */
    private void appendServiceInterfacePackageAndImports(
            StringBuilder stringBuilder,
            String servicePackage,
            java.util.LinkedHashSet<String> importLines
    ) {
        stringBuilder.append("package ").append(servicePackage).append(";\n\n");

        for (String importLine : importLines) {
            stringBuilder.append(importLine).append("\n");
        }

        stringBuilder.append("\n");
    }

    /**
     * Appends the service interface header.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     */
    private void appendServiceInterfaceHeader(StringBuilder stringBuilder, String entityName) {
        stringBuilder.append("/**\n");
        stringBuilder.append(" * Service contract for {@code ").append(entityName).append("} domain operations.\n");
        stringBuilder.append(" */\n");
        stringBuilder.append("public interface ").append(entityName).append("Service {\n\n");
    }

    /**
     * Appends get-all method signature.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param dtoName DTO simple name
     */
    private void appendGetAllMethodSignature(StringBuilder stringBuilder, String entityName, String dtoName) {
        String pluralMethodSuffix = NamingConverter.toPascalCase(
                NamingConverter.toCamelCasePlural(entityName)
        );
        String pluralLowerDisplayLabel = NamingConverter.toLogLabel(
                NamingConverter.toCamelCasePlural(entityName)
        );

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Retrieves all available ").append(pluralLowerDisplayLabel).append(".\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @return a non-null list of {@link ").append(dtoName).append("}\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    List<").append(dtoName).append("> getAll").append(pluralMethodSuffix).append("();\n\n");
    }

    /**
     * Appends get-by-id method signature.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param dtoName DTO simple name
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey true when the entity uses a composite primary key
     * @param idMethodParameters id method parameters
     */
    private void appendGetByIdMethodSignature(
            StringBuilder stringBuilder,
            String entityName,
            String dtoName,
            List<com.sqldomaingen.model.Column> primaryKeyColumns,
            boolean compositePrimaryKey,
            String idMethodParameters
    ) {
        String lowerDisplayLabel = NamingConverter.toLogLabel(entityName);

        stringBuilder.append("    /**\n");

        if (compositePrimaryKey) {
            stringBuilder.append("     * Retrieves ")
                    .append(NamingConverter.resolveIndefiniteArticle(lowerDisplayLabel))
                    .append(" ")
                    .append(lowerDisplayLabel)
                    .append(" by its composite identifier.\n");
        } else {
            stringBuilder.append("     * Retrieves ")
                    .append(NamingConverter.resolveIndefiniteArticle(lowerDisplayLabel))
                    .append(" ")
                    .append(lowerDisplayLabel)
                    .append(" by its identifier.\n");
        }

        stringBuilder.append("     *\n");
        appendPrimaryKeyJavaDocParameters(stringBuilder, primaryKeyColumns, compositePrimaryKey, lowerDisplayLabel, "retrieve");
        stringBuilder.append("     * @return the matching {@link ").append(dtoName).append("}\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    ").append(dtoName).append(" get").append(entityName).append("ById(")
                .append(idMethodParameters).append(");\n\n");
    }

    /**
     * Appends create method signature.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param dtoName DTO simple name
     */
    private void appendCreateMethodSignature(StringBuilder stringBuilder, String entityName, String dtoName) {
        String lowerDisplayLabel = NamingConverter.toLogLabel(entityName);

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Creates a new ").append(lowerDisplayLabel).append(".\n");
        stringBuilder.append("     *\n");
        stringBuilder.append("     * @param dto the ").append(lowerDisplayLabel).append(" payload to create\n");
        stringBuilder.append("     * @return the created {@link ").append(dtoName).append("}\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    ").append(dtoName).append(" create").append(entityName).append("(")
                .append(dtoName).append(" dto);\n\n");
    }

    /**
     * Appends update method signature.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param dtoName DTO simple name
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey true when the entity uses a composite primary key
     * @param updateMethodParameters update method parameters
     */
    private void appendUpdateMethodSignature(
            StringBuilder stringBuilder,
            String entityName,
            String dtoName,
            List<com.sqldomaingen.model.Column> primaryKeyColumns,
            boolean compositePrimaryKey,
            String updateMethodParameters
    ) {
        String lowerDisplayLabel = NamingConverter.toLogLabel(entityName);

        stringBuilder.append("    /**\n");
        stringBuilder.append("     * Updates an existing ").append(lowerDisplayLabel).append(".\n");
        stringBuilder.append("     * <p>\n");
        stringBuilder.append("     * Only non-null fields from the DTO are applied to the existing entity.\n");
        stringBuilder.append("     *\n");
        appendPrimaryKeyJavaDocParameters(stringBuilder, primaryKeyColumns, compositePrimaryKey, lowerDisplayLabel, "update");
        stringBuilder.append("     * @param dto the partial ").append(lowerDisplayLabel).append(" payload\n");
        stringBuilder.append("     * @return the updated {@link ").append(dtoName).append("}\n");
        stringBuilder.append("     */\n");
        stringBuilder.append("    ").append(dtoName).append(" update").append(entityName).append("(")
                .append(updateMethodParameters).append(");\n\n");
    }

    /**
     * Appends delete method signature.
     *
     * @param stringBuilder target source builder
     * @param entityName entity simple name
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey true when the entity uses a composite primary key
     * @param idMethodParameters id method parameters
     */
    private void appendDeleteMethodSignature(
            StringBuilder stringBuilder,
            String entityName,
            List<com.sqldomaingen.model.Column> primaryKeyColumns,
            boolean compositePrimaryKey,
            String idMethodParameters
    ) {
        String lowerDisplayLabel = NamingConverter.toLogLabel(entityName);

        stringBuilder.append("    /**\n");

        if (compositePrimaryKey) {
            stringBuilder.append("     * Deletes ")
                    .append(NamingConverter.resolveIndefiniteArticle(lowerDisplayLabel))
                    .append(" ")
                    .append(lowerDisplayLabel)
                    .append(" by its composite identifier.\n");
        } else {
            stringBuilder.append("     * Deletes ")
                    .append(NamingConverter.resolveIndefiniteArticle(lowerDisplayLabel))
                    .append(" ")
                    .append(lowerDisplayLabel)
                    .append(" by its identifier.\n");
        }

        stringBuilder.append("     *\n");
        appendPrimaryKeyJavaDocParameters(stringBuilder, primaryKeyColumns, compositePrimaryKey, lowerDisplayLabel, "delete");
        stringBuilder.append("     */\n");
        stringBuilder.append("    void delete").append(entityName).append("(")
                .append(idMethodParameters).append(");\n");
    }

    /**
     * Appends primary key JavaDoc parameters.
     *
     * @param stringBuilder target source builder
     * @param primaryKeyColumns primary key columns
     * @param compositePrimaryKey true when the entity uses a composite primary key
     * @param lowerDisplayLabel lowercase display label
     * @param action target action description
     */
    private void appendPrimaryKeyJavaDocParameters(
            StringBuilder stringBuilder,
            List<com.sqldomaingen.model.Column> primaryKeyColumns,
            boolean compositePrimaryKey,
            String lowerDisplayLabel,
            String action
    ) {
        if (!compositePrimaryKey) {
            stringBuilder.append("     * @param id the identifier of the ")
                    .append(lowerDisplayLabel)
                    .append(" to ")
                    .append(action)
                    .append("\n");
            return;
        }

        for (com.sqldomaingen.model.Column primaryKeyColumn : primaryKeyColumns) {
            String parameterName = resolvePrimaryKeyParameterName(primaryKeyColumn);
            String readableParameterLabel = NamingConverter.toLogLabel(parameterName);

            stringBuilder.append("     * @param ")
                    .append(parameterName)
                    .append(" the ")
                    .append(readableParameterLabel)
                    .append(" of the composite key\n");
        }
    }

    /**
     * Appends the service interface footer.
     *
     * @param stringBuilder target source builder
     */
    private void appendServiceInterfaceFooter(StringBuilder stringBuilder) {
        stringBuilder.append("}\n");
    }
}