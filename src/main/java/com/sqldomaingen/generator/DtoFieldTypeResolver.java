package com.sqldomaingen.generator;

import com.sqldomaingen.model.Field;
import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.util.JavaTypeSupport;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves DTO field Java types from entity field metadata.
 */
public final class DtoFieldTypeResolver {

    private DtoFieldTypeResolver() {
    }

    /**
     * Resolves the DTO field Java type from entity field metadata.
     *
     * <p>
     * Full DTO mode rules:
     * <ul>
     *     <li>Relationship fields are converted to nested DTO types</li>
     *     <li>Collection relationships preserve their container type and convert the element type to DTO</li>
     *     <li>Embedded/composite key types remain entity key types and do not become DTOs</li>
     *     <li>Scalar fields keep their simplified Java type</li>
     * </ul>
     *
     * @param field source field metadata
     * @return resolved DTO field type
     */
    public static String resolveDtoFieldType(Field field) {
        if (field == null) {
            return "Object";
        }

        String rawType = GeneratorSupport.trimToEmpty(field.getType());
        String simplifiedType = simplifyType(rawType);

        if (simplifiedType.isBlank()) {
            return "Object";
        }

        if (isEntityKeyType(simplifiedType)) {
            return simplifiedType;
        }

        if (field.isRelationship()) {
            return convertRelationshipTypeToDtoType(simplifiedType);
        }

        return simplifiedType;
    }

    /**
     * Converts a relationship type to its DTO equivalent while preserving containers.
     *
     * <p>
     * Examples:
     * <ul>
     *     <li>Country -> CountryDto</li>
     *     <li>List&lt;Country&gt; -> List&lt;CountryDto&gt;</li>
     *     <li>Set&lt;Languages&gt; -> Set&lt;LanguagesDto&gt;</li>
     *     <li>BusinessLocationI18nKey -> BusinessLocationI18nKey</li>
     * </ul>
     *
     * @param simplifiedType simplified relationship type
     * @return DTO relationship type
     */
    private static String convertRelationshipTypeToDtoType(String simplifiedType) {
        String normalizedType = GeneratorSupport.trimToEmpty(simplifiedType);

        if (normalizedType.isBlank()) {
            return "Object";
        }

        int genericStart = normalizedType.indexOf('<');
        int genericEnd = normalizedType.lastIndexOf('>');

        if (genericStart > 0 && genericEnd > genericStart) {
            String outerType = normalizedType.substring(0, genericStart).trim();
            String genericContent = normalizedType.substring(genericStart + 1, genericEnd).trim();

            List<String> convertedArguments = splitGenericArguments(genericContent).stream()
                    .map(DtoFieldTypeResolver::convertSingleRelationshipArgumentToDtoType)
                    .toList();

            return outerType + "<" + String.join(", ", convertedArguments) + ">";
        }

        return convertSingleRelationshipArgumentToDtoType(normalizedType);
    }

    /**
     * Converts a single relationship argument type to its DTO equivalent.
     *
     * @param typeName simplified type name
     * @return converted DTO type or original key/scalar/container type
     */
    private static String convertSingleRelationshipArgumentToDtoType(String typeName) {
        String normalizedType = simplifyType(typeName);

        if (normalizedType.isBlank()) {
            return "Object";
        }

        if (JavaTypeSupport.isScalarType(normalizedType)) {
            return normalizedType;
        }

        if (isEntityKeyType(normalizedType)) {
            return normalizedType;
        }

        return normalizedType.endsWith("Dto") ? normalizedType : normalizedType + "Dto";
    }

    /**
     * Determines whether the provided type represents an entity key type that must
     * remain on the entity side and must not become a DTO type.
     *
     * @param simpleType simplified type name
     * @return true when the type is a key-like entity type
     */
    private static boolean isEntityKeyType(String simpleType) {
        String normalizedType = GeneratorSupport.trimToEmpty(simpleType);

        return normalizedType.endsWith("Key")
                || normalizedType.endsWith("PK")
                || normalizedType.endsWith("Id");
    }

    /**
     * Simplifies a raw Java type by removing package names while preserving generic structure.
     *
     * @param rawType raw Java type
     * @return simplified Java type
     */
    public static String simplifyType(String rawType) {
        String normalizedType = GeneratorSupport.trimToEmpty(rawType);

        if (normalizedType.isEmpty()) {
            return "Object";
        }

        if (normalizedType.endsWith("[]")) {
            String elementType = normalizedType.substring(0, normalizedType.length() - 2);
            return simplifyType(elementType) + "[]";
        }

        int genericStart = normalizedType.indexOf('<');
        int genericEnd = normalizedType.lastIndexOf('>');

        if (genericStart > 0 && genericEnd > genericStart) {
            String outerType = normalizedType.substring(0, genericStart).trim();
            String genericContent = normalizedType.substring(genericStart + 1, genericEnd).trim();

            List<String> simplifiedArguments = splitGenericArguments(genericContent).stream()
                    .map(DtoFieldTypeResolver::simplifyType)
                    .toList();

            return simplifyNonGenericType(outerType) + "<" + String.join(", ", simplifiedArguments) + ">";
        }

        return simplifyNonGenericType(normalizedType);
    }



    /**
     * Splits top-level generic arguments while preserving nested generic sections.
     *
     * @param genericContent generic content without surrounding angle brackets
     * @return split generic arguments
     */
    private static List<String> splitGenericArguments(String genericContent) {
        List<String> arguments = new ArrayList<>();

        if (GeneratorSupport.trimToEmpty(genericContent).isBlank()) {
            return arguments;
        }

        StringBuilder currentArgument = new StringBuilder();
        int depth = 0;

        for (int index = 0; index < genericContent.length(); index++) {
            char currentChar = genericContent.charAt(index);

            if (currentChar == '<') {
                depth++;
                currentArgument.append(currentChar);
                continue;
            }

            if (currentChar == '>') {
                depth--;
                currentArgument.append(currentChar);
                continue;
            }

            if (currentChar == ',' && depth == 0) {
                arguments.add(currentArgument.toString().trim());
                currentArgument.setLength(0);
                continue;
            }

            currentArgument.append(currentChar);
        }

        if (!currentArgument.isEmpty()) {
            arguments.add(currentArgument.toString().trim());
        }

        return arguments;
    }

    /**
     * Simplifies a non-generic Java type by removing the package name.
     *
     * @param typeName raw non-generic type name
     * @return simplified type name
     */
    private static String simplifyNonGenericType(String typeName) {
        if (typeName.startsWith("java.lang.")) {
            return typeName.substring("java.lang.".length());
        }

        if (typeName.contains(".")) {
            return typeName.substring(typeName.lastIndexOf('.') + 1);
        }

        return typeName;
    }
}