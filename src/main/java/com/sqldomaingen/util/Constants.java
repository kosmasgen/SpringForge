package com.sqldomaingen.util;

import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;

public class Constants {

    public static final Path SCHEMA_PATH = Path.of("input", "schema.sql");


    public static final String CONTROLLER_PACKAGE = "controller";
    public static final String DTO_PACKAGE = "dto";
    public static final String SERVICE_PACKAGE = "service";
    public static final String CONFIG_PACKAGE = "config";

    public static final String API_BASE_PATH = "/api/";
    public static final String CONTROLLER_FILE_SUFFIX = "Controller.java";
    public static final String DTO_FILE_SUFFIX = "Dto.java";
    public static final String CONTROLLER_SUFFIX = "Controller";
    public static final String DTO_SUFFIX = "Dto";
    public static final String SERVICE_SUFFIX = "Service";

    public static final String MAPPER_PACKAGE = "mapper";
    public static final String ENTITY_PACKAGE = "entity";

    public static final String BASE_MAPPER_FILE_NAME = "BaseMapper.java";
    public static final String MAPPER_SUFFIX = "Mapper";
    public static final String OBJECT_TYPE = "Object";
    public static final String KEY_SUFFIX = "Key";
    public static final String PK_SUFFIX = "PK";
    public static final String ID_SUFFIX = "Id";

    public static final String JAVA_LANG_PACKAGE = "java.lang.";
    public static final String ARRAY_SUFFIX = "[]";
    public static final String API_ENTITY_PREFIX = "Api";


    public static final String DEFAULT_ID_PARAM = "id";
    public static final String DEFAULT_PK_TYPE = "Long";

    public static final String SECURITY_CONFIG_FILE_NAME = "SecurityConfig.java";
    public static final String CORS_CONFIG_FILE_NAME = "CorsConfig.java";
    public static final String EMPTY_SQL_ERROR_MESSAGE = "SQL content is empty or not set.";

    public static final int MAX_LINE_LENGTH = 100;
    public static final int MAX_LINES_PER_PAGE = 54;
    public static final String JAVA_BIG_DECIMAL = "java.math.BigDecimal";
    public static final String JAVA_STRING = "String";
    public static final String JAVA_LOCAL_DATE_TIME = "java.time.LocalDateTime";
    public static final String SPRING_BOOT_VERSION = "3.4.13";
    public static final String SPRINGDOC_VERSION = "2.8.17";
    public static final String MODELMAPPER_VERSION = "3.2.0";
    public static final long DEFAULT_JWT_EXPIRATION_MINUTES = 24 * 60;
    public static final String DEFAULT_VERSION = "v0.1.0";
    public static final Path MAIN_XML_RELATIVE_PATH = Path.of(
            "src", "main", "resources", "db", "migration", "changelogs", DEFAULT_VERSION, "main.xml"
    );

    public static final Pattern TYPE_PATTERN =
            Pattern.compile("^\\s*([A-Z0-9 ]+?)\\s*(?:\\((\\d+)(?:\\s*,\\s*(\\d+))?\\))?\\s*(\\[])?\\s*$");




    public static final Set<String> JAVA_EXCLUDED_TABLES = Set.of("audit", "syncruns_error_log");

    public static final String CONFIG_FILE = "generator-config.yml";

}
