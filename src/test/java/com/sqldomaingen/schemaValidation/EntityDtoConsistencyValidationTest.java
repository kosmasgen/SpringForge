package com.sqldomaingen.schemaValidation;

import com.sqldomaingen.util.Constants;
import com.sqldomaingen.validation.EntityDtoConsistencyValidation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Validates generated DTO source files against generated entity source files.
 */
class EntityDtoConsistencyValidationTest {

    /**
     * Validates that generated DTO source files are consistent with generated entity source files.
     */
    @Test
    void shouldValidateGeneratedDtoSourceFilesAgainstGeneratedEntitySourceFiles() {
        EntityDtoConsistencyValidation validation =
                new EntityDtoConsistencyValidation(Constants.GENERATED_JAVA_ROOT);

        List<String> violations = validation.validate();

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
        builder.append("ENTITY / DTO CONSISTENCY VALIDATION ERRORS").append(System.lineSeparator());
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
}