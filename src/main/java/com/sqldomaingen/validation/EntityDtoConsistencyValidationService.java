package com.sqldomaingen.validation;

import lombok.RequiredArgsConstructor;

import java.nio.file.Path;
import java.util.List;

/**
 * Service wrapper for entity/DTO consistency validation.
 */
@RequiredArgsConstructor
public class EntityDtoConsistencyValidationService {

    private final Path generatedJavaRoot;

    /**
     * Executes entity/DTO consistency validation.
     *
     * @return collected validation violations
     */
    public List<String> validate() {
        EntityDtoConsistencyValidation validation =
                new EntityDtoConsistencyValidation(generatedJavaRoot);

        return validation.validate();
    }
}