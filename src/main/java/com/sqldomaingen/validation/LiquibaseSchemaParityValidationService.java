package com.sqldomaingen.validation;

import com.sqldomaingen.model.Table;
import lombok.RequiredArgsConstructor;

import java.nio.file.Path;
import java.util.List;

/**
 * Executes Liquibase/schema parity validation logic.
 */
@RequiredArgsConstructor
public class LiquibaseSchemaParityValidationService {

    private final Path schemaPath;
    private final Path liquibaseRoot;
    private final List<Table> parsedTables;

    /**
     * Runs Liquibase/schema parity validation.
     *
     * @return collected violations
     */
    public List<String> validate() {
        LiquibaseSchemaParityValidation validation =
                new LiquibaseSchemaParityValidation(
                        schemaPath,
                        liquibaseRoot,
                        parsedTables
                );

        return validation.validate();
    }
}