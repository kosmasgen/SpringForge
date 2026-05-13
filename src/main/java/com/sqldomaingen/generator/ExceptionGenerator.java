package com.sqldomaingen.generator;

import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.util.PackageResolver;
import lombok.extern.log4j.Log4j2;

import java.nio.file.Path;
import java.util.Objects;

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

    private String buildGeneratedRuntimeExceptionHandlerMethod() {
        return """
            /**
             * Handles {@link GeneratedRuntimeException}.
             *
             * @param exception thrown generated runtime exception
             * @param request current HTTP request
             * @return standardized error response
             */
            @ExceptionHandler(GeneratedRuntimeException.class)
            public ResponseEntity<ErrorResponse> handleGeneratedRuntimeException(
                    GeneratedRuntimeException exception,
                    HttpServletRequest request
            ) {
                HttpStatus status = resolveHttpStatusFromErrorCode(exception.getCode());
                String message = safeMessage(exception.getMessage(), resolveMessage("error.unexpected"));

                return build(exception.getCode(), status, message, exception, request);
            }

        """;
    }

    /**
     * Builds the source code of the {@code ErrorResponse} class.
     *
     * @param exceptionPackage target package name
     * @return generated Java source content
     */
    private String buildErrorResponseContent(String exceptionPackage) {
        return """
                package %s;

                import io.swagger.v3.oas.annotations.media.Schema;
                import lombok.AllArgsConstructor;
                import lombok.Builder;
                import lombok.Data;
                import lombok.NoArgsConstructor;

                import java.time.Instant;

                /**
                 * Standard API error response payload.
                 */
                @Schema(description = "Standard API error response payload")
                @Data
                @Builder
                @NoArgsConstructor
                @AllArgsConstructor
                public class ErrorResponse {

                    @Schema(description = "Stable application error code", example = "VALIDATION_ERROR")
                    private String code;

                    @Schema(description = "Error timestamp (UTC)", example = "2026-02-18T10:15:30Z")
                    private Instant timestamp;

                    @Schema(description = "HTTP status code", example = "404")
                    private int status;

                    @Schema(description = "HTTP status reason phrase", example = "Not Found")
                    private String error;

                    @Schema(description = "Human-readable error message", example = "Resource not found with id: 10")
                    private String message;

                    @Schema(description = "Request path", example = "/api/absences/10")
                    private String path;

                    @Schema(description = "Exception type", example = "ResponseStatusException")
                    private String exception;
                }
                """.formatted(exceptionPackage);
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
        return """
                package %s;

                /**
                 * Centralized application error codes.
                 */
                public final class ErrorCodes {

                    public static final String NOT_FOUND = "NOT_FOUND";
                    public static final String BAD_REQUEST = "BAD_REQUEST";
                    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
                    public static final String REQUEST_ERROR = "REQUEST_ERROR";
                    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

                    /**
                     * Prevents instantiation.
                     */
                    private ErrorCodes() {
                    }
                }
                """.formatted(exceptionPackage);
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
        return """
            package %s;

            import lombok.Builder;
            import lombok.Getter;

            /**
             * Generic runtime exception used across generated services.
             * Carries structured error information for consistent API responses.
             */
            @Getter
            public class GeneratedRuntimeException extends RuntimeException {

                private final String code;

                /**
                 * Constructs a new exception instance.
                 *
                 * @param code application error code
                 * @param message error message
                 */
                @Builder
                public GeneratedRuntimeException(String code, String message) {
                    super(message);
                    this.code = code;
                }
            }
            """.formatted(exceptionPackage);
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

    private String buildGlobalExceptionHandlerContent(String exceptionPackage) {
        String basePackage = exceptionPackage.substring(0, exceptionPackage.lastIndexOf(".exception"));

        return """
        package %s;

        %s
        %s
        @Log4j2
        @RestControllerAdvice
        @RequiredArgsConstructor
        @SuppressWarnings("unused")
        public class GlobalExceptionHandler {

            private final MessageResolver messageResolver;

        %s
        }
        """.formatted(
                exceptionPackage,
                buildGlobalExceptionHandlerImports().formatted(basePackage),
                buildGlobalExceptionHandlerClassJavaDoc(),
                buildGlobalExceptionHandlerBody()
        );
    }

    /**
     * Builds the import section of the generated {@code GlobalExceptionHandler}.
     *
     * @return generated import source content
     */
    private String buildGlobalExceptionHandlerImports() {
        return """
            import %s.util.MessageResolver;
            import jakarta.servlet.http.HttpServletRequest;
            import jakarta.validation.ConstraintViolationException;
            import lombok.RequiredArgsConstructor;
            import lombok.extern.log4j.Log4j2;
            import org.springframework.http.HttpStatus;
            import org.springframework.http.ResponseEntity;
            import org.springframework.http.converter.HttpMessageNotReadableException;
            import org.springframework.validation.FieldError;
            import org.springframework.web.bind.MethodArgumentNotValidException;
            import org.springframework.web.bind.annotation.ExceptionHandler;
            import org.springframework.web.bind.annotation.RestControllerAdvice;
            import org.springframework.web.server.ResponseStatusException;

            import java.time.Instant;
            import java.util.Comparator;
            import java.util.List;
            import java.util.Objects;
            import java.util.stream.Collectors;
            """;
    }

    /**
     * Builds the JavaDoc section of the generated {@code GlobalExceptionHandler}.
     *
     * @return generated class Javadoc source content
     */
    private String buildGlobalExceptionHandlerClassJavaDoc() {
        return """
            /**
             * Centralized exception handling for REST APIs.
             */""";
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
                + buildGenericExceptionHandlerMethod()
                + buildErrorResponseBuilderMethod()
                + buildResolveCodeMethod()
                + buildResolveHttpStatusFromErrorCodeMethod()
                + buildValidationMessageMethod()
                + buildConstraintViolationMessageMethod()
                + buildResolveMessageMethod()
                + buildSafeMessageMethod();
    }

    /**
     * Builds the {@code HttpMessageNotReadableException} handler method.
     *
     * @return generated method source content
     */
    private String buildHttpMessageNotReadableHandlerMethod() {
        return """
            /**
             * Handles malformed or unreadable request bodies.
             *
             * @param exception thrown message parsing exception
             * @param request current HTTP request
             * @return standardized bad request error response
             */
            @ExceptionHandler(HttpMessageNotReadableException.class)
            public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
                    HttpMessageNotReadableException exception,
                    HttpServletRequest request
            ) {
                log.warn("Unreadable request body at {} {}", request.getMethod(), request.getRequestURI());

                return build(
                        ErrorCodes.BAD_REQUEST,
                        HttpStatus.BAD_REQUEST,
                        resolveMessage("error.invalidRequestBody"),
                        exception,
                        request
                );
            }

        """;
    }

    /**
     * Builds the safe message helper method.
     *
     * @return generated method source content
     */
    private String buildSafeMessageMethod() {
        return """
                /**
                 * Returns the primary message when it is not blank; otherwise returns the fallback message.
                 *
                 * @param primary preferred message
                 * @param fallback fallback message
                 * @return safe non-blank message
                 */
                private String safeMessage(String primary, String fallback) {
                    String trimmedPrimary = primary == null ? "" : primary.trim();
                    if (!trimmedPrimary.isEmpty()) {
                        return trimmedPrimary;
                    }

                    String trimmedFallback = fallback == null ? "" : fallback.trim();
                    return trimmedFallback.isEmpty() ? "Error" : trimmedFallback;
                }

            """;
    }

    /**
     * Builds the constraint violation message helper method.
     *
     * @return generated method source content
     */
    private String buildConstraintViolationMessageMethod() {
        return """
            /**
             * Builds a readable validation message from constraint violations.
             *
             * @param exception constraint violation exception
             * @return resolved validation message
             */
            private String buildConstraintViolationMessage(ConstraintViolationException exception) {
                if (exception.getConstraintViolations() == null || exception.getConstraintViolations().isEmpty()) {
                    return resolveMessage("error.validationFailed");
                }

                return exception.getConstraintViolations().stream()
                        .map(violation -> violation.getPropertyPath() + ": "
                                + safeMessage(violation.getMessage(), resolveMessage("error.invalid")))
                        .distinct()
                        .collect(Collectors.joining(", "));
            }

        """;
    }

    private String buildResolveMessageMethod() {
        return """
            /**
             * Resolves an i18n message by key.
             *
             * @param key message key
             * @param arguments optional message arguments
             * @return resolved message
             */
            private String resolveMessage(String key, Object... arguments) {
                return messageResolver.resolve(key, arguments);
            }

        """;
    }

    /**
     * Builds the validation message helper method.
     *
     * @return generated method source content
     */
    private String buildValidationMessageMethod() {
        return """
            /**
             * Builds a readable validation message from field errors.
             *
             * @param exception method argument validation exception
             * @return resolved validation message
             */
            private String buildValidationMessage(MethodArgumentNotValidException exception) {
                List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors();
                if (fieldErrors.isEmpty()) {
                    return resolveMessage("error.validationFailed");
                }

                FieldError firstFieldError = fieldErrors.stream()
                        .filter(Objects::nonNull)
                        .min(Comparator.comparing(FieldError::getField))
                        .orElse(fieldErrors.getFirst());

                String detailedMessage = fieldErrors.stream()
                        .filter(Objects::nonNull)
                        .map(fieldError -> fieldError.getField() + ": "
                                + safeMessage(fieldError.getDefaultMessage(), resolveMessage("error.invalid")))
                        .distinct()
                        .collect(Collectors.joining(", "));

                String primaryMessage = firstFieldError.getField() + ": "
                        + safeMessage(firstFieldError.getDefaultMessage(), resolveMessage("error.invalid"));

                return safeMessage(primaryMessage, detailedMessage);
            }

        """;
    }

    /**
     * Builds the method that resolves HTTP status from application error code.
     *
     * @return generated method source content
     */
    private String buildResolveHttpStatusFromErrorCodeMethod() {
        return """
                /**
                 * Resolves the HTTP status from the provided application error code.
                 *
                 * @param errorCode application error code
                 * @return resolved HTTP status
                 */
                private HttpStatus resolveHttpStatusFromErrorCode(String errorCode) {
                    if (errorCode == null || errorCode.isBlank()) {
                        return HttpStatus.INTERNAL_SERVER_ERROR;
                    }

                    return switch (errorCode) {
                        case ErrorCodes.NOT_FOUND -> HttpStatus.NOT_FOUND;
                        case ErrorCodes.BAD_REQUEST, ErrorCodes.REQUEST_ERROR -> HttpStatus.BAD_REQUEST;
                        case ErrorCodes.VALIDATION_ERROR -> HttpStatus.UNPROCESSABLE_ENTITY;
                        default -> HttpStatus.INTERNAL_SERVER_ERROR;
                    };
                }

            """;
    }

    /**
     * Builds the method that resolves application error codes from HTTP status.
     *
     * @return generated method source content
     */
    private String buildResolveCodeMethod() {
        return """
                /**
                 * Resolves a stable application error code from an HTTP status.
                 *
                 * @param status HTTP status
                 * @return stable application error code
                 */
                private String resolveCode(HttpStatus status) {
                    if (status == null) {
                        return ErrorCodes.REQUEST_ERROR;
                    }

                    return switch (status) {
                        case NOT_FOUND -> ErrorCodes.NOT_FOUND;
                        case BAD_REQUEST -> ErrorCodes.BAD_REQUEST;
                        case UNPROCESSABLE_ENTITY -> ErrorCodes.VALIDATION_ERROR;
                        case UNAUTHORIZED, FORBIDDEN, CONFLICT -> ErrorCodes.REQUEST_ERROR;
                        default -> status.is4xxClientError()
                                ? ErrorCodes.REQUEST_ERROR
                                : ErrorCodes.INTERNAL_ERROR;
                    };
                }

            """;
    }

    /**
     * Builds the {@code ErrorResponse} builder helper method.
     *
     * @return generated method source content
     */
    private String buildErrorResponseBuilderMethod() {
        return """
                /**
                 * Builds a standardized {@link ErrorResponse}.
                 *
                 * @param code stable application error code
                 * @param status HTTP status
                 * @param message response message
                 * @param exception original exception
                 * @param request current HTTP request
                 * @return response entity with standardized error body
                 */
                private ResponseEntity<ErrorResponse> build(
                        String code,
                        HttpStatus status,
                        String message,
                        Exception exception,
                        HttpServletRequest request
                ) {
                    ErrorResponse response = ErrorResponse.builder()
                            .code(code)
                            .timestamp(Instant.now())
                            .status(status.value())
                            .error(status.getReasonPhrase())
                            .message(message)
                            .path(request.getRequestURI())
                            .exception(exception.getClass().getSimpleName())
                            .build();

                    return ResponseEntity.status(status).body(response);
                }

            """;
    }

    /**
     * Builds the generic {@code Exception} handler method.
     *
     * @return generated method source content
     */
    private String buildGenericExceptionHandlerMethod() {
        return """
            /**
             * Handles all unexpected exceptions.
             *
             * @param exception thrown exception
             * @param request current HTTP request
             * @return standardized internal server error response
             */
            @ExceptionHandler(Exception.class)
            public ResponseEntity<ErrorResponse> handleGeneric(
                    Exception exception,
                    HttpServletRequest request
            ) {
                log.error("Unhandled exception at {} {}", request.getMethod(), request.getRequestURI(), exception);

                return build(
                        ErrorCodes.INTERNAL_ERROR,
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        resolveMessage("error.unexpected"),
                        exception,
                        request
                );
            }

        """;
    }

    private String buildNoHandlerFoundExceptionHandlerMethod() {
        return """
            /**
             * Handles requests that do not match any controller endpoint.
             *
             * @param exception thrown no-handler exception
             * @param request current HTTP request
             * @return standardized not found error response
             */
            @ExceptionHandler(org.springframework.web.servlet.NoHandlerFoundException.class)
            public ResponseEntity<ErrorResponse> handleNoHandlerFoundException(
                    org.springframework.web.servlet.NoHandlerFoundException exception,
                    HttpServletRequest request
            ) {
                return build(
                        ErrorCodes.NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        resolveMessage("error.endpointNotFound", request.getRequestURI()),
                        exception,
                        request
                );
            }

        """;
    }

    /**
     * Builds the {@code ConstraintViolationException} handler method.
     *
     * @return generated method source content
     */
    private String buildConstraintViolationHandlerMethod() {
        return """
                /**
                 * Handles validation errors raised for request parameters and path variables.
                 *
                 * @param exception thrown constraint violation exception
                 * @param request current HTTP request
                 * @return standardized validation error response
                 */
                @ExceptionHandler(ConstraintViolationException.class)
                public ResponseEntity<ErrorResponse> handleConstraintViolation(
                        ConstraintViolationException exception,
                        HttpServletRequest request
                ) {
                    String message = buildConstraintViolationMessage(exception);

                    return build(
                            ErrorCodes.VALIDATION_ERROR,
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            message,
                            exception,
                            request
                    );
                }

            """;
    }

    /**
     * Builds the {@code MethodArgumentNotValidException} handler method.
     *
     * @return generated method source content
     */
    private String buildMethodArgumentNotValidHandlerMethod() {
        return """
                /**
                 * Handles request body validation errors.
                 *
                 * @param exception thrown validation exception
                 * @param request current HTTP request
                 * @return standardized validation error response
                 */
                @ExceptionHandler(MethodArgumentNotValidException.class)
                public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
                        MethodArgumentNotValidException exception,
                        HttpServletRequest request
                ) {
                    String message = buildValidationMessage(exception);

                    return build(
                            ErrorCodes.VALIDATION_ERROR,
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            message,
                            exception,
                            request
                    );
                }

            """;
    }

    /**
     * Builds the {@code ResponseStatusException} handler method.
     *
     * @return generated method source content
     */
    private String buildResponseStatusExceptionHandlerMethod() {
        return """
                /**
                 * Handles {@link ResponseStatusException}.
                 *
                 * @param exception thrown response status exception
                 * @param request current HTTP request
                 * @return standardized error response
                 */
                @ExceptionHandler(ResponseStatusException.class)
                public ResponseEntity<ErrorResponse> handleResponseStatusException(
                        ResponseStatusException exception,
                        HttpServletRequest request
                ) {
                    HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
                    String message = safeMessage(exception.getReason(), exception.getMessage());

                    return build(
                            resolveCode(status),
                            status,
                            message,
                            exception,
                            request
                    );
                }

            """;
    }
}