package com.sqldomaingen.config;

import com.sqldomaingen.util.Constants;
import com.sqldomaingen.util.GeneratorSupport;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Generator configuration loaded from generator-config.yml.
 */
@Getter
@Setter
public class GeneratorConfig {

    /**
     * Tables treated as lookup tables.
     */
    private List<String> lookupTables = new ArrayList<>();

    /**
     * Security generation configuration.
     */
    private Security security = new Security();

    /**
     * Security configuration used by the generated application.
     */
    @Getter
    @Setter
    public static class Security {

        /**
         * Enables security generation.
         */
        private boolean enabled;

        /**
         * Table used for application users.
         */
        private String userTable;

        /**
         * Field used as the authentication username.
         */
        private String usernameField;

        /**
         * Field used to store the encoded password.
         */
        private String passwordField;

        /**
         * JWT authentication configuration.
         */
        private Jwt jwt = new Jwt();
    }

    /**
     * JWT authentication configuration.
     */
    @Getter
    @Setter
    public static class Jwt {

        /**
         * Enables JWT authentication generation.
         */
        private boolean enabled;

        /**
         * Access token lifetime in minutes (24 hours).
         */
        private long expirationMinutes;
    }

    /**
     * Checks whether the given table is configured as lookup table.
     *
     * @param tableName table name
     * @return true when table is configured as lookup table
     */
    public boolean isLookupTable(String tableName) {
        if (tableName == null || tableName.isBlank() || lookupTables == null || lookupTables.isEmpty()) {
            return false;
        }

        String normalizedInputTableName = GeneratorSupport.normalizeTableName(tableName);

        return lookupTables.stream()
                .filter(configuredTableName -> configuredTableName != null
                        && !configuredTableName.isBlank())
                .map(GeneratorSupport::normalizeTableName)
                .anyMatch(configuredTableName -> configuredTableName.equals(normalizedInputTableName));
    }
}