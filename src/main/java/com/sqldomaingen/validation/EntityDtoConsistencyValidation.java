package com.sqldomaingen.validation;

import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;

/**
 * Validates generated DTO source files against generated entity source files.
 */
@RequiredArgsConstructor
public class EntityDtoConsistencyValidation {

    private final Path generatedJavaRoot;

    /**
     * Executes entity/DTO consistency validation.
     *
     * @return collected violations
     */
    public List<String> validate() {
        List<String> violations = new ArrayList<>();

        validateGeneratedJavaRootExists(violations);

        if (!violations.isEmpty()) {
            return violations;
        }

        try {
            Map<String, JavaTypeDefinition> entityBySimpleName =
                    findGeneratedTypeDefinitions("/entity/", true, false, violations);

            Map<String, JavaTypeDefinition> dtoBySimpleName =
                    findGeneratedTypeDefinitions("/dto/", false, true, violations);

            if (entityBySimpleName.isEmpty()) {
                violations.add("No generated entity source files were found under: "
                        + generatedJavaRoot.toAbsolutePath());
                return violations;
            }

            if (dtoBySimpleName.isEmpty()) {
                violations.add("No generated DTO source files were found under: "
                        + generatedJavaRoot.toAbsolutePath());
                return violations;
            }

            for (JavaTypeDefinition entityDefinition : entityBySimpleName.values()) {
                validateMatchingDtoExists(entityDefinition, dtoBySimpleName, violations);
            }

            for (JavaTypeDefinition dtoDefinition : dtoBySimpleName.values()) {
                validateMatchingEntityExists(dtoDefinition, entityBySimpleName, violations);
            }

            for (JavaTypeDefinition entityDefinition : entityBySimpleName.values()) {
                if (entityDefinition.isEmbeddable()) {
                    continue;
                }

                JavaTypeDefinition dtoDefinition =
                        dtoBySimpleName.get(entityDefinition.simpleName() + "Dto");

                if (dtoDefinition == null) {
                    continue;
                }

                validateEntityDtoPair(
                        entityDefinition,
                        dtoDefinition,
                        entityBySimpleName,
                        violations
                );
            }
        } catch (Exception exception) {
            violations.add("Entity/DTO consistency validation failed: " + exception.getMessage());
        }

        return violations;
    }

    /**
     * Validates that the generated Java root directory exists.
     *
     * @param violations collected violations
     */
    private void validateGeneratedJavaRootExists(List<String> violations) {
        if (generatedJavaRoot == null) {
            violations.add("Generated Java root is null.");
            return;
        }

        if (!generatedJavaRoot.toFile().exists()) {
            violations.add(
                    "Generated Java root does not exist: "
                            + generatedJavaRoot.toAbsolutePath()
            );
        }
    }

    /**
     * Finds generated Java type definitions from a package folder.
     *
     * @param packageMarker path marker such as /entity/ or /dto/
     * @param includeEntityLike true for entity-side discovery
     * @param includeDtoLike true for dto-side discovery
     * @param violations collected violations
     * @return parsed type definitions by simple name
     * @throws IOException if file traversal fails
     */
    private Map<String, JavaTypeDefinition> findGeneratedTypeDefinitions(
            String packageMarker,
            boolean includeEntityLike,
            boolean includeDtoLike,
            List<String> violations
    ) throws IOException {
        Map<String, JavaTypeDefinition> definitions = new LinkedHashMap<>();

        try (var paths = Files.walk(generatedJavaRoot)) {
            List<Path> javaFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().replace("\\", "/").contains(packageMarker))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();

            for (Path javaFile : javaFiles) {
                try {
                    String content = Files.readString(javaFile);
                    JavaTypeDefinition typeDefinition = parseJavaTypeFile(javaFile, content);

                    if (typeDefinition == null) {
                        continue;
                    }

                    if (includeEntityLike && (typeDefinition.isEntity() || typeDefinition.isEmbeddable())) {
                        definitions.put(typeDefinition.simpleName(), typeDefinition);
                    }

                    if (includeDtoLike && typeDefinition.isDtoLike()) {
                        definitions.put(typeDefinition.simpleName(), typeDefinition);
                    }
                } catch (Exception exception) {
                    violations.add("Could not parse generated file: " + javaFile + " -> " + exception.getMessage());
                }
            }
        }

        return definitions;
    }

    /**
     * Validates that a generated entity has a matching DTO.
     *
     * @param entityDefinition entity definition
     * @param dtoBySimpleName dto definitions by simple name
     * @param violations collected violations
     */
    private void validateMatchingDtoExists(
            JavaTypeDefinition entityDefinition,
            Map<String, JavaTypeDefinition> dtoBySimpleName,
            List<String> violations
    ) {
        if (entityDefinition.isEmbeddable()) {
            return;
        }

        String expectedDtoName = entityDefinition.simpleName() + "Dto";

        if (!dtoBySimpleName.containsKey(expectedDtoName)) {
            violations.add(
                    "[" + entityDefinition.displayName() + "] Missing matching DTO source: " + expectedDtoName
            );
        }
    }

    /**
     * Validates that a generated DTO has a matching entity.
     *
     * @param dtoDefinition dto definition
     * @param entityBySimpleName entity definitions by simple name
     * @param violations collected violations
     */
    private void validateMatchingEntityExists(
            JavaTypeDefinition dtoDefinition,
            Map<String, JavaTypeDefinition> entityBySimpleName,
            List<String> violations
    ) {
        if (!dtoDefinition.simpleName().endsWith("Dto")) {
            return;
        }

        String expectedEntityName = dtoDefinition.simpleName().substring(0, dtoDefinition.simpleName().length() - 3);

        if (!entityBySimpleName.containsKey(expectedEntityName)) {
            violations.add(
                    "[" + dtoDefinition.displayName() + "] Missing matching entity source: " + expectedEntityName
            );
        }
    }

    /**
     * Validates one entity/DTO pair.
     *
     * @param entityDefinition entity definition
     * @param dtoDefinition dto definition
     * @param violations collected violations
     */
    private void validateEntityDtoPair(
            JavaTypeDefinition entityDefinition,
            JavaTypeDefinition dtoDefinition,
            Map<String, JavaTypeDefinition> entityBySimpleName,
            List<String> violations
    ) {
        Map<String, JavaFieldDefinition> dtoFieldsByName = indexFieldsByName(dtoDefinition.fields());

        for (JavaFieldDefinition entityField : entityDefinition.fields()) {
            if (shouldSkipEntityField(entityField)) {
                continue;
            }

            String expectedDtoFieldName = entityField.name();
            String expectedDtoFieldType = resolveExpectedDtoFieldType(entityField);

            JavaFieldDefinition dtoField = dtoFieldsByName.get(expectedDtoFieldName);

            if (dtoField == null) {
                violations.add(
                        "[" + dtoDefinition.displayName() + "] Missing DTO field '" + expectedDtoFieldName
                                + "' expected from entity '" + entityDefinition.displayName() + "'"
                );
                continue;
            }

            String actualDtoFieldType = normalizeType(dtoField.type());

            if (!expectedDtoFieldType.equals(actualDtoFieldType)) {
                violations.add(
                        "[" + dtoDefinition.displayName() + "] DTO field '" + expectedDtoFieldName
                                + "' has type '" + actualDtoFieldType
                                + "' but expected '" + expectedDtoFieldType
                                + "' from entity field '" + entityField.type() + "'"
                );
            }
        }

        Map<String, JavaFieldDefinition> entityFieldsByName = indexFieldsByName(entityDefinition.fields());

        for (JavaFieldDefinition dtoField : dtoDefinition.fields()) {
            if (entityFieldsByName.containsKey(dtoField.name())) {
                continue;
            }

            if (isNestedDtoFieldMappedByEmbeddedId(dtoField, entityDefinition, entityBySimpleName)) {
                continue;
            }

            violations.add(
                    "[" + dtoDefinition.displayName() + "] Unexpected DTO field without matching entity field: '"
                            + dtoField.name() + "'"
            );
        }
    }

    /**
     * Checks whether a nested DTO field is mapped through an EmbeddedId key
     * by verifying actual key fields.
     *
     * @param dtoField DTO field definition
     * @param entityDefinition entity definition
     * @param entityBySimpleName all entity definitions (including embeddables)
     * @return true when mapping exists in key class
     */
    private boolean isNestedDtoFieldMappedByEmbeddedId(
            JavaFieldDefinition dtoField,
            JavaTypeDefinition entityDefinition,
            Map<String, JavaTypeDefinition> entityBySimpleName
    ) {
        if (dtoField == null || dtoField.name() == null || dtoField.name().isBlank()) {
            return false;
        }

        // find id field
        JavaFieldDefinition idField = entityDefinition.fields().stream()
                .filter(field -> field.hasAnnotation("EmbeddedId"))
                .findFirst()
                .orElse(null);

        if (idField == null) {
            return false;
        }

        String keyTypeName = normalizeType(idField.type());

        JavaTypeDefinition keyDefinition = entityBySimpleName.get(keyTypeName);
        if (keyDefinition == null) {
            return false;
        }

        String expectedKeyField = dtoField.name().trim() + "Id";

        return keyDefinition.fields().stream()
                .anyMatch(field -> field.name().equals(expectedKeyField));
    }

    /**
     * Returns true when an entity field should be ignored for DTO compatibility checks.
     *
     * @param entityField entity field
     * @return true when the field should be skipped
     */
    private boolean shouldSkipEntityField(JavaFieldDefinition entityField) {
        return entityField.hasAnnotation("Transient");
    }

    /**
     * Resolves the expected DTO field type from an entity field declaration.
     *
     * @param entityField entity field
     * @return expected DTO field type
     */
    private String resolveExpectedDtoFieldType(JavaFieldDefinition entityField) {
        String normalizedEntityFieldType = normalizeType(entityField.type());

        if (entityField.hasAnnotation("EmbeddedId")) {
            return normalizedEntityFieldType;
        }

        if (entityField.hasAnnotation("ManyToOne") || entityField.hasAnnotation("OneToOne")) {
            return toDtoType(normalizedEntityFieldType);
        }

        if (entityField.hasAnnotation("OneToMany") || entityField.hasAnnotation("ManyToMany")) {
            return convertCollectionEntityTypeToDtoType(normalizedEntityFieldType);
        }

        return normalizedEntityFieldType;
    }

    /**
     * Converts a single entity type to its DTO equivalent.
     *
     * @param entityType normalized entity type
     * @return dto type
     */
    private String toDtoType(String entityType) {
        if (isKeyType(entityType)) {
            return entityType;
        }

        return entityType.endsWith("Dto") ? entityType : entityType + "Dto";
    }

    /**
     * Converts a collection entity type to a DTO collection type.
     *
     * @param entityCollectionType normalized collection type
     * @return dto collection type
     */
    private String convertCollectionEntityTypeToDtoType(String entityCollectionType) {
        int genericStart = entityCollectionType.indexOf('<');
        int genericEnd = entityCollectionType.lastIndexOf('>');

        if (genericStart < 0 || genericEnd <= genericStart) {
            return entityCollectionType;
        }

        String outerType = entityCollectionType.substring(0, genericStart).trim();
        String innerType = entityCollectionType.substring(genericStart + 1, genericEnd).trim();

        return outerType + "<" + toDtoType(innerType) + ">";
    }

    /**
     * Returns true when the type represents an entity key type.
     *
     * @param typeName type name
     * @return true when key-like
     */
    private boolean isKeyType(String typeName) {
        String normalizedType = normalizeType(typeName);

        return normalizedType.endsWith("Key") || normalizedType.endsWith("PK");
    }

    /**
     * Indexes fields by field name.
     *
     * @param fields field list
     * @return indexed field map
     */
    private Map<String, JavaFieldDefinition> indexFieldsByName(List<JavaFieldDefinition> fields) {
        Map<String, JavaFieldDefinition> fieldsByName = new LinkedHashMap<>();

        for (JavaFieldDefinition field : fields) {
            fieldsByName.put(field.name(), field);
        }

        return fieldsByName;
    }

    /**
     * Parses one generated Java source file.
     *
     * @param javaFile Java source file path
     * @param content file content
     * @return parsed type definition or null when class name cannot be resolved
     */
    private JavaTypeDefinition parseJavaTypeFile(Path javaFile, String content) {
        String normalizedContent = stripComments(content);

        String packageName = extractPackageName(normalizedContent);
        String className = extractClassName(normalizedContent);

        if (className == null || className.isBlank()) {
            return null;
        }

        boolean isEntity = normalizedContent.contains("@Entity");
        boolean isEmbeddable = normalizedContent.contains("@Embeddable");
        boolean isDtoLike = packageName != null && packageName.endsWith(".dto");

        List<JavaFieldDefinition> fieldDefinitions = parseJavaFields(normalizedContent);

        return new JavaTypeDefinition(
                javaFile,
                packageName,
                className,
                isEntity,
                isEmbeddable,
                isDtoLike,
                fieldDefinitions
        );
    }

    /**
     * Parses Java fields from a source file.
     *
     * @param content Java source content
     * @return parsed field definitions
     */
    private List<JavaFieldDefinition> parseJavaFields(String content) {
        List<JavaFieldDefinition> fieldDefinitions = new ArrayList<>();

        int classBodyStart = content.indexOf('{');
        int classBodyEnd = content.lastIndexOf('}');
        if (classBodyStart < 0 || classBodyEnd <= classBodyStart) {
            return fieldDefinitions;
        }

        String classBody = content.substring(classBodyStart + 1, classBodyEnd);
        List<String> lines = classBody.lines().toList();

        List<String> pendingAnnotations = new ArrayList<>();
        StringBuilder currentAnnotation = null;
        int annotationParenthesesDepth = 0;

        for (String rawLine : lines) {
            String line = rawLine.trim();

            if (line.isBlank()) {
                continue;
            }

            if (currentAnnotation != null) {
                currentAnnotation.append(' ').append(line);
                annotationParenthesesDepth += countOccurrences(line, '(');
                annotationParenthesesDepth -= countOccurrences(line, ')');

                if (annotationParenthesesDepth <= 0) {
                    pendingAnnotations.add(currentAnnotation.toString().trim());
                    currentAnnotation = null;
                    annotationParenthesesDepth = 0;
                }

                continue;
            }

            if (line.startsWith("@")) {
                currentAnnotation = new StringBuilder(line);
                annotationParenthesesDepth = countOccurrences(line, '(') - countOccurrences(line, ')');

                if (annotationParenthesesDepth <= 0) {
                    pendingAnnotations.add(currentAnnotation.toString().trim());
                    currentAnnotation = null;
                    annotationParenthesesDepth = 0;
                }

                continue;
            }

            if (looksLikeFieldDeclaration(line)) {
                String fieldType = extractFieldType(line);
                String fieldName = extractFieldName(line);

                if (fieldType != null && fieldName != null) {
                    fieldDefinitions.add(new JavaFieldDefinition(
                            fieldName,
                            fieldType,
                            parseAnnotations(pendingAnnotations)
                    ));
                }

                pendingAnnotations = new ArrayList<>();

            }

        }

        return fieldDefinitions;
    }

    /**
     * Parses annotation lines into annotation definitions.
     *
     * @param annotationLines raw annotation lines
     * @return parsed annotations
     */
    private List<AnnotationDefinition> parseAnnotations(List<String> annotationLines) {
        List<AnnotationDefinition> annotations = new ArrayList<>();

        for (String annotationLine : annotationLines) {
            String trimmed = annotationLine.trim();

            Matcher matcher = java.util.regex.Pattern
                    .compile("^@([A-Za-z0-9_]+)(\\((.*)\\))?$")
                    .matcher(trimmed);

            if (!matcher.find()) {
                continue;
            }

            String annotationName = matcher.group(1);
            annotations.add(new AnnotationDefinition(annotationName));
        }

        return annotations;
    }

    /**
     * Counts occurrences of one character.
     *
     * @param value source text
     * @param target target character
     * @return occurrence count
     */
    private int countOccurrences(String value, char target) {
        int count = 0;

        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) == target) {
                count++;
            }
        }

        return count;
    }

    /**
     * Extracts the Java package name.
     *
     * @param content Java source content
     * @return package name or null
     */
    private String extractPackageName(String content) {
        Matcher matcher = java.util.regex.Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;").matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Extracts the Java class name.
     *
     * @param content Java source content
     * @return class name or null
     */
    private String extractClassName(String content) {
        Matcher matcher = java.util.regex.Pattern.compile("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)\\b").matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Returns true when a line looks like a Java field declaration.
     *
     * @param line raw line
     * @return true when field declaration
     */
    private boolean looksLikeFieldDeclaration(String line) {
        return line.endsWith(";")
                && (line.startsWith("private ") || line.startsWith("protected ") || line.startsWith("public "))
                && !line.contains("(");
    }

    /**
     * Extracts the field type from a field declaration.
     *
     * @param line field declaration
     * @return field type or null
     */
    private String extractFieldType(String line) {
        String normalized = line.replace(";", "").trim();
        String[] parts = normalized.split("\\s+");

        if (parts.length < 3) {
            return null;
        }

        int index = 0;
        while (index < parts.length && isFieldModifier(parts[index])) {
            index++;
        }

        if (index >= parts.length - 1) {
            return null;
        }

        return normalizeType(parts[index]);
    }

    /**
     * Extracts the field name from a field declaration.
     *
     * @param line field declaration
     * @return field name or null
     */
    private String extractFieldName(String line) {
        String normalized = line.replace(";", "").trim();

        int initializerIndex = normalized.indexOf('=');
        if (initializerIndex >= 0) {
            normalized = normalized.substring(0, initializerIndex).trim();
        }

        String[] parts = normalized.split("\\s+");

        if (parts.length < 2) {
            return null;
        }

        return parts[parts.length - 1];
    }

    /**
     * Returns true when a token is a field modifier.
     *
     * @param token raw token
     * @return true when modifier
     */
    private boolean isFieldModifier(String token) {
        return token.equals("private")
                || token.equals("protected")
                || token.equals("public")
                || token.equals("final")
                || token.equals("static")
                || token.equals("transient")
                || token.equals("volatile");
    }

    /**
     * Normalizes Java types for stable comparisons.
     *
     * @param typeName raw type name
     * @return normalized type
     */
    private String normalizeType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return "";
        }

        String normalizedType = typeName.trim();

        if (normalizedType.contains(".")) {
            normalizedType = normalizedType.substring(normalizedType.lastIndexOf('.') + 1);
        }

        return normalizedType.replaceAll("\\s+", "");
    }

    /**
     * Removes comments from Java source code.
     *
     * @param content raw Java source
     * @return source without comments
     */
    private String stripComments(String content) {
        String withoutBlockComments = content.replaceAll("(?s)/\\*.*?\\*/", "");
        return withoutBlockComments.replaceAll("(?m)//.*$", "");
    }


    /**
     * Parsed Java type definition from source code.
     *
     * @param sourceFile source file path
     * @param packageName package name
     * @param simpleName class simple name
     * @param isEntity whether @Entity exists
     * @param isEmbeddable whether @Embeddable exists
     * @param isDtoLike whether this class belongs to dto package
     * @param fields parsed fields
     */
    private record JavaTypeDefinition(
            Path sourceFile,
            String packageName,
            String simpleName,
            boolean isEntity,
            boolean isEmbeddable,
            boolean isDtoLike,
            List<JavaFieldDefinition> fields
    ) {
        /**
         * Returns a human-readable type display name.
         *
         * @return display name
         */
        private String displayName() {
            return packageName == null || packageName.isBlank()
                    ? simpleName
                    : packageName + "." + simpleName;
        }
    }

    /**
     * Parsed Java field definition from source code.
     *
     * @param name field name
     * @param type field type
     * @param annotations field annotations
     */
    private record JavaFieldDefinition(
            String name,
            String type,
            List<AnnotationDefinition> annotations
    ) {
        /**
         * Returns true when the field has the given annotation.
         *
         * @param annotationName simple annotation name
         * @return true when present
         */
        private boolean hasAnnotation(String annotationName) {
            return annotations.stream().anyMatch(annotation -> annotation.name().equals(annotationName));
        }
    }

    /**
     * Parsed annotation definition from source code.
     *
     * @param name annotation simple name
     */
    private record AnnotationDefinition(String name) {
    }
}