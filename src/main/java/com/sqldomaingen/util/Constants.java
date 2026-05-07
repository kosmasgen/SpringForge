package com.sqldomaingen.util;

import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

public class Constants {

    public static final String EMPTY_SQL_ERROR_MESSAGE = "SQL content is empty or not set.";

    public static final int MAX_LINE_LENGTH = 100;
    public static final int MAX_LINES_PER_PAGE = 45;
    public static final String JAVA_BIG_DECIMAL = "java.math.BigDecimal";
    public static final String JAVA_STRING = "String";
    public static final String JAVA_LOCAL_DATE_TIME = "java.time.LocalDateTime";

    public static final String DTO_SUFFIX = "Dto";
    public static final String SPRING_BOOT_VERSION = "3.4.2";
    public static final String SPRINGDOC_VERSION = "2.8.5";
    public static final String MODELMAPPER_VERSION = "3.2.0";

    public static final String DEFAULT_VERSION = "v0.1.0";
    public static final Path MAIN_XML_RELATIVE_PATH = Path.of(
            "src", "main", "resources", "db", "migration", "changelogs", DEFAULT_VERSION, "main.xml"
    );

    public static final Pattern TYPE_PATTERN =
            Pattern.compile("^\\s*([A-Z0-9 ]+?)\\s*(?:\\((\\d+)(?:\\s*,\\s*(\\d+))?\\))?\\s*(\\[])?\\s*$");


    public static final Path SCHEMA_PATH = Path.of("input", "test_script.sql");
    public static final Path GENERATED_JAVA_ROOT = Path.of("output", "PepsiTest", "src", "main", "java");

    public static final Set<String> JAVA_EXCLUDED_TABLES = Set.of(
            "audit",
            "syncruns_error_log"

    );

}
