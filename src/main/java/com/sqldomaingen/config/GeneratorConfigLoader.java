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

        return config;
    }
}