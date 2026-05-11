package com.sqldomaingen.schemaValidation;

import com.sqldomaingen.util.Constants;
import com.sqldomaingen.validation.EntitySchemaValidator;
import org.junit.jupiter.api.Test;

import java.util.*;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the generated entity/schema validation through {@link EntitySchemaValidator}.
 *
 * <p>This test keeps the validation logic in one place and only fails when the
 * validator reports violations.
 */
class EntitySchemaValidationTest {

    /**
     * Validates generated entity source files against the SQL schema.
     */
    @Test
    void shouldValidateGeneratedEntitySourceFilesAgainstSchema() {
        EntitySchemaValidator validator = new EntitySchemaValidator(
                Constants.SCHEMA_PATH,
                Constants.GENERATED_JAVA_ROOT
        );

        printValidationChecklist();

        List<String> violations = validator.validate();

        if (!violations.isEmpty()) {
            fail(buildViolationReport(violations));
        }
    }

    /**
     * Builds the final validation error report.
     *
     * @param violations collected violations
     * @return formatted report
     */
    private String buildViolationReport(List<String> violations) {
        StringBuilder builder = new StringBuilder();

        builder.append(System.lineSeparator());
        builder.append("==================================================").append(System.lineSeparator());
        builder.append("ENTITY / SCHEMA VALIDATION ERRORS").append(System.lineSeparator());
        builder.append("==================================================").append(System.lineSeparator());

        for (int index = 0; index < violations.size(); index++) {
            builder.append(index + 1)
                    .append(". ")
                    .append(violations.get(index))
                    .append(System.lineSeparator());
        }

        builder.append("==================================================").append(System.lineSeparator());
        builder.append("Total errors: ").append(violations.size()).append(System.lineSeparator());
        builder.append("==================================================");

        return builder.toString();
    }

    /**
     * Prints the validation checks performed by this test.
     */
    private void printValidationChecklist() {
        EntitySchemaValidator validator = new EntitySchemaValidator(
                Constants.SCHEMA_PATH,
                Constants.GENERATED_JAVA_ROOT
        );

        List<String> checks = validator.getValidationChecklistLines();

        System.out.println();
        System.out.println("==================================================");
        System.out.println("ENTITY / SCHEMA VALIDATION CHECKLIST");
        System.out.println("==================================================");

        for (String check : checks) {
            System.out.println(check);
        }

        System.out.println("==================================================");
        System.out.println("Total checks: " + checks.size());
        System.out.println("==================================================");
        System.out.println();
    }
}