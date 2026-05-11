package com.sqldomaingen.validation;

import lombok.RequiredArgsConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Core validation service extracted from test logic.
 */
@RequiredArgsConstructor
public class EntitySchemaValidationService {

    private final Path schemaPath;
    private final Path generatedJavaRoot;

    /**
     * Executes validation logic.
     *
     * @return list of violations
     */
    public List<String> validate() {
        List<String> violations = new ArrayList<>();

        try {
            if (!Files.exists(schemaPath)) {
                violations.add("Missing schema file: " + schemaPath.toAbsolutePath());
                return violations;
            }

            if (!Files.exists(generatedJavaRoot)) {
                violations.add("Missing generated Java root: " + generatedJavaRoot.toAbsolutePath());
                return violations;
            }

            EntitySchemaValidator validator = new EntitySchemaValidator(
                    schemaPath,
                    generatedJavaRoot
            );

            return validator.validate();
        } catch (Exception exception) {
            violations.add("Validation execution failed: " + exception.getMessage());
            return violations;
        }
    }
}