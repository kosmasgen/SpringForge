package com.sqldomaingen.util;

/**
 * Utility class for resolving project-specific import lines used by generators.
 *
 * <p>This class centralizes import decisions for:
 * <ul>
 *     <li>DTO types</li>
 *     <li>entity types</li>
 *     <li>composite key / id helper types</li>
 * </ul>
 *
 * <p>It intentionally ignores:
 * <ul>
 *     <li>java.lang and scalar/JDK types</li>
 *     <li>collection container types</li>
 *     <li>framework/service/repository/controller/mapper types</li>
 * </ul>
 */
public final class ProjectTypeImportSupport {

    /**
     * Prevents instantiation.
     */
    private ProjectTypeImportSupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Resolves an import line for a project type using entity and DTO packages.
     *
     * @param typeName referenced type name
     * @param entityPackage entity package name
     * @param dtoPackage dto package name
     * @return import line, or empty string when no import is required
     */
    public static String resolveImportLine(
            String typeName,
            String entityPackage,
            String dtoPackage
    ) {
        String normalizedTypeName = GeneratorSupport.trimToEmpty(typeName);
        String normalizedEntityPackage = GeneratorSupport.trimToEmpty(entityPackage);
        String normalizedDtoPackage = GeneratorSupport.trimToEmpty(dtoPackage);

        if (normalizedTypeName.isBlank()) {
            return "";
        }

        if (shouldIgnoreType(normalizedTypeName)) {
            return "";
        }

        if (isDtoType(normalizedTypeName)) {
            if (normalizedDtoPackage.isBlank()) {
                return "";
            }

            return "import " + normalizedDtoPackage + "." + normalizedTypeName + ";";
        }

        if (isCompositeKeyType(normalizedTypeName)) {
            if (normalizedEntityPackage.isBlank()) {
                return "";
            }

            return "import " + normalizedEntityPackage + "." + normalizedTypeName + ";";
        }

        if (isEntityType(normalizedTypeName)) {
            if (normalizedEntityPackage.isBlank()) {
                return "";
            }

            return "import " + normalizedEntityPackage + "." + normalizedTypeName + ";";
        }

        return "";
    }

    /**
     * Returns true when the type represents a DTO.
     *
     * @param typeName referenced type name
     * @return true when the type ends with Dto
     */
    public static boolean isDtoType(String typeName) {
        return GeneratorSupport.trimToEmpty(typeName).endsWith("Dto");
    }

    /**
     * Returns true when the type represents a composite key or key-like type.
     *
     * @param typeName referenced type name
     * @return true when the type ends with Key, id, or PK
     */
    public static boolean isCompositeKeyType(String typeName) {
        String normalizedTypeName = GeneratorSupport.trimToEmpty(typeName);

        return normalizedTypeName.endsWith("Key")
                || normalizedTypeName.endsWith("Id")
                || normalizedTypeName.endsWith("PK");
    }

    /**
     * Returns true when the type should be treated as an entity type.
     *
     * @param typeName referenced type name
     * @return true when the type looks like a project entity name
     */
    public static boolean isEntityType(String typeName) {
        String normalizedTypeName = GeneratorSupport.trimToEmpty(typeName);

        if (normalizedTypeName.isBlank()) {
            return false;
        }

        if (shouldIgnoreType(normalizedTypeName)) {
            return false;
        }

        return Character.isUpperCase(normalizedTypeName.charAt(0));
    }

    /**
     * Returns true when the type should not produce a project import.
     *
     * @param typeName referenced type name
     * @return true when the type should be ignored
     */
    public static boolean shouldIgnoreType(String typeName) {
        String normalizedTypeName = GeneratorSupport.trimToEmpty(typeName);

        if (normalizedTypeName.isBlank()) {
            return true;
        }

        if (JavaTypeSupport.isScalarType(normalizedTypeName)) {
            return true;
        }

        if (JavaTypeSupport.isCollectionType(normalizedTypeName)) {
            return true;
        }

        if (isJdkContainerToken(normalizedTypeName)) {
            return true;
        }

        return isFrameworkOrLayerType(normalizedTypeName);

    }

    /**
     * Returns true when the token represents a known JDK container/helper type.
     *
     * @param typeName referenced type name
     * @return true when the type is a container/helper token
     */
    public static boolean isJdkContainerToken(String typeName) {
        String normalizedTypeName = GeneratorSupport.trimToEmpty(typeName);

        return "List".equals(normalizedTypeName)
                || "Set".equals(normalizedTypeName)
                || "Map".equals(normalizedTypeName)
                || "Collection".equals(normalizedTypeName)
                || "Iterable".equals(normalizedTypeName)
                || "ArrayList".equals(normalizedTypeName)
                || "LinkedList".equals(normalizedTypeName)
                || "HashSet".equals(normalizedTypeName)
                || "LinkedHashSet".equals(normalizedTypeName)
                || "HashMap".equals(normalizedTypeName)
                || "LinkedHashMap".equals(normalizedTypeName);
    }

    /**
     * Returns true when the type belongs to generator/framework/layer artifacts
     * that should not be imported as domain model types.
     *
     * @param typeName referenced type name
     * @return true when the type should be ignored
     */
    public static boolean isFrameworkOrLayerType(String typeName) {
        String normalizedTypeName = GeneratorSupport.trimToEmpty(typeName);

        return normalizedTypeName.endsWith("Mapper")
                || normalizedTypeName.endsWith("Service")
                || normalizedTypeName.endsWith("ServiceImpl")
                || normalizedTypeName.endsWith("Controller")
                || normalizedTypeName.endsWith("Repository")
                || normalizedTypeName.endsWith("Generator")
                || normalizedTypeName.endsWith("Test");
    }
}