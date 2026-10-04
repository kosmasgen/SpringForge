package com.sqldomaingen.config;

import com.sqldomaingen.util.Constants;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Loads generator configuration from generator-config.yml.
 */
public final class GeneratorConfigLoader {

    private GeneratorConfigLoader() {
    }

    /**
     * Loads generator configuration.
     *
     * @return loaded generator configuration
     */
    public static GeneratorConfig load() {
        InputStream inputStream = GeneratorConfigLoader.class
                .getClassLoader()
                .getResourceAsStream(Constants.CONFIG_FILE);

        if (inputStream == null) {
            return new GeneratorConfig();
        }

        Yaml yaml = new Yaml();

        Object loadedObject = yaml.load(inputStream);

        if (!(loadedObject instanceof Map<?, ?> yamlMap)) {
            return new GeneratorConfig();
        }

        GeneratorConfig config = new GeneratorConfig();

        Object lookupTables = yamlMap.get("lookupTables");

        if (lookupTables instanceof List<?> tableList) {
            config.setLookupTables(
                    tableList.stream()
                            .map(String::valueOf)
                            .toList()
            );
        }

        Object securityObject = yamlMap.get("security");

        if (securityObject instanceof Map<?, ?> securityMap) {
            GeneratorConfig.Security security = new GeneratorConfig.Security();

            Object enabled = securityMap.get("enabled");
            if (enabled instanceof Boolean enabledValue) {
                security.setEnabled(enabledValue);
            }

            Object userTable = securityMap.get("userTable");
            if (userTable != null) {
                security.setUserTable(String.valueOf(userTable));
            }

            Object usernameField = securityMap.get("usernameField");
            if (usernameField != null) {
                security.setUsernameField(String.valueOf(usernameField));
            }

            Object passwordField = securityMap.get("passwordField");
            if (passwordField != null) {
                security.setPasswordField(String.valueOf(passwordField));
            }

            /*
             * Load JWT configuration when it is defined under the security section.
             */
            Object jwtObject = securityMap.get("jwt");

            if (jwtObject instanceof Map<?, ?> jwtMap) {
                GeneratorConfig.Jwt jwt = new GeneratorConfig.Jwt();

                /*
                 * Enable or disable JWT authentication generation.
                 */
                Object jwtEnabled = jwtMap.get("enabled");
                if (jwtEnabled instanceof Boolean jwtEnabledValue) {
                    jwt.setEnabled(jwtEnabledValue);
                }

                /*
                 * Configure the access token lifetime in minutes when explicitly provided.
                 */
                Object expirationMinutes = jwtMap.get("expirationMinutes");
                if (expirationMinutes instanceof Number expirationMinutesValue) {
                    jwt.setExpirationMinutes(expirationMinutesValue.longValue());
                }

                security.setJwt(jwt);
            }

            config.setSecurity(security);
        }

        return config;
    }
}