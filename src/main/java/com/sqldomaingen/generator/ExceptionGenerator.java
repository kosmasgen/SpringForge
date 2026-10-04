package com.sqldomaingen.generator;

import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.util.PackageResolver;
import lombok.extern.log4j.Log4j2;

import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Generates the exception handling layer under the exception package.
 * <p>
 * Generated files:
 * <ul>
 *     <li>{@code ErrorResponse.java}</li>
 *     <li>{@code ErrorCodes.java}</li>
 *     <li>{@code GeneratedRuntimeException.java}</li>
 *     <li>{@code GlobalExceptionHandler.java}</li>
 * </ul>
 */
@Log4j2
public class ExceptionGenerator {

    /**
     * Generates the complete exception handling layer under the exception package.
     *
     * @param outputDir project root output directory
     * @param basePackage base package name
     * @param overwrite whether existing files should be overwritten
     */
    public void generateExceptionHandling(String outputDir, String basePackage, boolean overwrite) {
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        String trimmedOutputDir = outputDir.trim();
        String trimmedBasePackage = basePackage.trim();

        validateOutputDirectory(trimmedOutputDir);
        validateBasePackage(trimmedBasePackage);

        Path exceptionDirectory = resolveExceptionDirectory(trimmedOutputDir, trimmedBasePackage);
        String exceptionPackage = resolveExceptionPackage(trimmedBasePackage);

        writeErrorResponse(exceptionDirectory, exceptionPackage, overwrite);
        writeErrorCodes(exceptionDirectory, exceptionPackage, overwrite);
        writeErrorMessages(exceptionDirectory, exceptionPackage, overwrite);
        writeGeneratedRuntimeException(exceptionDirectory, exceptionPackage, overwrite);
        writeGlobalExceptionHandler(exceptionDirectory, exceptionPackage, overwrite);

        log.debug(" Exception handling generated under: {}", exceptionDirectory.toAbsolutePath());
    }

    /**
     * Validates the output directory argument.
     *
     * @param outputDir trimmed output directory value
     */
    private void validateOutputDirectory(String outputDir) {
        if (outputDir.isEmpty()) {
            throw new IllegalArgumentException("outputDir must not be blank");
        }
    }

    /**
     * Validates the base package argument.
     *
     * @param basePackage trimmed base package value
     */
    private void validateBasePackage(String basePackage) {
        if (basePackage.isEmpty()) {
            throw new IllegalArgumentException("basePackage must not be blank");
        }
    }

    /**
     * Resolves and creates the exception package directory if needed.
     *
     * @param outputDir trimmed output directory
     * @param basePackage trimmed base package
     * @return resolved exception directory path
     */
    private Path resolveExceptionDirectory(String outputDir, String basePackage) {
        return GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(outputDir, basePackage, "exception")
        );
    }

    /**
     * Resolves the exception package name.
     *
     * @param basePackage trimmed base package
     * @return fully qualified exception package name
     */
    private String resolveExceptionPackage(String basePackage) {
        return PackageResolver.resolvePackageName(basePackage, "exception");
    }

    /**
     * Generates the {@code ErrorResponse} class.
     *
     * @param exceptionDirectory target exception directory
     * @param exceptionPackage target package name
     * @param overwrite whether existing files should be overwritten
     */
    private void writeErrorResponse(Path exceptionDirectory, String exceptionPackage, boolean overwrite) {
        Path file = exceptionDirectory.resolve("ErrorResponse.java");
        String content = buildErrorResponseContent(exceptionPackage);

        GeneratorSupport.writeFile(file, content, overwrite);
        log.info("ErrorResponse generated: {}", file.toAbsolutePath());
    }

    /**
     * Builds the {@code GeneratedRuntimeException} handler method.
     *
     * @return generated method source content
     */
    private String buildGeneratedRuntimeExceptionHandlerMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Handles {@link GeneratedRuntimeException}.\n");
        builder.append(" *\n");
        builder.append(" * @param exception thrown generated runtime exception\n");
        builder.append(" * @param request current HTTP request\n");
        builder.append(" * @return standardized error response\n");
        builder.append(" */\n");
        builder.append("@ExceptionHandler(GeneratedRuntimeException.class)\n");
        builder.append("public ResponseEntity<ErrorResponse> handleGeneratedRuntimeException(\n");
        builder.append("        GeneratedRuntimeException exception,\n");
        builder.append("        HttpServletRequest request\n");
        builder.append(") {\n");
        builder.append("    String code = exception.getCode();\n");
        builder.append("    HttpStatus status = resolveStatus(code);\n");
        builder.append("    String message = safeMessage(\n");
        builder.append("            exception.getMessage(),\n");
        builder.append("            messageResolver.resolve(ErrorMessages.ERROR_UNEXPECTED)\n");
        builder.append("    );\n\n");
        builder.append("    return build(code, status, message, exception, request);\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds the source code of the {@code ErrorResponse} class.
     *
     * @param exceptionPackage target package name
     * @return generated Java source content
     */
    private String buildErrorResponseContent(String exceptionPackage) {
        StringBuilder builder = new StringBuilder();

        builder.append("package ").append(exceptionPackage).append(";\n\n");
        builder.append("import io.swagger.v3.oas.annotations.media.Schema;\n");
        builder.append("import lombok.AllArgsConstructor;\n");
        builder.append("import lombok.Builder;\n");
        builder.append("import lombok.Data;\n");
        builder.append("import lombok.NoArgsConstructor;\n\n");
        builder.append("import java.time.Instant;\n\n");
        builder.append("/**\n");
        builder.append(" * Standard API error response payload.\n");
        builder.append(" */\n");
        builder.append("@Schema(description = \"Standard API error response payload\")\n");
        builder.append("@Data\n");
        builder.append("@Builder\n");
        builder.append("@NoArgsConstructor\n");
        builder.append("@AllArgsConstructor\n");
        builder.append("public class ErrorResponse {\n\n");
        builder.append("    @Schema(description = \"Stable application error code\", example = \"VALIDATION_ERROR\")\n");
        builder.append("    private String code;\n\n");
        builder.append("    @Schema(description = \"Error timestamp (UTC)\", example = \"2026-02-18T10:15:30Z\")\n");
        builder.append("    private Instant timestamp;\n\n");
        builder.append("    @Schema(description = \"HTTP status code\", example = \"404\")\n");
        builder.append("    private int status;\n\n");
        builder.append("    @Schema(description = \"HTTP status reason phrase\", example = \"Not Found\")\n");
        builder.append("    private String error;\n\n");
        builder.append("    @Schema(description = \"Error message\", example = \"Resource not found with id: 10\")\n");
        builder.append("    private String message;\n\n");
        builder.append("    @Schema(description = \"Request path\", example = \"/api/absences/10\")\n");
        builder.append("    private String path;\n\n");
        builder.append("    @Schema(description = \"Exception type\", example = \"ResponseStatusException\")\n");
        builder.append("    private String exception;\n");
        builder.append("}\n");

        return builder.toString();
    }

    /**
     * Generates the {@code ErrorCodes} class.
     *
     * @param exceptionDirectory target exception directory
     * @param exceptionPackage target package name
     * @param overwrite whether existing files should be overwritten
     */
    private void writeErrorCodes(Path exceptionDirectory, String exceptionPackage, boolean overwrite) {
        Path file = exceptionDirectory.resolve("ErrorCodes.java");
        String content = buildErrorCodesContent(exceptionPackage);

        GeneratorSupport.writeFile(file, content, overwrite);
        log.debug(" ErrorCodes generated: {}", file.toAbsolutePath());
    }

    /**
     * Builds the source code of the {@code ErrorCodes} class.
     *
     * @param exceptionPackage target package name
     * @return generated Java source content
     */
    private String buildErrorCodesContent(String exceptionPackage) {
        StringBuilder builder = new StringBuilder();

        builder.append("package ").append(exceptionPackage).append(";\n\n");
        builder.append("/**\n");
        builder.append(" * Centralized application error codes.\n");
        builder.append(" */\n");
        builder.append("public final class ErrorCodes {\n\n");
        builder.append("    public static final String NOT_FOUND = \"NOT_FOUND\";\n");
        builder.append("    public static final String BAD_REQUEST = \"BAD_REQUEST\";\n");
        builder.append("    public static final String UNAUTHORIZED = \"UNAUTHORIZED\";\n");
        builder.append("    public static final String EMAIL_ALREADY_EXISTS = \"EMAIL_ALREADY_EXISTS\";\n");
        builder.append("    public static final String USERNAME_ALREADY_EXISTS = \"USERNAME_ALREADY_EXISTS\";\n");
        builder.append("    public static final String VALIDATION_ERROR = \"VALIDATION_ERROR\";\n");
        builder.append("    public static final String REQUEST_ERROR = \"REQUEST_ERROR\";\n");
        builder.append("    public static final String INTERNAL_ERROR = \"INTERNAL_ERROR\";\n\n");
        builder.append("    /**\n");
        builder.append("     * Prevents instantiation.\n");
        builder.append("     */\n");
        builder.append("    private ErrorCodes() {\n");
        builder.append("    }\n");
        builder.append("}\n");

        return builder.toString();
    }

    /**
     * Generates the {@code ErrorMessages} class.
     *
     * @param exceptionDirectory target exception directory
     * @param exceptionPackage target package name
     * @param overwrite whether existing files should be overwritten
     */
    private void writeErrorMessages(Path exceptionDirectory, String exceptionPackage, boolean overwrite) {
        Path file = exceptionDirectory.resolve("ErrorMessages.java");
        String content = buildErrorMessagesContent(exceptionPackage);

        GeneratorSupport.writeFile(file, content, overwrite);
        log.debug(" ErrorMessages generated: {}", file.toAbsolutePath());
    }

    /**
     * Builds the source code of the {@code ErrorMessages} class.
     *
     * @param exceptionPackage target package name
     * @return generated Java source content
     */
    private String buildErrorMessagesContent(String exceptionPackage) {
        StringBuilder builder = new StringBuilder();

        builder.append("package ").append(exceptionPackage).append(";\n\n");
        builder.append("/**\n");
        builder.append(" * Centralized message keys for exception handling.\n");
        builder.append(" */\n");
        builder.append("public final class ErrorMessages {\n\n");
        builder.append("    public static final String ENTITY_NOT_FOUND_BY_ID = \"entity.notFoundById\";\n");
        builder.append("    public static final String ENTITY_NOT_FOUND_BY_COMPOSITE_ID = \"entity.notFoundByCompositeId\";\n");
        builder.append("    public static final String ENTITY_ALREADY_EXISTS_BY_ID = \"entity.alreadyExistsById\";\n");
        builder.append("    public static final String ENTITY_ALREADY_EXISTS_BY_COMPOSITE_ID = \"entity.alreadyExistsByCompositeId\";\n");
        builder.append("    public static final String ENTITY_UNIQUE_CONSTRAINT_VIOLATION = \"entity.uniqueConstraintViolation\";\n\n");
        builder.append("    public static final String ERROR_UNEXPECTED = \"error.unexpected\";\n");
        builder.append("    public static final String ERROR_ENDPOINT_NOT_FOUND = \"error.endpointNotFound\";\n");
        builder.append("    public static final String ERROR_INVALID_REQUEST_BODY = \"error.invalidRequestBody\";\n");
        builder.append("    public static final String ERROR_VALIDATION_FAILED = \"error.validationFailed\";\n");
        builder.append("    public static final String ERROR_INVALID = \"error.invalid\";\n");
        builder.append("    public static final String ERROR_INVALID_CREDENTIALS = \"error.invalidCredentials\";\n");
        builder.append("    public static final String ERROR_USERNAME_ALREADY_EXISTS = \"error.usernameAlreadyExists\";\n");
        builder.append("    public static final String ERROR_EMAIL_ALREADY_EXISTS = \"error.emailAlreadyExists\";\n\n");
        builder.append("    /**\n");
        builder.append("     * Prevents instantiation.\n");
        builder.append("     */\n");
        builder.append("    private ErrorMessages() {\n");
        builder.append("    }\n");
        builder.append("}\n");

        return builder.toString();
    }

    /**
     * Generates the {@code GeneratedRuntimeException} class.
     *
     * @param exceptionDirectory target exception directory
     * @param exceptionPackage target package name
     * @param overwrite whether existing files should be overwritten
     */
    private void writeGeneratedRuntimeException(Path exceptionDirectory, String exceptionPackage, boolean overwrite) {
        Path file = exceptionDirectory.resolve("GeneratedRuntimeException.java");
        String content = buildGeneratedRuntimeExceptionContent(exceptionPackage);

        GeneratorSupport.writeFile(file, content, overwrite);
        log.debug(" GeneratedRuntimeException generated: {}", file.toAbsolutePath());
    }

    /**
     * Builds the source code of the {@code GeneratedRuntimeException} class.
     *
     * @param exceptionPackage target package name
     * @return generated Java source content
     */
    private String buildGeneratedRuntimeExceptionContent(String exceptionPackage) {
        StringBuilder builder = new StringBuilder();

        builder.append("package ").append(exceptionPackage).append(";\n\n");
        builder.append("import lombok.Builder;\n");
        builder.append("import lombok.Getter;\n\n");
        builder.append("/**\n");
        builder.append(" * Generic runtime exception used across generated services.\n");
        builder.append(" * Carries structured error information for consistent API responses.\n");
        builder.append(" */\n");
        builder.append("@Getter\n");
        builder.append("public class GeneratedRuntimeException extends RuntimeException {\n\n");
        builder.append("    private final String code;\n\n");
        builder.append("    /**\n");
        builder.append("     * Constructs a new exception instance.\n");
        builder.append("     *\n");
        builder.append("     * @param code application error code\n");
        builder.append("     * @param message error message\n");
        builder.append("     */\n");
        builder.append("    @Builder\n");
        builder.append("    public GeneratedRuntimeException(String code, String message) {\n");
        builder.append("        super(message);\n");
        builder.append("        this.code = code;\n");
        builder.append("    }\n");
        builder.append("}\n");

        return builder.toString();
    }

    /**
     * Generates the {@code GlobalExceptionHandler} class.
     *
     * @param exceptionDirectory target exception directory
     * @param exceptionPackage target package name
     * @param overwrite whether existing files should be overwritten
     */
    private void writeGlobalExceptionHandler(Path exceptionDirectory, String exceptionPackage, boolean overwrite) {
        Path file = exceptionDirectory.resolve("GlobalExceptionHandler.java");
        String content = buildGlobalExceptionHandlerContent(exceptionPackage);

        GeneratorSupport.writeFile(file, content, overwrite);
        log.info(" GlobalExceptionHandler generated: {}", file.toAbsolutePath());
    }

    /**
     * Builds the complete source content of the generated {@code GlobalExceptionHandler}.
     *
     * @param exceptionPackage package of the generated exception classes
     * @return generated {@code GlobalExceptionHandler} source content
     */
    private String buildGlobalExceptionHandlerContent(String exceptionPackage) {
        String basePackage = exceptionPackage.substring(0, exceptionPackage.lastIndexOf(".exception"));
        StringBuilder builder = new StringBuilder();

        builder.append("package ").append(exceptionPackage).append(";\n\n");
        builder.append(buildGlobalExceptionHandlerImports().formatted(basePackage));
        builder.append("\n");
        builder.append(buildGlobalExceptionHandlerClassJavaDoc()).append("\n");
        builder.append("@Log4j2\n");
        builder.append("@RestControllerAdvice\n");
        builder.append("@RequiredArgsConstructor\n");
        builder.append("@SuppressWarnings(\"unused\")\n");
        builder.append("public class GlobalExceptionHandler {\n\n");
        builder.append("    private final MessageResolver messageResolver;\n\n");
        builder.append(indent(buildGlobalExceptionHandlerBody()));
        builder.append("}\n");

        return builder.toString();
    }

    /**
     * Applies indentation padding to generated multiline content.
     *
     * @param content generated content
     * @return indented content
     */
    private String indent(String content) {
        String padding = " ".repeat(4);

        return content.lines()
                .map(line -> line.isBlank() ? line : padding + line)
                .collect(Collectors.joining(System.lineSeparator()));
    }

    /**
     * Builds the import section of the generated {@code GlobalExceptionHandler}.
     *
     * @return generated import source content
     */
    private String buildGlobalExceptionHandlerImports() {
        StringBuilder builder = new StringBuilder();

        builder.append("import %s.util.MessageResolver;\n");
        builder.append("import jakarta.servlet.http.HttpServletRequest;\n");
        builder.append("import jakarta.validation.ConstraintViolation;\n");
        builder.append("import jakarta.validation.ConstraintViolationException;\n");
        builder.append("import lombok.RequiredArgsConstructor;\n");
        builder.append("import lombok.extern.log4j.Log4j2;\n");
        builder.append("import org.springframework.http.HttpStatus;\n");
        builder.append("import org.springframework.http.ResponseEntity;\n");
        builder.append("import org.springframework.http.converter.HttpMessageNotReadableException;\n");
        builder.append("import org.springframework.security.core.AuthenticationException;\n");
        builder.append("import org.springframework.validation.FieldError;\n");
        builder.append("import org.springframework.web.bind.MethodArgumentNotValidException;\n");
        builder.append("import org.springframework.web.bind.annotation.ExceptionHandler;\n");
        builder.append("import org.springframework.web.bind.annotation.RestControllerAdvice;\n");
        builder.append("import org.springframework.web.server.ResponseStatusException;\n");
        builder.append("import org.springframework.web.servlet.NoHandlerFoundException;\n\n");
        builder.append("import java.time.Instant;\n");
        builder.append("import java.util.List;\n");
        builder.append("import java.util.Set;\n");
        builder.append("import java.util.stream.Collectors;\n\n");
        builder.append("import static java.time.temporal.ChronoUnit.MILLIS;\n");

        return builder.toString();
    }

    /**
     * Builds the JavaDoc section of the generated {@code GlobalExceptionHandler}.
     *
     * @return generated class Javadoc source content
     */
    private String buildGlobalExceptionHandlerClassJavaDoc() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Centralized exception handling for REST APIs.\n");
        builder.append(" */");

        return builder.toString();
    }

    /**
     * Builds the complete body of the generated {@code GlobalExceptionHandler}.
     *
     * @return generated class body source content
     */
    private String buildGlobalExceptionHandlerBody() {
        return buildGeneratedRuntimeExceptionHandlerMethod()
                + buildResponseStatusExceptionHandlerMethod()
                + buildMethodArgumentNotValidHandlerMethod()
                + buildConstraintViolationHandlerMethod()
                + buildNoHandlerFoundExceptionHandlerMethod()
                + buildHttpMessageNotReadableHandlerMethod()
                + buildAuthenticationExceptionHandlerMethod()
                + buildGenericExceptionHandlerMethod()
                + buildErrorResponseBuilderMethod()
                + buildResolveCodeMethod()
                + buildResolveStatusMethod()
                + buildValidationMessageMethod()
                + buildFormatValidationMessageMethod()
                + buildViolationMessageMethod()
                + buildFormatViolationMethod()
                + buildBadRequestMethod()
                + buildSafeMessageMethod()
                + buildValidationErrorMethod();
    }

    /**
     * Builds the {@code HttpMessageNotReadableException} handler method.
     *
     * @return generated method source content
     */
    private String buildHttpMessageNotReadableHandlerMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Handles malformed or unreadable request bodies.\n");
        builder.append(" *\n");
        builder.append(" * @param exception thrown message parsing exception\n");
        builder.append(" * @param request current HTTP request\n");
        builder.append(" * @return standardized bad request error response\n");
        builder.append(" */\n");
        builder.append("@ExceptionHandler(HttpMessageNotReadableException.class)\n");
        builder.append("public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(\n");
        builder.append("        HttpMessageNotReadableException exception,\n");
        builder.append("        HttpServletRequest request\n");
        builder.append(") {\n");
        builder.append("    log.warn(\"Unreadable request body at {} {}\", request.getMethod(), request.getRequestURI());\n\n");
        builder.append("    return badRequest(messageResolver.resolve(ErrorMessages.ERROR_INVALID_REQUEST_BODY), exception, request);\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds the safe message helper method.
     *
     * @return generated method source content
     */
    private String buildSafeMessageMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Returns the message when it is not blank; otherwise returns the fallback message.\n");
        builder.append(" *\n");
        builder.append(" * @param message preferred message\n");
        builder.append(" * @param fallback fallback message\n");
        builder.append(" * @return resolved message\n");
        builder.append(" */\n");
        builder.append("private String safeMessage(String message, String fallback) {\n");
        builder.append("    return (message == null || message.isBlank())\n");
        builder.append("            ? fallback\n");
        builder.append("            : message;\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds the constraint violation message helper method.
     *
     * @return generated method source content
     */
    private String buildViolationMessageMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Builds a readable validation message from constraint violations.\n");
        builder.append(" *\n");
        builder.append(" * @param exception constraint violation exception\n");
        builder.append(" * @return resolved validation message\n");
        builder.append(" */\n");
        builder.append("private String buildViolationMessage(ConstraintViolationException exception) {\n");
        builder.append("    Set<ConstraintViolation<?>> violations = exception.getConstraintViolations();\n\n");
        builder.append("    if (violations.isEmpty()) {\n");
        builder.append("        return messageResolver.resolve(ErrorMessages.ERROR_VALIDATION_FAILED);\n");
        builder.append("    }\n\n");
        builder.append("    return violations.stream()\n");
        builder.append("            .map(this::formatViolation)\n");
        builder.append("            .distinct()\n");
        builder.append("            .collect(Collectors.joining(\", \"));\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds a standardized validation error response.
     *
     * @return generated method source content
     */
    private String buildValidationErrorMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Builds a standardized validation error response.\n");
        builder.append(" *\n");
        builder.append(" * @param message response message\n");
        builder.append(" * @param exception original exception\n");
        builder.append(" * @param request current HTTP request\n");
        builder.append(" * @return standardized validation error response\n");
        builder.append(" */\n");
        builder.append("private ResponseEntity<ErrorResponse> validationError(String message, Exception exception, HttpServletRequest request) {\n");
        builder.append("    return build(ErrorCodes.VALIDATION_ERROR, HttpStatus.BAD_REQUEST, message, exception, request);\n");
        builder.append("}\n\n");

        return builder.toString();
    }



    /**
     * Builds the constraint violation formatter helper method.
     *
     * @return generated method source content
     */
    private String buildFormatViolationMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Formats a constraint violation into a readable validation message.\n");
        builder.append(" *\n");
        builder.append(" * @param violation constraint violation\n");
        builder.append(" * @return formatted validation message\n");
        builder.append(" */\n");
        builder.append("private String formatViolation(ConstraintViolation<?> violation) {\n");
        builder.append("    return formatValidationMessage(\n");
        builder.append("            violation.getPropertyPath().toString(),\n");
        builder.append("            safeMessage(\n");
        builder.append("                    violation.getMessage(),\n");
        builder.append("                    messageResolver.resolve(ErrorMessages.ERROR_INVALID)\n");
        builder.append("            )\n");
        builder.append("    );\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds the bad request response helper method.
     *
     * @return generated method source content
     */
    private String buildBadRequestMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Builds a standardized bad request error response.\n");
        builder.append(" *\n");
        builder.append(" * @param message response message\n");
        builder.append(" * @param exception original exception\n");
        builder.append(" * @param request current HTTP request\n");
        builder.append(" * @return standardized bad request response\n");
        builder.append(" */\n");
        builder.append("private ResponseEntity<ErrorResponse> badRequest(\n");
        builder.append("        String message,\n");
        builder.append("        Exception exception,\n");
        builder.append("        HttpServletRequest request\n");
        builder.append(") {\n");
        builder.append("    return build(ErrorCodes.BAD_REQUEST, HttpStatus.BAD_REQUEST, message, exception, request);\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds the validation message helper method.
     *
     * @return generated method source content
     */
    private String buildValidationMessageMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Builds a readable validation message from field errors.\n");
        builder.append(" *\n");
        builder.append(" * @param exception method argument validation exception\n");
        builder.append(" * @return resolved validation message\n");
        builder.append(" */\n");
        builder.append("private String buildValidationMessage(MethodArgumentNotValidException exception) {\n");
        builder.append("    List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors();\n\n");
        builder.append("    if (fieldErrors.isEmpty()) {\n");
        builder.append("        return messageResolver.resolve(ErrorMessages.ERROR_VALIDATION_FAILED);\n");
        builder.append("    }\n\n");
        builder.append("    FieldError fieldError = fieldErrors.getFirst();\n\n");
        builder.append("    return formatValidationMessage(\n");
        builder.append("            fieldError.getField(),\n");
        builder.append("            safeMessage(\n");
        builder.append("                    fieldError.getDefaultMessage(),\n");
        builder.append("                    messageResolver.resolve(ErrorMessages.ERROR_INVALID)\n");
        builder.append("            )\n");
        builder.append("    );\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds the validation message formatter helper method.
     *
     * @return generated method source content
     */
    private String buildFormatValidationMessageMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Formats a validation message using the field name and resolved message.\n");
        builder.append(" *\n");
        builder.append(" * @param field field name\n");
        builder.append(" * @param message validation message\n");
        builder.append(" * @return formatted validation message\n");
        builder.append(" */\n");
        builder.append("private String formatValidationMessage(String field, String message) {\n");
        builder.append("    return field + \": \" + message;\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds the method that resolves HTTP status from application error code.
     *
     * @return generated method source content
     */
    private String buildResolveStatusMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Resolves the HTTP status from a provided application error code.\n");
        builder.append(" *\n");
        builder.append(" * @param errorCode application error code\n");
        builder.append(" * @return resolved HTTP status\n");
        builder.append(" */\n");
        builder.append("private HttpStatus resolveStatus(String errorCode) {\n");
        builder.append("    if (errorCode == null || errorCode.isBlank()) {\n");
        builder.append("        return HttpStatus.INTERNAL_SERVER_ERROR;\n");
        builder.append("    }\n\n");
        builder.append("    return switch (errorCode) {\n");
        builder.append("        case ErrorCodes.NOT_FOUND -> HttpStatus.NOT_FOUND;\n");
        builder.append("        case ErrorCodes.BAD_REQUEST, ErrorCodes.REQUEST_ERROR, ErrorCodes.VALIDATION_ERROR, ErrorCodes.USERNAME_ALREADY_EXISTS, ErrorCodes.EMAIL_ALREADY_EXISTS -> HttpStatus.BAD_REQUEST;\n");
        builder.append("        case ErrorCodes.UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;\n");
        builder.append("        default -> HttpStatus.INTERNAL_SERVER_ERROR;\n");
        builder.append("    };\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds the method that resolves application error codes from HTTP status.
     *
     * @return generated method source content
     */
    private String buildResolveCodeMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Resolves a stable application error code from an HTTP status.\n");
        builder.append(" *\n");
        builder.append(" * @param status HTTP status\n");
        builder.append(" * @return stable application error code\n");
        builder.append(" */\n");
        builder.append("private String resolveCode(HttpStatus status) {\n");
        builder.append("    if (status == null) {\n");
        builder.append("        return ErrorCodes.REQUEST_ERROR;\n");
        builder.append("    }\n\n");
        builder.append("    return switch (status) {\n");
        builder.append("        case NOT_FOUND -> ErrorCodes.NOT_FOUND;\n");
        builder.append("        case BAD_REQUEST -> ErrorCodes.BAD_REQUEST;\n");
        builder.append("        case UNAUTHORIZED -> ErrorCodes.UNAUTHORIZED;\n");
        builder.append("        case UNPROCESSABLE_ENTITY -> ErrorCodes.VALIDATION_ERROR;\n");
        builder.append("        case FORBIDDEN, CONFLICT -> ErrorCodes.REQUEST_ERROR;\n");
        builder.append("        default -> status.is4xxClientError()\n");
        builder.append("                ? ErrorCodes.REQUEST_ERROR\n");
        builder.append("                : ErrorCodes.INTERNAL_ERROR;\n");
        builder.append("    };\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds the {@code ErrorResponse} builder helper method.
     *
     * @return generated method source content
     */
    private String buildErrorResponseBuilderMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Builds a standardized {@link ErrorResponse}.\n");
        builder.append(" *\n");
        builder.append(" * @param code stable application error code\n");
        builder.append(" * @param status HTTP status\n");
        builder.append(" * @param message response message\n");
        builder.append(" * @param exception original exception\n");
        builder.append(" * @param request current HTTP request\n");
        builder.append(" * @return response entity with standardized error body\n");
        builder.append(" */\n");
        builder.append("private ResponseEntity<ErrorResponse> build(\n");
        builder.append("        String code,\n");
        builder.append("        HttpStatus status,\n");
        builder.append("        String message,\n");
        builder.append("        Exception exception,\n");
        builder.append("        HttpServletRequest request\n");
        builder.append(") {\n");
        builder.append("    ErrorResponse errorResponse = ErrorResponse.builder()\n");
        builder.append("            .code(code)\n");
        builder.append("            .timestamp(Instant.now().truncatedTo(MILLIS))\n");
        builder.append("            .status(status.value())\n");
        builder.append("            .error(status.getReasonPhrase())\n");
        builder.append("            .message(message)\n");
        builder.append("            .path(request.getRequestURI())\n");
        builder.append("            .exception(exception.getClass().getSimpleName())\n");
        builder.append("            .build();\n\n");
        builder.append("    return ResponseEntity.status(status).body(errorResponse);\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds the Spring Security authentication exception handler method.
     *
     * @return generated method source content
     */
    private String buildAuthenticationExceptionHandlerMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Handles authentication failures.\n");
        builder.append(" *\n");
        builder.append(" * @param exception thrown authentication exception\n");
        builder.append(" * @param request current HTTP request\n");
        builder.append(" * @return standardized unauthorized error response\n");
        builder.append(" */\n");
        builder.append("@ExceptionHandler(AuthenticationException.class)\n");
        builder.append("public ResponseEntity<ErrorResponse> handleAuthenticationException(\n");
        builder.append("        AuthenticationException exception,\n");
        builder.append("        HttpServletRequest request\n");
        builder.append(") {\n");
        builder.append("    log.warn(\"Authentication failed at {} {}.\", request.getMethod(), request.getRequestURI());\n\n");
        builder.append("    return build(\n");
        builder.append("            ErrorCodes.UNAUTHORIZED,\n");
        builder.append("            HttpStatus.UNAUTHORIZED,\n");
        builder.append("            messageResolver.resolve(ErrorMessages.ERROR_INVALID_CREDENTIALS),\n");
        builder.append("            exception,\n");
        builder.append("            request\n");
        builder.append("    );\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds the generic {@code Exception} handler method.
     *
     * @return generated method source content
     */
    private String buildGenericExceptionHandlerMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Handles all unexpected exceptions.\n");
        builder.append(" *\n");
        builder.append(" * @param exception thrown exception\n");
        builder.append(" * @param request current HTTP request\n");
        builder.append(" * @return standardized internal server error response\n");
        builder.append(" */\n");
        builder.append("@ExceptionHandler(Exception.class)\n");
        builder.append("public ResponseEntity<ErrorResponse> handleGeneric(\n");
        builder.append("        Exception exception,\n");
        builder.append("        HttpServletRequest request\n");
        builder.append(") {\n");
        builder.append("    log.error(\"Unhandled exception at {} {}: {}\", request.getMethod(), request.getRequestURI(), exception.getMessage());\n\n");
        builder.append("    return build(\n");
        builder.append("            ErrorCodes.INTERNAL_ERROR,\n");
        builder.append("            HttpStatus.INTERNAL_SERVER_ERROR,\n");
        builder.append("            messageResolver.resolve(ErrorMessages.ERROR_UNEXPECTED),\n");
        builder.append("            exception,\n");
        builder.append("            request\n");
        builder.append("    );\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds the {@code NoHandlerFoundException} handler method.
     *
     * @return generated method source content
     */
    private String buildNoHandlerFoundExceptionHandlerMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Handles requests that do not match any controller endpoint.\n");
        builder.append(" *\n");
        builder.append(" * @param exception thrown no-handler exception\n");
        builder.append(" * @param request current HTTP request\n");
        builder.append(" * @return standardized not found error response\n");
        builder.append(" */\n");
        builder.append("@ExceptionHandler(NoHandlerFoundException.class)\n");
        builder.append("public ResponseEntity<ErrorResponse> handleNoHandlerFoundException(\n");
        builder.append("        NoHandlerFoundException exception,\n");
        builder.append("        HttpServletRequest request\n");
        builder.append(") {\n");
        builder.append("    return build(\n");
        builder.append("            ErrorCodes.NOT_FOUND,\n");
        builder.append("            HttpStatus.NOT_FOUND,\n");
        builder.append("            messageResolver.resolve(ErrorMessages.ERROR_ENDPOINT_NOT_FOUND, request.getRequestURI()),\n");
        builder.append("            exception,\n");
        builder.append("            request\n");
        builder.append("    );\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds the {@code ConstraintViolationException} handler method.
     *
     * @return generated method source content
     */
    private String buildConstraintViolationHandlerMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Handles validation errors raised for request parameters and path variables.\n");
        builder.append(" *\n");
        builder.append(" * @param exception thrown constraint violation exception\n");
        builder.append(" * @param request current HTTP request\n");
        builder.append(" * @return standardized validation error response\n");
        builder.append(" */\n");
        builder.append("@ExceptionHandler(ConstraintViolationException.class)\n");
        builder.append("public ResponseEntity<ErrorResponse> handleConstraintViolation(\n");
        builder.append("        ConstraintViolationException exception,\n");
        builder.append("        HttpServletRequest request\n");
        builder.append(") {\n");
        builder.append("    String message = buildViolationMessage(exception);\n\n");
        builder.append("    return validationError(message, exception, request);\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds the {@code MethodArgumentNotValidException} handler method.
     *
     * @return generated method source content
     */
    private String buildMethodArgumentNotValidHandlerMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Handles request body validation errors.\n");
        builder.append(" *\n");
        builder.append(" * @param exception thrown validation exception\n");
        builder.append(" * @param request current HTTP request\n");
        builder.append(" * @return standardized validation error response\n");
        builder.append(" */\n");
        builder.append("@ExceptionHandler(MethodArgumentNotValidException.class)\n");
        builder.append("public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(\n");
        builder.append("        MethodArgumentNotValidException exception,\n");
        builder.append("        HttpServletRequest request\n");
        builder.append(") {\n");
        builder.append("    String message = buildValidationMessage(exception);\n\n");
        builder.append("    return validationError(message, exception, request);\n");
        builder.append("}\n\n");

        return builder.toString();
    }

    /**
     * Builds the {@code ResponseStatusException} handler method.
     *
     * @return generated method source content
     */
    private String buildResponseStatusExceptionHandlerMethod() {
        StringBuilder builder = new StringBuilder();

        builder.append("/**\n");
        builder.append(" * Handles {@link ResponseStatusException}.\n");
        builder.append(" *\n");
        builder.append(" * @param exception thrown response status exception\n");
        builder.append(" * @param request current HTTP request\n");
        builder.append(" * @return standardized error response\n");
        builder.append(" */\n");
        builder.append("@ExceptionHandler(ResponseStatusException.class)\n");
        builder.append("public ResponseEntity<ErrorResponse> handleResponseStatusException(\n");
        builder.append("        ResponseStatusException exception,\n");
        builder.append("        HttpServletRequest request\n");
        builder.append(") {\n");
        builder.append("    HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());\n");
        builder.append("    String message = safeMessage(\n");
        builder.append("            exception.getReason(),\n");
        builder.append("            exception.getMessage()\n");
        builder.append("    );\n\n");
        builder.append("    return build(resolveCode(status), status, message, exception, request);\n");
        builder.append("}\n\n");

        return builder.toString();
    }
}