package com.sqldomaingen.validation;

import com.sqldomaingen.model.Column;
import com.sqldomaingen.util.Constants;
import com.sqldomaingen.util.TypeMapper;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Executes entity/schema validation without depending on test classes.
 */
@RequiredArgsConstructor
public class EntitySchemaValidator {

    private final Path schemaPath;
    private final Path generatedJavaRoot;

    /**
     * Runs validation and returns violations.
     *
     * @return list of violations
     */
    public List<String> validate() {
        List<String> violations = new ArrayList<>();

        try {
            if (!Files.exists(schemaPath)) {
                violations.add("Missing schema file: " + schemaPath.toAbsolutePath());
                return violations;
            }

            if (!Files.exists(generatedJavaRoot)) {
                violations.add("Missing generated Java root: " + generatedJavaRoot.toAbsolutePath());
                return violations;
            }

            String sql = Files.readString(schemaPath);
            Map<String, TableDefinition> schemaTables = parseSchema(sql);

            List<JavaEntityDefinition> entityDefinitions = findGeneratedEntityDefinitions(violations);
            printEntitiesWithTodoComments(entityDefinitions);

            if (entityDefinitions.isEmpty()) {
                violations.add("No generated entity source files were found under: "
                        + generatedJavaRoot.toAbsolutePath());
                return violations;
            }

            Map<String, JavaEntityDefinition> entityBySimpleName = entityDefinitions.stream()
                    .collect(Collectors.toMap(
                            JavaEntityDefinition::simpleName,
                            entity -> entity,
                            (left, right) -> left,
                            LinkedHashMap::new
                    ));

            for (JavaEntityDefinition entityDefinition : entityDefinitions) {
                validateEntityDefinition(entityDefinition, schemaTables, entityBySimpleName, violations);
            }
        } catch (Exception exception) {
            violations.add("Validation execution failed: " + exception.getMessage());
        }

        return violations;
    }

    private List<JavaEntityDefinition> findGeneratedEntityDefinitions(List<String> violations) throws IOException {
        List<JavaEntityDefinition> entityDefinitions = new ArrayList<>();

        try (var paths = Files.walk(generatedJavaRoot)) {
            List<Path> javaFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().replace("\\", "/").contains("/entity/"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();

            for (Path javaFile : javaFiles) {
                try {
                    String content = Files.readString(javaFile);
                    JavaEntityDefinition entityDefinition = parseJavaEntityFile(javaFile, content);

                    if (entityDefinition != null && (entityDefinition.isEntity() || entityDefinition.isEmbeddable())) {
                        entityDefinitions.add(entityDefinition);
                    }
                } catch (Exception exception) {
                    violations.add("Could not parse entity file: " + javaFile + " -> " + exception.getMessage());
                }
            }
        }

        return entityDefinitions;
    }

    private JavaEntityDefinition parseJavaEntityFile(Path javaFile, String content) {
        String normalizedContent = stripComments(content);

        String packageName = extractPackageName(normalizedContent);
        String className = extractClassName(normalizedContent);

        if (className == null || className.isBlank()) {
            return null;
        }

        boolean isEntity = normalizedContent.contains("@Entity");
        boolean isMappedSuperclass = normalizedContent.contains("@MappedSuperclass");
        boolean isEmbeddable = normalizedContent.contains("@Embeddable");
        boolean hasTodoComment = content.contains("TODO");

        String tableName = extractTableName(normalizedContent);
        String tableSchema = extractTableSchema(normalizedContent);

        List<JavaFieldDefinition> fieldDefinitions = parseJavaFields(normalizedContent);

        return new JavaEntityDefinition(
                javaFile,
                packageName,
                className,
                isEntity,
                isMappedSuperclass,
                isEmbeddable,
                hasTodoComment,
                tableSchema,
                tableName,
                fieldDefinitions
        );
    }

    /**
     * Validates one parsed entity definition against the SQL schema.
     *
     * @param entityDefinition parsed entity definition
     * @param schemaTables parsed schema tables
     * @param entityBySimpleName parsed entities by simple name
     * @param violations collected violations
     */
    private void validateEntityDefinition(
            JavaEntityDefinition entityDefinition,
            Map<String, TableDefinition> schemaTables,
            Map<String, JavaEntityDefinition> entityBySimpleName,
            List<String> violations
    ) {
        if (entityDefinition.isEmbeddable()) {
            return;
        }

        if (entityDefinition.tableName() == null || entityDefinition.tableName().isBlank()) {
            violations.add("[" + entityDefinition.displayName() + "] Missing @Table or blank @Table(name)");
            return;
        }

        String requestedTableName = buildPhysicalTableName(entityDefinition.tableSchema(), entityDefinition.tableName());
        TableDefinition tableDefinition = resolveTableDefinition(
                schemaTables,
                entityDefinition.tableSchema(),
                entityDefinition.tableName()
        );

        if (tableDefinition == null) {
            violations.add("[" + entityDefinition.displayName() + "] Table not found in SQL schema: " + requestedTableName);
            return;
        }

        validateIdentifier(entityDefinition, violations);
        validateDuplicateFieldNames(entityDefinition, violations);
        validateSimpleColumns(entityDefinition, tableDefinition, entityBySimpleName, violations);
        validateJavaTypes(entityDefinition, tableDefinition, violations);
        validateColumnConstraints(entityDefinition, tableDefinition, violations);
        validateMapsIdConsistency(entityDefinition, entityBySimpleName, violations);
        validateRelations(entityDefinition, tableDefinition, schemaTables, entityBySimpleName, violations);
        validateMissingInverseOneToOneRelations(tableDefinition, schemaTables, entityBySimpleName, violations);
        validateForeignKeyTypeCompatibility(entityDefinition, tableDefinition, schemaTables, violations);
        validateForeignKeyCoverage(entityDefinition, tableDefinition, schemaTables, violations);
        validateMissingTableColumns(entityDefinition, tableDefinition, entityBySimpleName, violations);
    }

    /**
     * Validates that every @MapsId value matches a real field inside the embedded id class.
     *
     * @param entityDefinition parsed entity definition
     * @param entityBySimpleName parsed entities by simple name
     * @param violations collected violations
     */
    private void validateMapsIdConsistency(
            JavaEntityDefinition entityDefinition,
            Map<String, JavaEntityDefinition> entityBySimpleName,
            List<String> violations
    ) {
        JavaFieldDefinition embeddedIdField = findEmbeddedIdField(entityDefinition);

        for (JavaFieldDefinition field : entityDefinition.fields()) {
            if (!field.hasAnnotation("MapsId")) {
                continue;
            }

            String mapsIdValue = field.annotationAttribute("MapsId", "value");

            if (mapsIdValue == null || mapsIdValue.isBlank()) {
                violations.add("[" + entityDefinition.displayName() + "] Field '" + field.name()
                        + "' declares @MapsId without a value");
                continue;
            }

            if (embeddedIdField == null) {
                violations.add("[" + entityDefinition.displayName() + "] Field '" + field.name()
                        + "' declares @MapsId(\"" + mapsIdValue + "\") but entity has no @EmbeddedId field");
                continue;
            }

            JavaEntityDefinition embeddedIdDefinition = resolveEmbeddedIdDefinition(embeddedIdField, entityBySimpleName);

            if (embeddedIdDefinition == null) {
                violations.add("[" + entityDefinition.displayName() + "] Field '" + field.name()
                        + "' declares @MapsId(\"" + mapsIdValue + "\") but embedded id class '"
                        + embeddedIdField.type() + "' was not found");
                continue;
            }

            JavaFieldDefinition mappedIdField = findFieldByName(embeddedIdDefinition, mapsIdValue);

            if (mappedIdField == null) {
                violations.add("[" + entityDefinition.displayName() + "] Field '" + field.name()
                        + "' declares @MapsId(\"" + mapsIdValue + "\") but embedded id class '"
                        + embeddedIdDefinition.displayName() + "' has no field named '" + mapsIdValue + "'");
                continue;
            }

            validateMapsIdJoinColumnCoverage(entityDefinition, field, mappedIdField, violations);
        }
    }

    /**
     * Validates that the @MapsId field column is covered by the relation join column.
     *
     * @param entityDefinition parsed entity definition
     * @param relationField relation field using @MapsId
     * @param mappedIdField field inside the embedded id class
     * @param violations collected violations
     */
    private void validateMapsIdJoinColumnCoverage(
            JavaEntityDefinition entityDefinition,
            JavaFieldDefinition relationField,
            JavaFieldDefinition mappedIdField,
            List<String> violations
    ) {
        List<String> joinColumnNames = resolveJoinColumnNames(relationField);
        String mappedIdColumnName = resolveColumnName(mappedIdField);

        boolean coveredByJoinColumn = joinColumnNames.stream()
                .map(this::normalizeName)
                .anyMatch(joinColumnName -> joinColumnName.equals(normalizeName(mappedIdColumnName)));

        if (!coveredByJoinColumn) {
            violations.add("[" + entityDefinition.displayName() + "] Field '" + relationField.name()
                    + "' declares @MapsId(\"" + mappedIdField.name() + "\") but join columns "
                    + joinColumnNames + " do not cover embedded id column '" + mappedIdColumnName + "'");
        }
    }

    /**
     * Validates that every local foreign-key column has the same Java type as the referenced target column.
     *
     * @param entityDefinition parsed entity definition
     * @param tableDefinition parsed SQL table definition
     * @param schemaTables parsed schema tables
     * @param violations collected violations
     */
    private void validateForeignKeyTypeCompatibility(
            JavaEntityDefinition entityDefinition,
            TableDefinition tableDefinition,
            Map<String, TableDefinition> schemaTables,
            List<String> violations
    ) {
        for (ForeignKeyDefinition foreignKeyDefinition : tableDefinition.foreignKeys()) {
            ColumnDefinition sourceColumnDefinition =
                    tableDefinition.columns().get(normalizeName(foreignKeyDefinition.sourceColumn()));

            if (sourceColumnDefinition == null) {
                continue;
            }

            TableDefinition targetTableDefinition = resolveTableDefinitionByPhysicalOrUnqualifiedName(
                    schemaTables,
                    foreignKeyDefinition.targetTable()
            );

            if (targetTableDefinition == null) {
                continue;
            }

            ColumnDefinition targetColumnDefinition =
                    targetTableDefinition.columns().get(normalizeName(foreignKeyDefinition.targetColumn()));

            if (targetColumnDefinition == null) {
                violations.add("[" + entityDefinition.displayName() + "] Foreign key column '"
                        + foreignKeyDefinition.sourceColumn() + "' targets missing column '"
                        + foreignKeyDefinition.targetColumn() + "' in table '"
                        + targetTableDefinition.fullName() + "'");
                continue;
            }

            String sourceJavaType = simpleType(TypeMapper.mapToJavaType(toModelColumn(sourceColumnDefinition)));
            String targetJavaType = simpleType(TypeMapper.mapToJavaType(toModelColumn(targetColumnDefinition)));

            if (!Objects.equals(sourceJavaType, targetJavaType)) {
                violations.add("[" + entityDefinition.displayName() + "] Foreign key type mismatch for column '"
                        + foreignKeyDefinition.sourceColumn() + "' in table '" + tableDefinition.fullName()
                        + "'. Source Java type: " + sourceJavaType
                        + ", target Java type: " + targetJavaType
                        + ", target: " + targetTableDefinition.fullName()
                        + "." + foreignKeyDefinition.targetColumn());
            }
        }
    }

    /**
     * Finds the entity field annotated with @EmbeddedId.
     *
     * @param entityDefinition parsed entity definition
     * @return embedded id field or null
     */
    private JavaFieldDefinition findEmbeddedIdField(JavaEntityDefinition entityDefinition) {
        return entityDefinition.fields().stream()
                .filter(field -> field.hasAnnotation("EmbeddedId"))
                .findFirst()
                .orElse(null);
    }

    /**
     * Finds a field by Java field name.
     *
     * @param entityDefinition parsed entity definition
     * @param fieldName Java field name
     * @return matching field or null
     */
    private JavaFieldDefinition findFieldByName(JavaEntityDefinition entityDefinition, String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return null;
        }

        return entityDefinition.fields().stream()
                .filter(field -> field.name().equals(fieldName))
                .findFirst()
                .orElse(null);
    }

    private void validateJavaTypes(
            JavaEntityDefinition entityDefinition,
            TableDefinition tableDefinition,
            List<String> violations
    ) {
        for (ColumnDefinition columnDefinition : tableDefinition.columns().values()) {
            JavaFieldDefinition javaFieldDefinition = findJavaFieldForColumn(entityDefinition, columnDefinition);

            if (javaFieldDefinition == null) {
                continue;
            }

            if (javaFieldDefinition.hasAnnotation("ManyToOne")
                    || javaFieldDefinition.hasAnnotation("OneToOne")
                    || javaFieldDefinition.hasAnnotation("OneToMany")
                    || javaFieldDefinition.hasAnnotation("ManyToMany")
                    || javaFieldDefinition.hasAnnotation("Transient")) {
                continue;
            }

            String expectedJavaType = TypeMapper.mapToJavaType(toModelColumn(columnDefinition));
            String actualJavaType = simpleType(javaFieldDefinition.type());

            if (expectedJavaType == null || expectedJavaType.isBlank()) {
                continue;
            }

            if (!simpleType(expectedJavaType).equals(actualJavaType)) {
                violations.add("""
                        [%s] Java type mismatch for column '%s':
                          SQL type: %s
                          Expected Java: %s
                          Actual Java: %s
                        """.formatted(
                        entityDefinition.displayName(),
                        columnDefinition.name(),
                        columnDefinition.sqlType(),
                        simpleType(expectedJavaType),
                        actualJavaType
                ));
            }
        }
    }

    private TableDefinition resolveTableDefinition(
            Map<String, TableDefinition> schemaTables,
            String schema,
            String tableName
    ) {
        String fullTableName = buildPhysicalTableName(schema, tableName);

        TableDefinition exactMatch = schemaTables.get(normalizeName(fullTableName));
        if (exactMatch != null) {
            return exactMatch;
        }

        String unqualifiedTableName = sanitizeIdentifier(tableName);

        List<TableDefinition> matches = schemaTables.values().stream()
                .filter(table -> normalizeName(extractUnqualifiedTableName(table.fullName()))
                        .equals(normalizeName(unqualifiedTableName)))
                .toList();

        if (matches.size() == 1) {
            return matches.getFirst();
        }

        return null;
    }

    private JavaEntityDefinition resolveEmbeddedIdDefinition(
            JavaFieldDefinition field,
            Map<String, JavaEntityDefinition> entityBySimpleName
    ) {
        return entityBySimpleName.get(simpleTypeName(field.type()));
    }

    private String extractUnqualifiedTableName(String fullTableName) {
        String sanitized = sanitizeIdentifier(fullTableName);

        if (sanitized.contains(".")) {
            return sanitized.substring(sanitized.lastIndexOf('.') + 1);
        }

        return sanitized;
    }

    private void validateIdentifier(JavaEntityDefinition entityDefinition, List<String> violations) {
        boolean hasIdentifier = entityDefinition.fields().stream()
                .anyMatch(field -> field.hasAnnotation("Id") || field.hasAnnotation("EmbeddedId"));

        if (!hasIdentifier) {
            violations.add("[" + entityDefinition.displayName() + "] Missing @Id or @EmbeddedId");
        }
    }

    private void validateSimpleColumns(
            JavaEntityDefinition entityDefinition,
            TableDefinition tableDefinition,
            Map<String, JavaEntityDefinition> entityBySimpleName,
            List<String> violations
    ) {
        for (JavaFieldDefinition field : entityDefinition.fields()) {
            if (shouldSkipSimpleColumnValidation(field, entityBySimpleName)) {
                continue;
            }

            if (field.hasAnnotation("EmbeddedId")) {
                JavaEntityDefinition embeddedIdDefinition = resolveEmbeddedIdDefinition(field, entityBySimpleName);

                if (embeddedIdDefinition != null) {
                    for (JavaFieldDefinition embeddedField : embeddedIdDefinition.fields()) {
                        if (embeddedField.hasAnnotation("Transient")) {
                            continue;
                        }

                        String expectedColumnName = resolveColumnName(embeddedField);
                        ColumnDefinition columnDefinition = tableDefinition.columns().get(normalizeName(expectedColumnName));

                        if (columnDefinition == null) {
                            violations.add("[" + entityDefinition.displayName() + "] Missing DB column for embedded id field '"
                                    + embeddedField.name() + "' -> expected column '" + expectedColumnName
                                    + "' in table '" + tableDefinition.fullName() + "'");
                        }
                    }
                }

                continue;
            }

            String expectedColumnName = resolveColumnName(field);
            ColumnDefinition columnDefinition = tableDefinition.columns().get(normalizeName(expectedColumnName));

            if (columnDefinition == null) {
                violations.add("[" + entityDefinition.displayName() + "] Missing DB column for field '" + field.name()
                        + "' -> expected column '" + expectedColumnName
                        + "' in table '" + tableDefinition.fullName() + "'");
            }
        }
    }

    private void validateRelations(
            JavaEntityDefinition entityDefinition,
            TableDefinition tableDefinition,
            Map<String, TableDefinition> schemaTables,
            Map<String, JavaEntityDefinition> entityBySimpleName,
            List<String> violations
    ) {
        for (JavaFieldDefinition field : entityDefinition.fields()) {
            if (field.hasAnnotation("ManyToOne")) {
                validateManyToOneField(entityDefinition, field, tableDefinition, schemaTables, entityBySimpleName, violations);
            }

            if (field.hasAnnotation("OneToOne")) {
                validateOneToOneField(entityDefinition, field, tableDefinition, schemaTables, entityBySimpleName, violations);
            }

            if (field.hasAnnotation("OneToMany")) {
                validateOneToManyField(entityDefinition, field, entityBySimpleName, violations);
            }

            if (field.hasAnnotation("ManyToMany")) {
                validateManyToManyField(entityDefinition, field, schemaTables, violations);
            }
        }
    }

    private void validateManyToOneField(
            JavaEntityDefinition entityDefinition,
            JavaFieldDefinition field,
            TableDefinition tableDefinition,
            Map<String, TableDefinition> schemaTables,
            Map<String, JavaEntityDefinition> entityBySimpleName,
            List<String> violations
    ) {
        List<String> joinColumnNames = resolveJoinColumnNames(field);

        if (joinColumnNames.isEmpty()) {
            violations.add("[" + entityDefinition.displayName() + "] @ManyToOne field '" + field.name()
                    + "' is missing @JoinColumn or @JoinColumns");
            return;
        }

        for (String joinColumnName : joinColumnNames) {
            ColumnDefinition localColumnDefinition = tableDefinition.columns().get(normalizeName(joinColumnName));

            if (localColumnDefinition == null) {
                violations.add("[" + entityDefinition.displayName() + "] @ManyToOne field '" + field.name()
                        + "' has join column '" + joinColumnName
                        + "' that does not exist in table '" + tableDefinition.fullName() + "'");
                continue;
            }

            ForeignKeyDefinition foreignKeyDefinition = findForeignKeyBySourceColumn(tableDefinition, joinColumnName);

            if (foreignKeyDefinition == null) {
                violations.add("[" + entityDefinition.displayName() + "] @ManyToOne field '" + field.name()
                        + "' uses join column '" + joinColumnName
                        + "' but no matching foreign key was found in table '" + tableDefinition.fullName() + "'");
                continue;
            }

            validateRelationTargetEntityWhenResolvable(
                    entityDefinition,
                    field,
                    foreignKeyDefinition,
                    schemaTables,
                    entityBySimpleName,
                    violations
            );
        }
    }

    private void validateOneToOneField(
            JavaEntityDefinition entityDefinition,
            JavaFieldDefinition field,
            TableDefinition tableDefinition,
            Map<String, TableDefinition> schemaTables,
            Map<String, JavaEntityDefinition> entityBySimpleName,
            List<String> violations
    ) {
        boolean inverseSide = field.annotationAttribute("OneToOne", "mappedBy") != null
                && !field.annotationAttribute("OneToOne", "mappedBy").isBlank();

        if (inverseSide) {
            return;
        }

        List<String> joinColumnNames = resolveJoinColumnNames(field);

        if (joinColumnNames.isEmpty()) {
            violations.add("[" + entityDefinition.displayName() + "] Owning @OneToOne field '" + field.name()
                    + "' must declare @JoinColumn or @JoinColumns");
            return;
        }

        for (String joinColumnName : joinColumnNames) {
            ColumnDefinition localColumnDefinition = tableDefinition.columns().get(normalizeName(joinColumnName));

            if (localColumnDefinition == null) {
                violations.add("[" + entityDefinition.displayName() + "] @OneToOne field '" + field.name()
                        + "' has join column '" + joinColumnName
                        + "' that does not exist in table '" + tableDefinition.fullName() + "'");
                continue;
            }

            ForeignKeyDefinition foreignKeyDefinition = findForeignKeyBySourceColumn(tableDefinition, joinColumnName);

            if (foreignKeyDefinition == null) {
                violations.add("[" + entityDefinition.displayName() + "] @OneToOne field '" + field.name()
                        + "' uses join column '" + joinColumnName
                        + "' but no matching foreign key was found in table '" + tableDefinition.fullName() + "'");
                continue;
            }

            validateRelationTargetEntityWhenResolvable(
                    entityDefinition,
                    field,
                    foreignKeyDefinition,
                    schemaTables,
                    entityBySimpleName,
                    violations
            );
        }
    }

    private void validateOneToManyField(
            JavaEntityDefinition entityDefinition,
            JavaFieldDefinition field,
            Map<String, JavaEntityDefinition> entityBySimpleName,
            List<String> violations
    ) {
        String mappedBy = field.annotationAttribute("OneToMany", "mappedBy");

        if (mappedBy == null || mappedBy.isBlank()) {
            violations.add("[" + entityDefinition.displayName() + "] @OneToMany field '" + field.name()
                    + "' must declare mappedBy");
            return;
        }

        String targetType = resolveCollectionGenericType(field.type());
        if (targetType == null || targetType.isBlank()) {
            violations.add("[" + entityDefinition.displayName() + "] Could not resolve target entity type for @OneToMany field '"
                    + field.name() + "'");
            return;
        }

        JavaEntityDefinition targetEntityDefinition = entityBySimpleName.get(simpleTypeName(targetType));
        if (targetEntityDefinition == null) {
            violations.add("[" + entityDefinition.displayName() + "] @OneToMany field '" + field.name()
                    + "' points to non-discovered entity '" + targetType + "'");
            return;
        }

        JavaFieldDefinition mappedField = targetEntityDefinition.fields().stream()
                .filter(targetField -> targetField.name().equals(mappedBy))
                .findFirst()
                .orElse(null);

        if (mappedField == null) {
            violations.add("[" + entityDefinition.displayName() + "] @OneToMany field '" + field.name()
                    + "' has invalid mappedBy='" + mappedBy
                    + "' on target entity '" + targetEntityDefinition.displayName() + "'");
            return;
        }

        boolean validOwningAnnotation = mappedField.hasAnnotation("ManyToOne") || mappedField.hasAnnotation("OneToOne");
        if (!validOwningAnnotation) {
            violations.add("[" + entityDefinition.displayName() + "] @OneToMany field '" + field.name()
                    + "' mappedBy='" + mappedBy
                    + "' points to field on target entity '" + targetEntityDefinition.displayName()
                    + "' that is not @ManyToOne/@OneToOne");
        }
    }

    private void validateManyToManyField(
            JavaEntityDefinition entityDefinition,
            JavaFieldDefinition field,
            Map<String, TableDefinition> schemaTables,
            List<String> violations
    ) {
        String mappedBy = field.annotationAttribute("ManyToMany", "mappedBy");
        if (mappedBy != null && !mappedBy.isBlank()) {
            return;
        }

        String joinTableName = field.annotationAttribute("JoinTable", "name");
        String joinTableSchema = field.annotationAttribute("JoinTable", "schema");

        if (joinTableName == null || joinTableName.isBlank()) {
            violations.add("[" + entityDefinition.displayName() + "] Owning @ManyToMany field '" + field.name()
                    + "' must declare @JoinTable(name=...)");
            return;
        }

        String physicalJoinTableName = buildPhysicalTableName(joinTableSchema, joinTableName);
        TableDefinition joinTableDefinition = schemaTables.get(normalizeName(physicalJoinTableName));

        if (joinTableDefinition == null) {
            violations.add("[" + entityDefinition.displayName() + "] Join table not found in SQL schema for field '"
                    + field.name() + "': " + physicalJoinTableName);
            return;
        }

        for (String joinColumnName : extractJoinTableArrayNames(field, "joinColumns")) {
            if (!joinTableDefinition.columns().containsKey(normalizeName(joinColumnName))) {
                violations.add("[" + entityDefinition.displayName() + "] JoinTable column '" + joinColumnName
                        + "' not found for field '" + field.name()
                        + "' in join table '" + physicalJoinTableName + "'");
            }
        }

        for (String inverseJoinColumnName : extractJoinTableArrayNames(field, "inverseJoinColumns")) {
            if (!joinTableDefinition.columns().containsKey(normalizeName(inverseJoinColumnName))) {
                violations.add("[" + entityDefinition.displayName() + "] JoinTable inverse column '" + inverseJoinColumnName
                        + "' not found for field '" + field.name()
                        + "' in join table '" + physicalJoinTableName + "'");
            }
        }

        List<String> allJoinColumns = new ArrayList<>();
        allJoinColumns.addAll(extractJoinTableArrayNames(field, "joinColumns"));
        allJoinColumns.addAll(extractJoinTableArrayNames(field, "inverseJoinColumns"));

        for (String joinColumnName : allJoinColumns) {
            ForeignKeyDefinition foreignKeyDefinition = findForeignKeyBySourceColumn(joinTableDefinition, joinColumnName);
            if (foreignKeyDefinition == null) {
                violations.add("[" + entityDefinition.displayName() + "] Join table '" + physicalJoinTableName
                        + "' column '" + joinColumnName + "' used by field '" + field.name()
                        + "' is not backed by a foreign key");
            }
        }
    }


    private void validateMissingTableColumns(
            JavaEntityDefinition entityDefinition,
            TableDefinition tableDefinition,
            Map<String, JavaEntityDefinition> entityBySimpleName,
            List<String> violations
    ) {
        Set<String> mappedColumns = new TreeSet<>();

        for (JavaFieldDefinition field : entityDefinition.fields()) {
            if (field.hasAnnotation("Transient")) {
                continue;
            }

            if (field.hasAnnotation("EmbeddedId")) {
                JavaEntityDefinition embeddedIdDefinition = resolveEmbeddedIdDefinition(field, entityBySimpleName);

                if (embeddedIdDefinition != null) {
                    for (JavaFieldDefinition embeddedField : embeddedIdDefinition.fields()) {
                        if (embeddedField.hasAnnotation("Transient")) {
                            continue;
                        }

                        mappedColumns.add(normalizeName(resolveColumnName(embeddedField)));
                    }
                }

                continue;
            }

            if (field.hasAnnotation("Id")) {
                mappedColumns.add(normalizeName(resolveColumnName(field)));
                continue;
            }

            if (field.hasAnnotation("ManyToOne") || field.hasAnnotation("OneToOne")) {
                mappedColumns.addAll(
                        resolveJoinColumnNames(field).stream()
                                .map(this::normalizeName)
                                .collect(Collectors.toSet())
                );
                continue;
            }

            if (field.hasAnnotation("OneToMany") || field.hasAnnotation("ManyToMany")) {
                continue;
            }

            if (entityBySimpleName.containsKey(simpleTypeName(field.type()))) {
                continue;
            }

            mappedColumns.add(normalizeName(resolveColumnName(field)));
        }

        List<String> unmappedNonAuditColumns = tableDefinition.columns().keySet().stream()
                .filter(columnName -> !mappedColumns.contains(columnName))
                .filter(columnName -> !looksLikeAuditOnlyColumn(columnName))
                .sorted()
                .toList();

        if (!unmappedNonAuditColumns.isEmpty()) {
            violations.add("[" + entityDefinition.displayName() + "] Unmapped DB columns in table '"
                    + tableDefinition.fullName() + "': " + unmappedNonAuditColumns);
        }
    }

    /**
     * Parses CREATE TABLE statements from a SQL script.
     *
     * @param sql SQL script content
     * @return parsed table definitions keyed by normalized physical table name
     */
    private Map<String, TableDefinition> parseSchema(String sql) {
        Map<String, TableDefinition> tables = new LinkedHashMap<>();

        Matcher matcher = Pattern.compile(
                "(?is)\\bCREATE\\s+TABLE\\s+([\\w.\"]+)\\s*\\("
        ).matcher(sql);

        while (matcher.find()) {
            String rawTableName = sanitizeIdentifier(matcher.group(1));
            int bodyStartIndex = matcher.end();
            int bodyEndIndex = findMatchingClosingParenthesis(sql, bodyStartIndex - 1);

            if (bodyEndIndex < 0) {
                continue;
            }

            String tableBody = sql.substring(bodyStartIndex, bodyEndIndex);
            TableDefinition tableDefinition = parseTableBlock(rawTableName, tableBody);

            tables.put(normalizeName(rawTableName), tableDefinition);
        }

        return tables;
    }

    /**
     * Finds the matching closing parenthesis for an opening parenthesis.
     *
     * @param sql SQL content
     * @param openingParenthesisIndex opening parenthesis index
     * @return matching closing parenthesis index, or -1 when not found
     */
    private int findMatchingClosingParenthesis(String sql, int openingParenthesisIndex) {
        int parenthesesDepth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int index = openingParenthesisIndex; index < sql.length(); index++) {
            char currentChar = sql.charAt(index);

            if (currentChar == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (currentChar == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (currentChar == '(') {
                    parenthesesDepth++;
                } else if (currentChar == ')') {
                    parenthesesDepth--;

                    if (parenthesesDepth == 0) {
                        return index;
                    }
                }
            }
        }

        return -1;
    }

    /**
     * Parses one CREATE TABLE block.
     *
     * @param tableName physical table name
     * @param tableBody table body
     * @return parsed table definition
     */
    private TableDefinition parseTableBlock(String tableName, String tableBody) {
        Map<String, ColumnDefinition> columns = new LinkedHashMap<>();
        List<ForeignKeyDefinition> foreignKeys = new ArrayList<>();
        Set<String> uniqueColumns = new LinkedHashSet<>();
        List<String> segments = splitTopLevelSqlSegments(tableBody);

        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isBlank()) {
                continue;
            }

            if (isForeignKeyConstraint(trimmed)) {
                ForeignKeyDefinition foreignKeyDefinition = parseForeignKeyConstraint(tableName, trimmed);
                if (foreignKeyDefinition != null) {
                    foreignKeys.add(foreignKeyDefinition);
                }
                continue;
            }

            if (isUniqueConstraint(trimmed)) {
                uniqueColumns.addAll(parseUniqueConstraintColumns(trimmed));
                continue;
            }

            if (isTableConstraint(trimmed)) {
                continue;
            }

            ColumnDefinition columnDefinition = parseColumnDefinition(trimmed);
            if (columnDefinition != null) {
                columns.put(normalizeName(columnDefinition.name()), columnDefinition);

                if (columnDefinition.unique()) {
                    uniqueColumns.add(normalizeName(columnDefinition.name()));
                }

                ForeignKeyDefinition inlineForeignKeyDefinition =
                        parseInlineForeignKeyConstraint(tableName, columnDefinition.name(), trimmed);

                if (inlineForeignKeyDefinition != null) {
                    foreignKeys.add(inlineForeignKeyDefinition);
                }
            }
        }

        return new TableDefinition(tableName, columns, foreignKeys, uniqueColumns);
    }

    /**
     * Checks whether a SQL segment is a UNIQUE table constraint.
     *
     * @param sqlSegment SQL segment
     * @return true when the segment defines a UNIQUE constraint
     */
    private boolean isUniqueConstraint(String sqlSegment) {
        String normalized = sqlSegment.trim().toUpperCase(Locale.ROOT);

        return normalized.startsWith("UNIQUE")
                || normalized.startsWith("CONSTRAINT ") && normalized.contains(" UNIQUE ");
    }

    /**
     * Parses single-column UNIQUE constraint columns.
     *
     * @param sqlSegment SQL constraint segment
     * @return normalized unique column names
     */
    private Set<String> parseUniqueConstraintColumns(String sqlSegment) {
        Matcher matcher = Pattern.compile("(?is)\\bUNIQUE\\s*\\(([^)]+)\\)").matcher(sqlSegment);

        if (!matcher.find()) {
            return Set.of();
        }

        List<String> columns = splitIdentifierList(matcher.group(1));

        if (columns.size() != 1) {
            return Set.of();
        }

        return Set.of(normalizeName(columns.getFirst()));
    }

    /**
     * Validates that unique foreign keys generated as owning @OneToOne also have
     * the expected inverse @OneToOne(mappedBy=...) field on the referenced entity.
     *
     * @param tableDefinition current SQL table
     * @param schemaTables parsed SQL schema tables
     * @param entityBySimpleName generated entities by simple name
     * @param violations collected violations
     */
    private void validateMissingInverseOneToOneRelations(
            TableDefinition tableDefinition,
            Map<String, TableDefinition> schemaTables,
            Map<String, JavaEntityDefinition> entityBySimpleName,
            List<String> violations
    ) {
        JavaEntityDefinition owningEntity = findEntityForTable(tableDefinition, entityBySimpleName);
        if (owningEntity == null) {
            return;
        }

        for (ForeignKeyDefinition foreignKeyDefinition : tableDefinition.foreignKeys()) {
            if (!isUniqueForeignKeyColumn(tableDefinition, foreignKeyDefinition.sourceColumn())) {
                continue;
            }

            JavaFieldDefinition owningField = findOwningOneToOneFieldForForeignKey(
                    owningEntity,
                    foreignKeyDefinition.sourceColumn()
            );

            if (owningField == null) {
                continue;
            }

            TableDefinition targetTableDefinition = resolveTableDefinitionByPhysicalOrUnqualifiedName(
                    schemaTables,
                    foreignKeyDefinition.targetTable()
            );

            if (targetTableDefinition == null) {
                continue;
            }

            JavaEntityDefinition targetEntity = findEntityForTable(targetTableDefinition, entityBySimpleName);
            if (targetEntity == null) {
                continue;
            }

            String expectedInverseFieldName = decapitalize(owningEntity.simpleName());

            if (!hasInverseOneToOneField(targetEntity, owningEntity.simpleName(), expectedInverseFieldName, owningField.name())) {
                violations.add("[" + targetEntity.displayName() + "] Missing inverse @OneToOne field '"
                        + expectedInverseFieldName + "' mappedBy='" + owningField.name()
                        + "' for unique foreign key '" + tableDefinition.fullName() + "."
                        + foreignKeyDefinition.sourceColumn() + "'");
            }
        }
    }

    /**
     * Checks whether the foreign key source column is unique.
     *
     * @param tableDefinition source table
     * @param sourceColumn source FK column
     * @return true when the FK column is unique
     */
    private boolean isUniqueForeignKeyColumn(TableDefinition tableDefinition, String sourceColumn) {
        String normalizedSourceColumn = normalizeName(sourceColumn);
        ColumnDefinition columnDefinition = tableDefinition.columns().get(normalizedSourceColumn);

        return tableDefinition.uniqueColumns().contains(normalizedSourceColumn)
                || columnDefinition != null && columnDefinition.unique();
    }

    /**
     * Finds the generated entity for a SQL table.
     *
     * @param tableDefinition SQL table definition
     * @param entityBySimpleName generated entities by simple name
     * @return matching Java entity or null
     */
    private JavaEntityDefinition findEntityForTable(
            TableDefinition tableDefinition,
            Map<String, JavaEntityDefinition> entityBySimpleName
    ) {
        String expectedEntityName = toPascalCase(extractUnqualifiedTableName(tableDefinition.fullName()));
        return entityBySimpleName.get(expectedEntityName);
    }

    /**
     * Finds the owning @OneToOne field that uses the given FK column.
     *
     * @param entityDefinition generated entity
     * @param sourceColumn FK source column
     * @return owning @OneToOne field or null
     */
    private JavaFieldDefinition findOwningOneToOneFieldForForeignKey(
            JavaEntityDefinition entityDefinition,
            String sourceColumn
    ) {
        String normalizedSourceColumn = normalizeName(sourceColumn);

        return entityDefinition.fields().stream()
                .filter(field -> field.hasAnnotation("OneToOne"))
                .filter(field -> field.annotationAttribute("OneToOne", "mappedBy") == null)
                .filter(field -> resolveJoinColumnNames(field).stream()
                        .map(this::normalizeName)
                        .anyMatch(normalizedSourceColumn::equals))
                .findFirst()
                .orElse(null);
    }

    /**
     * Checks whether the target entity contains the expected inverse @OneToOne field.
     *
     * @param targetEntity target entity
     * @param expectedFieldType expected inverse field type
     * @param expectedFieldName expected inverse field name
     * @param expectedMappedBy expected mappedBy value
     * @return true when the inverse field exists
     */
    private boolean hasInverseOneToOneField(
            JavaEntityDefinition targetEntity,
            String expectedFieldType,
            String expectedFieldName,
            String expectedMappedBy
    ) {
        return targetEntity.fields().stream()
                .filter(field -> field.hasAnnotation("OneToOne"))
                .filter(field -> expectedFieldName.equals(field.name()))
                .filter(field -> expectedFieldType.equals(simpleTypeName(field.type())))
                .anyMatch(field -> expectedMappedBy.equals(field.annotationAttribute("OneToOne", "mappedBy")));
    }

    /**
     * Decapitalizes a Java class name into a field name.
     *
     * @param value class name
     * @return decapitalized field name
     */
    private String decapitalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }

        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    /**
     * Parses an inline column-level foreign key declaration.
     *
     * @param sourceTableName source table name
     * @param sourceColumnName source column name
     * @param sqlSegment full column SQL segment
     * @return parsed foreign key definition or null
     */
    private ForeignKeyDefinition parseInlineForeignKeyConstraint(
            String sourceTableName,
            String sourceColumnName,
            String sqlSegment
    ) {
        Pattern pattern = Pattern.compile(
                "(?is)\\bREFERENCES\\s+([\\w.\"]+)\\s*\\(([^)]+)\\)"
        );

        Matcher matcher = pattern.matcher(sqlSegment);
        if (!matcher.find()) {
            return null;
        }

        String targetTableName = sanitizeIdentifier(matcher.group(1));
        String targetColumnName = sanitizeIdentifier(matcher.group(2));

        return new ForeignKeyDefinition(
                sanitizeIdentifier(sourceTableName),
                sanitizeIdentifier(sourceColumnName),
                targetTableName,
                targetColumnName
        );
    }

    /**
     * Validates that every local foreign key is represented by a relation field.
     *
     * @param entityDefinition parsed entity definition
     * @param tableDefinition parsed SQL table definition
     * @param schemaTables parsed schema tables
     * @param violations collected violations
     */
    private void validateForeignKeyCoverage(
            JavaEntityDefinition entityDefinition,
            TableDefinition tableDefinition,
            Map<String, TableDefinition> schemaTables,
            List<String> violations
    ) {
        Set<String> relationJoinColumns = entityDefinition.fields().stream()
                .filter(field -> field.hasAnnotation("ManyToOne") || field.hasAnnotation("OneToOne"))
                .flatMap(field -> resolveJoinColumnNames(field).stream())
                .map(this::normalizeName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (ForeignKeyDefinition foreignKeyDefinition : tableDefinition.foreignKeys()) {
            String normalizedSourceColumn = normalizeName(foreignKeyDefinition.sourceColumn());

            if (relationJoinColumns.contains(normalizedSourceColumn)) {
                continue;
            }

            TableDefinition targetTableDefinition = resolveTableDefinitionByPhysicalOrUnqualifiedName(
                    schemaTables,
                    foreignKeyDefinition.targetTable()
            );

            if (targetTableDefinition == null) {
                continue;
            }

            boolean hasMatchingScalarField = entityDefinition.fields().stream()
                    .filter(field -> !field.hasAnnotation("Transient"))
                    .filter(field -> !field.hasAnnotation("ManyToOne"))
                    .filter(field -> !field.hasAnnotation("OneToOne"))
                    .filter(field -> !field.hasAnnotation("OneToMany"))
                    .filter(field -> !field.hasAnnotation("ManyToMany"))
                    .anyMatch(field -> normalizeName(resolveColumnName(field)).equals(normalizedSourceColumn));

            if (hasMatchingScalarField) {
                violations.add("[" + entityDefinition.displayName() + "] Local foreign key column '"
                        + foreignKeyDefinition.sourceColumn() + "' in table '" + tableDefinition.fullName()
                        + "' is mapped as scalar field instead of relation");
            } else {
                violations.add("[" + entityDefinition.displayName() + "] Missing relation field for local foreign key column '"
                        + foreignKeyDefinition.sourceColumn() + "' in table '" + tableDefinition.fullName() + "'");
            }
        }
    }

    /**
     * Validates that an entity does not declare duplicate Java field names.
     *
     * @param entityDefinition parsed entity definition
     * @param violations collected violations
     */
    private void validateDuplicateFieldNames(
            JavaEntityDefinition entityDefinition,
            List<String> violations
    ) {
        Map<String, Long> fieldNameCounts = entityDefinition.fields().stream()
                .collect(Collectors.groupingBy(
                        JavaFieldDefinition::name,
                        LinkedHashMap::new,
                        Collectors.counting()
                ));

        fieldNameCounts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .forEach(entry -> violations.add(
                        "[" + entityDefinition.displayName() + "] Duplicate Java field name detected: '"
                                + entry.getKey() + "'"
                ));
    }

    /**
     * Parses a SQL column definition.
     *
     * @param sqlSegment raw SQL segment
     * @return parsed column definition or null
     */
    private ColumnDefinition parseColumnDefinition(String sqlSegment) {
        Matcher matcher = Pattern.compile("^\\s*(\"[^\"]+\"|[a-zA-Z_][\\w$]*)\\s+(.+)$", Pattern.DOTALL)
                .matcher(sqlSegment);

        if (!matcher.find()) {
            return null;
        }

        String rawColumnName = matcher.group(1);
        String definitionWithoutName = matcher.group(2).trim();
        String sqlType = extractSqlType(definitionWithoutName);

        return new ColumnDefinition(
                sanitizeIdentifier(rawColumnName),
                normalizeSqlType(sqlType),
                extractLength(sqlType),
                extractPrecision(sqlType),
                extractScale(sqlType),
                !containsNotNull(definitionWithoutName),
                containsPrimaryKey(definitionWithoutName),
                containsUnique(definitionWithoutName),
                extractDefaultValue(definitionWithoutName),
                extractCheckConstraint(definitionWithoutName)
        );
    }

    private String extractSqlType(String definitionWithoutName) {
        String value = definitionWithoutName.trim();
        String upper = value.toUpperCase(Locale.ROOT);
        int cutIndex = value.length();

        for (String marker : List.of(" NOT NULL", " NULL", " DEFAULT ", " CONSTRAINT ",
                " PRIMARY KEY", " UNIQUE", " CHECK ", " REFERENCES ")) {
            int currentIndex = upper.indexOf(marker);
            if (currentIndex >= 0 && currentIndex < cutIndex) {
                cutIndex = currentIndex;
            }
        }

        return value.substring(0, cutIndex).trim();
    }

    private String normalizeSqlType(String sqlType) {
        if (sqlType == null) {
            return null;
        }

        return sqlType.trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private Integer extractLength(String sqlType) {
        if (sqlType == null) {
            return null;
        }

        Matcher matcher = Pattern.compile("(?i)^(?:character varying|varchar|char|character)\\((\\d+)\\)")
                .matcher(sqlType);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private Integer extractPrecision(String sqlType) {
        if (sqlType == null) {
            return null;
        }

        Matcher matcher = Pattern.compile("(?i)^(?:numeric|decimal)\\((\\d+)(?:\\s*,\\s*(\\d+))?\\)")
                .matcher(sqlType);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private Integer extractScale(String sqlType) {
        if (sqlType == null) {
            return null;
        }

        Matcher matcher = Pattern.compile("(?i)^(?:numeric|decimal)\\((\\d+)\\s*,\\s*(\\d+)\\)")
                .matcher(sqlType);
        return matcher.find() ? Integer.parseInt(matcher.group(2)) : null;
    }

    private boolean containsNotNull(String value) {
        return value.toUpperCase(Locale.ROOT).contains("NOT NULL");
    }

    private boolean containsPrimaryKey(String value) {
        return value.toUpperCase(Locale.ROOT).contains("PRIMARY KEY");
    }

    private boolean containsUnique(String value) {
        return value.toUpperCase(Locale.ROOT).contains("UNIQUE");
    }

    private String extractDefaultValue(String value) {
        Matcher matcher = Pattern.compile("(?i)\\bDEFAULT\\s+(.+?)(?=\\s+NOT\\s+NULL|\\s+NULL|\\s+CONSTRAINT|\\s+PRIMARY\\s+KEY|\\s+UNIQUE|\\s+CHECK|\\s+REFERENCES|$)")
                .matcher(value);
        if (!matcher.find()) {
            return null;
        }

        return matcher.group(1).trim();
    }

    private String extractCheckConstraint(String value) {
        Matcher matcher = Pattern.compile("(?i)\\bCHECK\\s*\\((.*)\\)").matcher(value);
        if (!matcher.find()) {
            return null;
        }

        return "(" + matcher.group(1).trim() + ")";
    }

    private boolean isForeignKeyConstraint(String sqlSegment) {
        String normalized = sqlSegment.trim().toUpperCase(Locale.ROOT);

        return normalized.startsWith("FOREIGN KEY")
                || normalized.startsWith("CONSTRAINT ") && normalized.contains(" FOREIGN KEY ");
    }

    private ForeignKeyDefinition parseForeignKeyConstraint(String sourceTableName, String sqlSegment) {
        Pattern pattern = Pattern.compile(
                "(?is)(?:CONSTRAINT\\s+\\S+\\s+)?FOREIGN\\s+KEY\\s*\\(([^)]+)\\)\\s+REFERENCES\\s+([\\w.\"]+)\\s*\\(([^)]+)\\)"
        );

        Matcher matcher = pattern.matcher(sqlSegment);
        if (!matcher.find()) {
            return null;
        }

        String sourceColumnsGroup = matcher.group(1);
        String targetTableGroup = sanitizeIdentifier(matcher.group(2));
        String targetColumnsGroup = matcher.group(3);

        List<String> sourceColumns = splitIdentifierList(sourceColumnsGroup);
        List<String> targetColumns = splitIdentifierList(targetColumnsGroup);

        if (sourceColumns.size() != 1 || targetColumns.size() != 1) {
            return null;
        }

        return new ForeignKeyDefinition(
                sanitizeIdentifier(sourceTableName),
                sanitizeIdentifier(sourceColumns.getFirst()),
                sanitizeIdentifier(targetTableGroup),
                sanitizeIdentifier(targetColumns.getFirst())
        );
    }

    private List<String> splitIdentifierList(String value) {
        return splitTopLevelByComma(value).stream()
                .map(String::trim)
                .map(this::sanitizeIdentifier)
                .filter(identifier -> identifier != null && !identifier.isBlank())
                .toList();
    }

    /**
     * Checks whether a SQL segment is a table-level constraint.
     *
     * @param sqlSegment SQL segment
     * @return true when the segment is a table-level constraint
     */
    private boolean isTableConstraint(String sqlSegment) {
        String normalized = sqlSegment.trim().toUpperCase(Locale.ROOT);

        return normalized.startsWith("CONSTRAINT ")
                || normalized.startsWith("PRIMARY KEY")
                || normalized.startsWith("FOREIGN KEY")
                || normalized.startsWith("UNIQUE")
                || normalized.startsWith("CHECK ")
                || normalized.startsWith("CHECK(")
                || normalized.startsWith("EXCLUDE ");
    }

    private List<String> splitTopLevelSqlSegments(String sqlBlock) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenthesesDepth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int index = 0; index < sqlBlock.length(); index++) {
            char currentChar = sqlBlock.charAt(index);

            if (currentChar == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (currentChar == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (currentChar == '(') {
                    parenthesesDepth++;
                } else if (currentChar == ')') {
                    parenthesesDepth--;
                } else if (currentChar == ',' && parenthesesDepth == 0) {
                    segments.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }

            current.append(currentChar);
        }

        if (!current.isEmpty()) {
            segments.add(current.toString());
        }

        return segments;
    }

    /**
     * Parses Java field definitions from a class body.
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
                }

                continue;
            }

            if (line.startsWith("@")) {
                currentAnnotation = new StringBuilder(line);
                annotationParenthesesDepth = countOccurrences(line, '(') - countOccurrences(line, ')');

                if (annotationParenthesesDepth <= 0) {
                    pendingAnnotations.add(currentAnnotation.toString().trim());
                    currentAnnotation = null;
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

                pendingAnnotations.clear();
                continue;
            }

            if (line.contains("(")
                    || line.startsWith("public ")
                    || line.startsWith("protected ")
                    || line.startsWith("private ")) {
                pendingAnnotations.clear();
            }
        }

        return fieldDefinitions;
    }

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
     * Parses raw annotation lines into structured {@link AnnotationDefinition} objects.
     *
     * @param annotationLines list of annotation lines (e.g. ""@Column(name = \"id\")")
     * @return list of parsed annotation definitions with extracted name and attributes
     */
    private List<AnnotationDefinition> parseAnnotations(List<String> annotationLines) {
        List<AnnotationDefinition> annotations = new ArrayList<>();

        for (String annotationLine : annotationLines) {
            String trimmed = annotationLine.trim();

            Matcher matcher = Pattern.compile("^@([A-Za-z0-9_]+)(\\((.*)\\))?$").matcher(trimmed);
            if (!matcher.find()) {
                continue;
            }

            String annotationName = matcher.group(1);
            String rawArguments = matcher.group(3);

            Map<String, String> attributes = parseAnnotationAttributes(rawArguments);

            annotations.add(new AnnotationDefinition(annotationName, attributes, rawArguments == null ? "" : rawArguments));
        }

        return annotations;
    }

    /**
     * Parses raw annotation argument string into a map of attribute name-value pairs.
     *
     * @param rawArguments raw annotation arguments (e.g. ""name = \"id\", nullable = false")
     * @return ordered map of attribute names to their resolved values
     */
    private Map<String, String> parseAnnotationAttributes(String rawArguments) {
        Map<String, String> attributes = new LinkedHashMap<>();

        if (rawArguments == null || rawArguments.isBlank()) {
            return attributes;
        }

        List<String> segments = splitTopLevelByComma(rawArguments);

        for (String segment : segments) {
            String trimmed = segment.trim();

            int equalsIndex = findTopLevelEquals(trimmed);
            if (equalsIndex < 0) {
                attributes.put("value", unquote(trimmed));
                continue;
            }

            String name = trimmed.substring(0, equalsIndex).trim();
            String value = trimmed.substring(equalsIndex + 1).trim();
            attributes.put(name, unquote(value));
        }

        return attributes;
    }

    private List<String> splitTopLevelByComma(String value) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenthesesDepth = 0;
        int bracesDepth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int index = 0; index < value.length(); index++) {
            char currentChar = value.charAt(index);

            if (currentChar == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (currentChar == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (currentChar == '(') {
                    parenthesesDepth++;
                } else if (currentChar == ')') {
                    parenthesesDepth--;
                } else if (currentChar == '{') {
                    bracesDepth++;
                } else if (currentChar == '}') {
                    bracesDepth--;
                } else if (currentChar == ',' && parenthesesDepth == 0 && bracesDepth == 0) {
                    segments.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }

            current.append(currentChar);
        }

        if (!current.isEmpty()) {
            segments.add(current.toString());
        }

        return segments;
    }

    private int findTopLevelEquals(String value) {
        int parenthesesDepth = 0;
        int bracesDepth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int index = 0; index < value.length(); index++) {
            char currentChar = value.charAt(index);

            if (currentChar == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (currentChar == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (!inSingleQuote && !inDoubleQuote) {
                if (currentChar == '(') {
                    parenthesesDepth++;
                } else if (currentChar == ')') {
                    parenthesesDepth--;
                } else if (currentChar == '{') {
                    bracesDepth++;
                } else if (currentChar == '}') {
                    bracesDepth--;
                } else if (currentChar == '=' && parenthesesDepth == 0 && bracesDepth == 0) {
                    return index;
                }
            }
        }

        return -1;
    }

    private String extractPackageName(String content) {
        Matcher matcher = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;").matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractClassName(String content) {
        Matcher matcher = Pattern.compile("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)\\b").matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractTableName(String content) {
        Matcher matcher = Pattern.compile("@Table\\s*\\((.*?)\\)", Pattern.DOTALL).matcher(content);
        if (!matcher.find()) {
            return null;
        }

        String arguments = matcher.group(1);
        Matcher nameMatcher = Pattern.compile("\\bname\\s*=\\s*\"([^\"]+)\"").matcher(arguments);
        return nameMatcher.find() ? nameMatcher.group(1) : null;
    }

    private String extractTableSchema(String content) {
        Matcher matcher = Pattern.compile("@Table\\s*\\((.*?)\\)", Pattern.DOTALL).matcher(content);
        if (!matcher.find()) {
            return null;
        }

        String arguments = matcher.group(1);
        Matcher schemaMatcher = Pattern.compile("\\bschema\\s*=\\s*\"([^\"]+)\"").matcher(arguments);
        return schemaMatcher.find() ? schemaMatcher.group(1) : null;
    }

    private boolean looksLikeFieldDeclaration(String line) {
        return line.endsWith(";")
                && (line.startsWith("private ") || line.startsWith("protected ") || line.startsWith("public "))
                && !line.contains("(");
    }

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

        return parts[index];
    }

    private String extractFieldName(String line) {
        String normalized = line.replace(";", "").trim();
        String[] parts = normalized.split("\\s+");

        if (parts.length < 2) {
            return null;
        }

        return parts[parts.length - 1];
    }

    private boolean isFieldModifier(String token) {
        return token.equals("private")
                || token.equals("protected")
                || token.equals("public")
                || token.equals("final")
                || token.equals("static")
                || token.equals("transient")
                || token.equals("volatile");
    }

    private String resolveColumnName(JavaFieldDefinition field) {
        String declaredColumnName = field.annotationAttribute("Column", "name");

        if (declaredColumnName != null && !declaredColumnName.isBlank()) {
            return sanitizeIdentifier(declaredColumnName.replace("\"", "").trim());
        }

        return toSnakeCase(field.name());
    }

    private List<String> resolveJoinColumnNames(JavaFieldDefinition field) {
        List<String> names = new ArrayList<>();

        String directJoinColumnName = field.annotationAttribute("JoinColumn", "name");
        if (directJoinColumnName != null && !directJoinColumnName.isBlank()) {
            names.add(sanitizeIdentifier(directJoinColumnName));
        }

        String rawJoinColumns = field.annotationRawArguments("JoinColumns");
        if (rawJoinColumns != null && !rawJoinColumns.isBlank()) {
            Matcher matcher = Pattern.compile("name\\s*=\\s*\"([^\"]+)\"").matcher(rawJoinColumns);
            while (matcher.find()) {
                names.add(sanitizeIdentifier(matcher.group(1)));
            }
        }

        return names.stream().distinct().toList();
    }

    private List<String> extractJoinTableArrayNames(JavaFieldDefinition field, String attributeName) {
        String rawJoinTableArguments = field.annotationRawArguments("JoinTable");
        if (rawJoinTableArguments == null || rawJoinTableArguments.isBlank()) {
            return List.of();
        }

        String singleJoinColumnName = extractSingleJoinTableColumnName(rawJoinTableArguments, attributeName);
        if (singleJoinColumnName != null) {
            return List.of(singleJoinColumnName);
        }

        Matcher sectionMatcher = Pattern.compile(attributeName + "\\s*=\\s*\\{(.*?)}", Pattern.DOTALL)
                .matcher(rawJoinTableArguments);
        if (!sectionMatcher.find()) {
            return List.of();
        }

        String section = sectionMatcher.group(1);
        Matcher nameMatcher = Pattern.compile("name\\s*=\\s*\"([^\"]+)\"").matcher(section);

        List<String> names = new ArrayList<>();
        while (nameMatcher.find()) {
            names.add(sanitizeIdentifier(nameMatcher.group(1)));
        }

        return names;
    }

    private String extractSingleJoinTableColumnName(String rawJoinTableArguments, String attributeName) {
        Pattern pattern = Pattern.compile(attributeName + "\\s*=\\s*@JoinColumn\\s*\\((.*?)\\)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(rawJoinTableArguments);

        if (!matcher.find()) {
            return null;
        }

        String joinColumnArguments = matcher.group(1);
        Matcher nameMatcher = Pattern.compile("name\\s*=\\s*\"([^\"]+)\"").matcher(joinColumnArguments);

        if (!nameMatcher.find()) {
            return null;
        }

        return sanitizeIdentifier(nameMatcher.group(1));
    }

    private boolean shouldSkipSimpleColumnValidation(
            JavaFieldDefinition field,
            Map<String, JavaEntityDefinition> entityBySimpleName
    ) {
        return field.hasAnnotation("Transient")
                || field.hasAnnotation("OneToMany")
                || field.hasAnnotation("ManyToMany")
                || field.hasAnnotation("ManyToOne")
                || field.hasAnnotation("OneToOne")
                || entityBySimpleName.containsKey(simpleTypeName(field.type()));
    }

    private String resolveCollectionGenericType(String fieldType) {
        Matcher matcher = Pattern.compile("<\\s*([A-Za-z_][A-Za-z0-9_$.]*)\\s*>").matcher(fieldType);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String simpleTypeName(String fieldType) {
        String normalized = fieldType.trim();

        if (normalized.contains("<")) {
            normalized = normalized.substring(0, normalized.indexOf('<'));
        }

        if (normalized.contains(".")) {
            return normalized.substring(normalized.lastIndexOf('.') + 1);
        }

        return normalized;
    }

    private String buildPhysicalTableName(String schema, String tableName) {
        String sanitizedTableName = sanitizeIdentifier(tableName);

        if (schema == null || schema.isBlank()) {
            return sanitizedTableName;
        }

        return sanitizeIdentifier(schema) + "." + sanitizedTableName;
    }

    private String toSnakeCase(String value) {
        return value
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
    }

    private String stripComments(String content) {
        String withoutBlockComments = content.replaceAll("(?s)/\\*.*?\\*/", "");
        return withoutBlockComments.replaceAll("(?m)//.*$", "");
    }

    private String unquote(String value) {
        String trimmed = value.trim();

        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }

        return trimmed;
    }

    private String sanitizeIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }

        String trimmed = identifier.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }

        return trimmed;
    }

    private String normalizeName(String name) {
        return sanitizeIdentifier(name).toLowerCase(Locale.ROOT);
    }

    private boolean looksLikeAuditOnlyColumn(String columnName) {
        String normalized = normalizeName(columnName);
        return normalized.endsWith("_aud")
                || normalized.equals("rev")
                || normalized.equals("revtype");
    }

    private ForeignKeyDefinition findForeignKeyBySourceColumn(TableDefinition tableDefinition, String sourceColumn) {
        String normalizedSourceColumn = normalizeName(sourceColumn);

        return tableDefinition.foreignKeys().stream()
                .filter(foreignKey -> normalizeName(foreignKey.sourceColumn()).equals(normalizedSourceColumn))
                .findFirst()
                .orElse(null);
    }

    private void validateRelationTargetEntityWhenResolvable(
            JavaEntityDefinition entityDefinition,
            JavaFieldDefinition field,
            ForeignKeyDefinition foreignKeyDefinition,
            Map<String, TableDefinition> schemaTables,
            Map<String, JavaEntityDefinition> entityBySimpleName,
            List<String> violations
    ) {
        TableDefinition targetTableDefinition = resolveTableDefinitionByPhysicalOrUnqualifiedName(
                schemaTables,
                foreignKeyDefinition.targetTable()
        );

        if (targetTableDefinition == null) {
            return;
        }

        String expectedEntitySimpleName = toPascalCase(extractUnqualifiedTableName(targetTableDefinition.fullName()));
        String actualFieldTypeSimpleName = simpleTypeName(field.type());

        if (!actualFieldTypeSimpleName.equals(expectedEntitySimpleName)) {
            violations.add("[" + entityDefinition.displayName() + "] Relation field '" + field.name()
                    + "' points to entity type '" + actualFieldTypeSimpleName
                    + "' but foreign key targets table '" + targetTableDefinition.fullName()
                    + "' which resolves to expected entity '" + expectedEntitySimpleName + "'");
        }

        String referencedColumnName = field.annotationAttribute("JoinColumn", "referencedColumnName");
        if (referencedColumnName != null && !referencedColumnName.isBlank()) {
            if (!normalizeName(referencedColumnName).equals(normalizeName(foreignKeyDefinition.targetColumn()))) {
                violations.add("[" + entityDefinition.displayName() + "] Relation field '" + field.name()
                        + "' declares referencedColumnName='" + referencedColumnName
                        + "' but foreign key points to '" + foreignKeyDefinition.targetColumn() + "'");
            }
        }

        JavaEntityDefinition targetEntityDefinition = entityBySimpleName.get(expectedEntitySimpleName);
        if (targetEntityDefinition == null) {
            return;
        }

        boolean targetHasIdentifierCoverage = targetEntityDefinition.fields().stream()
                .anyMatch(targetField ->
                        targetField.hasAnnotation("Id")
                                || targetField.hasAnnotation("EmbeddedId")
                                || normalizeName(resolveColumnName(targetField))
                                .equals(normalizeName(foreignKeyDefinition.targetColumn()))
                );

        if (!targetHasIdentifierCoverage) {
            violations.add("[" + entityDefinition.displayName() + "] Relation field '" + field.name()
                    + "' points to entity '" + targetEntityDefinition.displayName()
                    + "' but target column '" + foreignKeyDefinition.targetColumn()
                    + "' could not be matched on the target entity");
        }
    }

    private TableDefinition resolveTableDefinitionByPhysicalOrUnqualifiedName(
            Map<String, TableDefinition> schemaTables,
            String physicalOrQualifiedTableName
    ) {
        if (physicalOrQualifiedTableName == null || physicalOrQualifiedTableName.isBlank()) {
            return null;
        }

        TableDefinition exactMatch = schemaTables.get(normalizeName(physicalOrQualifiedTableName));
        if (exactMatch != null) {
            return exactMatch;
        }

        String unqualifiedTableName = extractUnqualifiedTableName(physicalOrQualifiedTableName);

        List<TableDefinition> matches = schemaTables.values().stream()
                .filter(table -> normalizeName(extractUnqualifiedTableName(table.fullName()))
                        .equals(normalizeName(unqualifiedTableName)))
                .toList();

        if (matches.size() == 1) {
            return matches.getFirst();
        }

        return null;
    }

    private String toPascalCase(String value) {
        String normalized = sanitizeIdentifier(value);
        if (normalized == null || normalized.isBlank()) {
            return normalized;
        }

        String[] parts = normalized.split("[_\\s]+");
        StringBuilder builder = new StringBuilder();

        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }

            String lower = part.toLowerCase(Locale.ROOT);
            builder.append(Character.toUpperCase(lower.charAt(0)));

            if (lower.length() > 1) {
                builder.append(lower.substring(1));
            }
        }

        return builder.toString();
    }

    private void printEntitiesWithTodoComments(List<JavaEntityDefinition> entityDefinitions) {
        List<JavaEntityDefinition> todoEntities = entityDefinitions.stream()
                .filter(JavaEntityDefinition::hasTodoComment)
                .sorted(Comparator.comparing(JavaEntityDefinition::displayName))
                .toList();

        if (todoEntities.isEmpty()) {
            return;
        }

        System.out.println();
        System.out.println("==================================================");
        System.out.println("GENERATED ENTITY CLASSES WITH TODO COMMENTS");
        System.out.println("==================================================");

        for (int index = 0; index < todoEntities.size(); index++) {
            System.out.printf("%3d. %s%n", index + 1, todoEntities.get(index).displayName());
        }

        System.out.println("==================================================");
        System.out.println("Total classes with TODO: " + todoEntities.size());
        System.out.println("==================================================");
        System.out.println();
    }

    private JavaFieldDefinition findJavaFieldForColumn(
            JavaEntityDefinition javaEntityDefinition,
            ColumnDefinition columnDefinition
    ) {
        String normalizedColumnName = normalizeName(columnDefinition.name());

        return javaEntityDefinition.fields().stream()
                .filter(field -> !field.hasAnnotation("ManyToOne"))
                .filter(field -> !field.hasAnnotation("OneToOne"))
                .filter(field -> !field.hasAnnotation("OneToMany"))
                .filter(field -> !field.hasAnnotation("ManyToMany"))
                .filter(field -> !field.hasAnnotation("Transient"))
                .filter(field -> normalizeName(resolveColumnName(field)).equals(normalizedColumnName))
                .findFirst()
                .orElse(null);
    }

    private Column toModelColumn(ColumnDefinition columnDefinition) {
        Column column = new Column();
        column.setName(columnDefinition.name());
        column.setSqlType(columnDefinition.sqlType());
        column.setNullable(columnDefinition.nullable());
        column.setPrimaryKey(columnDefinition.primaryKey());
        column.setUnique(columnDefinition.unique());

        if (columnDefinition.length() != null) {
            column.setLength(columnDefinition.length());
        }

        if (columnDefinition.precision() != null) {
            column.setPrecision(columnDefinition.precision());
        }

        if (columnDefinition.scale() != null) {
            column.setScale(columnDefinition.scale());
        }

        return column;
    }

    private String simpleType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return typeName;
        }

        String trimmedTypeName = typeName.trim();

        if (trimmedTypeName.contains("<")) {
            trimmedTypeName = trimmedTypeName.substring(0, trimmedTypeName.indexOf('<'));
        }

        if (trimmedTypeName.contains(".")) {
            return trimmedTypeName.substring(trimmedTypeName.lastIndexOf('.') + 1);
        }

        return trimmedTypeName;
    }

    /**
     * Validates SQL column constraints against generated Java field annotations.
     *
     * @param entityDefinition parsed entity definition
     * @param tableDefinition parsed SQL table definition
     * @param violations collected violations
     */
    private void validateColumnConstraints(
            JavaEntityDefinition entityDefinition,
            TableDefinition tableDefinition,
            List<String> violations
    ) {
        for (ColumnDefinition columnDefinition : tableDefinition.columns().values()) {
            JavaFieldDefinition javaFieldDefinition = findJavaFieldForColumn(entityDefinition, columnDefinition);

            if (javaFieldDefinition == null || shouldSkipConstraintValidation(javaFieldDefinition)) {
                continue;
            }

            validateNullabilityConstraint(entityDefinition, columnDefinition, javaFieldDefinition, violations);
            validateUniqueConstraint(entityDefinition, columnDefinition, javaFieldDefinition, violations);

            if (isStringLikeColumn(columnDefinition)) {
                validateLengthConstraint(entityDefinition, columnDefinition, javaFieldDefinition, violations);
                validateSizeConstraint(entityDefinition, columnDefinition, javaFieldDefinition, violations);
                validateStringCheckConstraint(entityDefinition, columnDefinition, javaFieldDefinition, violations);
            }

            if (columnDefinition.precision() != null || columnDefinition.scale() != null) {
                validateNumericConstraint(entityDefinition, columnDefinition, javaFieldDefinition, violations);
                validateDigitsConstraint(entityDefinition, columnDefinition, javaFieldDefinition, violations);
            }

            validateDefaultMetadata(entityDefinition, columnDefinition, javaFieldDefinition, violations);
        }
    }

    /**
     * Checks whether constraint validation should skip this field.
     *
     * @param javaFieldDefinition parsed Java field
     * @return true when the field is not a direct scalar column
     */
    private boolean shouldSkipConstraintValidation(JavaFieldDefinition javaFieldDefinition) {
        return javaFieldDefinition.hasAnnotation("ManyToOne")
                || javaFieldDefinition.hasAnnotation("OneToOne")
                || javaFieldDefinition.hasAnnotation("OneToMany")
                || javaFieldDefinition.hasAnnotation("ManyToMany")
                || javaFieldDefinition.hasAnnotation("Transient");
    }

    /**
     * Validates @Size(max=...) against SQL varchar/char length.
     *
     * @param entityDefinition parsed entity definition
     * @param columnDefinition parsed SQL column definition
     * @param javaFieldDefinition parsed Java field
     * @param violations collected violations
     */
    private void validateSizeConstraint(
            JavaEntityDefinition entityDefinition,
            ColumnDefinition columnDefinition,
            JavaFieldDefinition javaFieldDefinition,
            List<String> violations
    ) {
        if (columnDefinition.length() == null) {
            return;
        }

        Integer sizeMax = tryParseInteger(javaFieldDefinition.annotationAttribute("Size", "max"));

        if (sizeMax != null && !columnDefinition.length().equals(sizeMax)) {
            violations.add("[%s] Column '%s' @Size(max) mismatch (SQL=%d, Java=%d)"
                    .formatted(
                            entityDefinition.displayName(),
                            columnDefinition.name(),
                            columnDefinition.length(),
                            sizeMax
                    ));
        }
    }

    /**
     * Validates @Digits(integer=..., fraction=...) against SQL numeric precision/scale.
     *
     * @param entityDefinition parsed entity definition
     * @param columnDefinition parsed SQL column definition
     * @param javaFieldDefinition parsed Java field
     * @param violations collected violations
     */
    private void validateDigitsConstraint(
            JavaEntityDefinition entityDefinition,
            ColumnDefinition columnDefinition,
            JavaFieldDefinition javaFieldDefinition,
            List<String> violations
    ) {
        Integer digitsInteger = tryParseInteger(javaFieldDefinition.annotationAttribute("Digits", "integer"));
        Integer digitsFraction = tryParseInteger(javaFieldDefinition.annotationAttribute("Digits", "fraction"));

        if (columnDefinition.precision() != null && columnDefinition.scale() != null && digitsInteger != null) {
            int expectedIntegerDigits = columnDefinition.precision() - columnDefinition.scale();

            if (expectedIntegerDigits != digitsInteger) {
                violations.add("[%s] Column '%s' @Digits(integer) mismatch (SQL=%d, Java=%d)"
                        .formatted(
                                entityDefinition.displayName(),
                                columnDefinition.name(),
                                expectedIntegerDigits,
                                digitsInteger
                        ));
            }
        }

        if (columnDefinition.scale() != null && digitsFraction != null
                && !columnDefinition.scale().equals(digitsFraction)) {
            violations.add("[%s] Column '%s' @Digits(fraction) mismatch (SQL=%d, Java=%d)"
                    .formatted(
                            entityDefinition.displayName(),
                            columnDefinition.name(),
                            columnDefinition.scale(),
                            digitsFraction
                    ));
        }
    }

    /**
     * Validates that important SQL default metadata is not silently lost for generated scalar fields.
     *
     * @param entityDefinition parsed entity definition
     * @param columnDefinition parsed SQL column definition
     * @param javaFieldDefinition parsed Java field
     * @param violations collected violations
     */
    private void validateDefaultMetadata(
            JavaEntityDefinition entityDefinition,
            ColumnDefinition columnDefinition,
            JavaFieldDefinition javaFieldDefinition,
            List<String> violations
    ) {
        if (isUuidGeneratedValue(columnDefinition, javaFieldDefinition)) {
            return;
        }

        if (columnDefinition.defaultValue() == null || columnDefinition.defaultValue().isBlank()) {
            return;
        }

        if (isIdentityDefault(columnDefinition.defaultValue())) {
            validateIdentityDefault(entityDefinition, columnDefinition, javaFieldDefinition, violations);
            return;
        }

        if (isBooleanLikeColumn(columnDefinition)
                || isTemporalDefault(columnDefinition.defaultValue())
                || isSimpleDatabaseDefault(columnDefinition.defaultValue())) {
            return;
        }

        String columnDefinitionAttr = javaFieldDefinition.annotationAttribute("Column", "columnDefinition");

        boolean hasColumnDefinition = columnDefinitionAttr != null && !columnDefinitionAttr.isBlank();

        boolean isCreationOrUpdateTimestamp = javaFieldDefinition.hasAnnotation("CreationTimestamp")
                || javaFieldDefinition.hasAnnotation("UpdateTimestamp");

        if (!hasColumnDefinition && !isCreationOrUpdateTimestamp) {
            violations.add("[%s] Column '%s' has SQL default '%s' but Java field does not preserve default metadata"
                    .formatted(
                            entityDefinition.displayName(),
                            columnDefinition.name(),
                            columnDefinition.defaultValue()
                    ));
        }
    }

    /**
     * Checks whether a SQL default is a simple literal safely handled by the database.
     *
     * @param defaultValue parsed SQL default value
     * @return true when the default is a simple literal
     */
    private boolean isSimpleDatabaseDefault(String defaultValue) {
        if (defaultValue == null || defaultValue.isBlank()) {
            return false;
        }

        String normalizedDefaultValue = defaultValue.trim().toLowerCase(Locale.ROOT);

        return normalizedDefaultValue.matches("^'.*'(::(character varying|charactervarying|varchar|text))?$")
                || normalizedDefaultValue.matches("-?\\d+(\\.\\d+)?");
    }

    /**
     * Checks whether the SQL default represents an identity column.
     *
     * @param defaultValue parsed SQL default value
     * @return true when the default is an identity definition
     */
    private boolean isIdentityDefault(String defaultValue) {
        if (defaultValue == null) {
            return false;
        }

        return defaultValue.trim().toUpperCase(Locale.ROOT).startsWith("AS IDENTITY");
    }

    /**
     * Validates that SQL identity columns are mapped as generated identifiers.
     *
     * @param entityDefinition parsed entity definition
     * @param columnDefinition parsed SQL column definition
     * @param javaFieldDefinition parsed Java field
     * @param violations collected violations
     */
    private void validateIdentityDefault(
            JavaEntityDefinition entityDefinition,
            ColumnDefinition columnDefinition,
            JavaFieldDefinition javaFieldDefinition,
            List<String> violations
    ) {
        boolean hasId = javaFieldDefinition.hasAnnotation("Id");
        boolean hasGeneratedValue = javaFieldDefinition.hasAnnotation("GeneratedValue");

        if (!hasId || !hasGeneratedValue) {
            violations.add("[%s] Identity column '%s' must be mapped with @Id and @GeneratedValue"
                    .formatted(entityDefinition.displayName(), columnDefinition.name()));
        }
    }

    /**
     * Checks whether a parsed SQL column is boolean-like.
     *
     * @param columnDefinition parsed SQL column definition
     * @return true when the SQL type is boolean-like
     */
    private boolean isBooleanLikeColumn(ColumnDefinition columnDefinition) {
        if (columnDefinition.sqlType() == null) {
            return false;
        }

        String sqlType = columnDefinition.sqlType().toLowerCase(Locale.ROOT);
        return sqlType.equals("boolean") || sqlType.equals("bool");
    }

    /**
     * Checks whether a SQL default is temporal and should be preserved.
     *
     * @param defaultValue parsed SQL default value
     * @return true when the default is temporal
     */
    private boolean isTemporalDefault(String defaultValue) {
        if (defaultValue == null) {
            return false;
        }

        String normalizedDefaultValue = defaultValue.trim().toLowerCase(Locale.ROOT);

        return normalizedDefaultValue.equals("now()")
                || normalizedDefaultValue.equals("current_timestamp")
                || normalizedDefaultValue.equals("current_date")
                || normalizedDefaultValue.equals("current_time")
                || normalizedDefaultValue.equals("localtimestamp")
                || normalizedDefaultValue.equals("localtime");
    }

    /**
     * Validates that SQL CHECK metadata on string-like columns is not silently lost.
     *
     * @param entityDefinition parsed entity definition
     * @param columnDefinition parsed SQL column definition
     * @param javaFieldDefinition parsed Java field
     * @param violations collected violations
     */
    private void validateStringCheckConstraint(
            JavaEntityDefinition entityDefinition,
            ColumnDefinition columnDefinition,
            JavaFieldDefinition javaFieldDefinition,
            List<String> violations
    ) {
        if (columnDefinition.checkConstraint() == null || columnDefinition.checkConstraint().isBlank()) {
            return;
        }

        boolean hasPattern = javaFieldDefinition.hasAnnotation("Pattern");
        boolean hasColumnDefinition = javaFieldDefinition.annotationAttribute("Column", "columnDefinition") != null;

        if (!hasPattern && !hasColumnDefinition) {
            violations.add("[%s] Column '%s' has SQL CHECK constraint '%s' but Java field does not preserve check metadata"
                    .formatted(
                            entityDefinition.displayName(),
                            columnDefinition.name(),
                            columnDefinition.checkConstraint()
                    ));
        }
    }

    private boolean isStringLikeColumn(ColumnDefinition columnDefinition) {
        if (columnDefinition.sqlType() == null) {
            return false;
        }

        String sqlType = columnDefinition.sqlType().toLowerCase(Locale.ROOT);
        return sqlType.startsWith("varchar")
                || sqlType.startsWith("character varying")
                || sqlType.startsWith("char")
                || sqlType.startsWith("character")
                || sqlType.equals("text");
    }

    private void validateNullabilityConstraint(
            JavaEntityDefinition entityDefinition,
            ColumnDefinition columnDefinition,
            JavaFieldDefinition javaFieldDefinition,
            List<String> violations
    ) {
        boolean fieldDeclaresNullableFalse = "false".equalsIgnoreCase(
                javaFieldDefinition.annotationAttribute("Column", "nullable")
        );

        boolean fieldHasNotNull = javaFieldDefinition.hasAnnotation("NotNull");
        boolean primitiveTypeImpliesNotNull = isPrimitiveJavaType(javaFieldDefinition.type());

        if (!columnDefinition.nullable()
                && !fieldDeclaresNullableFalse
                && !fieldHasNotNull
                && !primitiveTypeImpliesNotNull) {
            violations.add("[%s] Column '%s' is NOT NULL in SQL but not enforced in Java"
                    .formatted(entityDefinition.displayName(), columnDefinition.name()));
        }
    }

    private boolean isPrimitiveJavaType(String typeName) {
        String simpleType = simpleType(typeName);

        return "int".equals(simpleType)
                || "long".equals(simpleType)
                || "double".equals(simpleType)
                || "float".equals(simpleType)
                || "boolean".equals(simpleType)
                || "short".equals(simpleType)
                || "byte".equals(simpleType)
                || "char".equals(simpleType);
    }

    private void validateUniqueConstraint(
            JavaEntityDefinition entityDefinition,
            ColumnDefinition columnDefinition,
            JavaFieldDefinition javaFieldDefinition,
            List<String> violations
    ) {
        boolean fieldDeclaresUnique = "true".equalsIgnoreCase(
                javaFieldDefinition.annotationAttribute("Column", "unique")
        );

        if (columnDefinition.unique() && !fieldDeclaresUnique) {
            violations.add("[%s] Column '%s' is UNIQUE in SQL but not in Java"
                    .formatted(entityDefinition.displayName(), columnDefinition.name()));
        }
    }

    private void validateLengthConstraint(
            JavaEntityDefinition entityDefinition,
            ColumnDefinition columnDefinition,
            JavaFieldDefinition javaFieldDefinition,
            List<String> violations
    ) {
        if (columnDefinition.length() == null) {
            return;
        }

        Integer actualLength = tryParseInteger(
                javaFieldDefinition.annotationAttribute("Column", "length")
        );

        if (actualLength != null && !columnDefinition.length().equals(actualLength)) {
            violations.add("[%s] Column '%s' length mismatch (SQL=%d, Java=%d)"
                    .formatted(entityDefinition.displayName(), columnDefinition.name(),
                            columnDefinition.length(), actualLength));
        }
    }

    private void validateNumericConstraint(
            JavaEntityDefinition entityDefinition,
            ColumnDefinition columnDefinition,
            JavaFieldDefinition javaFieldDefinition,
            List<String> violations
    ) {
        Integer javaPrecision = tryParseInteger(javaFieldDefinition.annotationAttribute("Column", "precision"));
        Integer javaScale = tryParseInteger(javaFieldDefinition.annotationAttribute("Column", "scale"));

        if (columnDefinition.precision() != null && javaPrecision != null
                && !columnDefinition.precision().equals(javaPrecision)) {
            violations.add("[%s] Column '%s' precision mismatch (SQL=%d, Java=%d)"
                    .formatted(entityDefinition.displayName(), columnDefinition.name(),
                            columnDefinition.precision(), javaPrecision));
        }

        if (columnDefinition.scale() != null && javaScale != null
                && !columnDefinition.scale().equals(javaScale)) {
            violations.add("[%s] Column '%s' scale mismatch (SQL=%d, Java=%d)"
                    .formatted(entityDefinition.displayName(), columnDefinition.name(),
                            columnDefinition.scale(), javaScale));
        }
    }

    private Integer tryParseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record TableDefinition(
            String fullName,
            Map<String, ColumnDefinition> columns,
            List<ForeignKeyDefinition> foreignKeys,
            Set<String> uniqueColumns
    ) {
    }

    private record ColumnDefinition(
            String name,
            String sqlType,
            Integer length,
            Integer precision,
            Integer scale,
            boolean nullable,
            boolean primaryKey,
            boolean unique,
            String defaultValue,
            String checkConstraint
    ) {
    }

    private record ForeignKeyDefinition(
            String sourceTable,
            String sourceColumn,
            String targetTable,
            String targetColumn
    ) {
    }

    private record JavaEntityDefinition(
            Path sourceFile,
            String packageName,
            String simpleName,
            boolean isEntity,
            boolean isMappedSuperclass,
            boolean isEmbeddable,
            boolean hasTodoComment,
            String tableSchema,
            String tableName,
            List<JavaFieldDefinition> fields
    ) {
        private String displayName() {
            return packageName == null || packageName.isBlank()
                    ? simpleName
                    : packageName + "." + simpleName;
        }
    }

    private record JavaFieldDefinition(
            String name,
            String type,
            List<AnnotationDefinition> annotations
    ) {
        private boolean hasAnnotation(String annotationName) {
            return annotations.stream().anyMatch(annotation -> annotation.name().equals(annotationName));
        }

        private String annotationAttribute(String annotationName, String attributeName) {
            return annotations.stream()
                    .filter(annotation -> annotation.name().equals(annotationName))
                    .map(annotation -> annotation.attributes().get(attributeName))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        private String annotationRawArguments(String annotationName) {
            return annotations.stream()
                    .filter(annotation -> annotation.name().equals(annotationName))
                    .map(AnnotationDefinition::rawArguments)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
    }

    private record AnnotationDefinition(
            String name,
            Map<String, String> attributes,
            String rawArguments
    ) {
    }

    /**
     * Returns the validation checklist lines used by entity/schema validation.
     *
     * @return ordered checklist lines
     */
    public List<String> getValidationChecklistLines() {
        List<String> checklist = new ArrayList<>();

        checklist.add("1. Checks that the schema SQL file exists");
        checklist.add("2. Checks that the generated Java root exists");
        checklist.add("3. Checks that generated entity files can be parsed without errors");
        checklist.add("4. Checks that at least one generated entity source file exists");
        checklist.add("5. Scans generated entity source files under the entity package");
        checklist.add("6. Parses @Entity, @Embeddable, @Table, fields, and annotations from source files");
        checklist.add("7. Prints generated entities that still contain TODO comments");
        checklist.add("8. Checks that each entity table exists in the SQL schema");
        checklist.add("9. Checks that each entity has @Table(name=...)");
        checklist.add("10. Checks that each entity has @Id or @EmbeddedId");
        checklist.add("11. Checks for duplicate Java field names in generated entities");
        checklist.add("12. Checks simple field-to-column mappings against SQL table columns");
        checklist.add("13. Checks embedded id fields against SQL columns");
        checklist.add("14. Checks for missing DB columns for mapped fields");
        checklist.add("15. Checks for unmapped non-audit SQL columns");
        checklist.add("16. Checks Java field types against SQL column types using TypeMapper");
        checklist.add("17. Checks SQL NOT NULL against Java (@NotNull / nullable=false / primitives)");
        checklist.add("18. Checks SQL UNIQUE against @Column(unique=true)");
        checklist.add("19. Checks SQL length against @Column(length)");
        checklist.add("20. Checks SQL precision against @Column(precision)");
        checklist.add("21. Checks SQL scale against @Column(scale)");
        checklist.add("22. Checks @Size(max=...) against SQL varchar/char length");
        checklist.add("23. Checks @Digits(integer=..., fraction=...) against SQL numeric precision/scale");
        checklist.add("24. Checks presence of SQL default metadata on boolean-like columns");
        checklist.add("25. Checks presence of SQL check constraints on string-like columns");
        checklist.add("26. Checks @ManyToOne fields for @JoinColumn/@JoinColumns presence");
        checklist.add("27. Checks @ManyToOne join columns exist in the owning SQL table");
        checklist.add("28. Checks @ManyToOne join columns are backed by a foreign key");
        checklist.add("29. Checks @OneToOne owning-side fields for @JoinColumn/@JoinColumns presence");
        checklist.add("30. Checks @OneToOne join columns exist in the owning SQL table");
        checklist.add("31. Checks @OneToOne join columns are backed by a foreign key");
        checklist.add("32. Checks @OneToMany fields define mappedBy");
        checklist.add("33. Checks @OneToMany mappedBy points to a real field on the target entity");
        checklist.add("34. Checks @OneToMany mappedBy points to a @ManyToOne or @OneToOne owning field");
        checklist.add("35. Checks owning @ManyToMany fields define @JoinTable(name=...)");
        checklist.add("36. Checks @ManyToMany join table exists in the parsed SQL schema");
        checklist.add("37. Checks @ManyToMany joinColumns and inverseJoinColumns exist in the join table");
        checklist.add("38. Checks @ManyToMany join table columns are backed by foreign keys");
        checklist.add("39. Checks relation target entity type matches foreign-key target table");
        checklist.add("40. Checks referencedColumnName matches foreign-key target column");
        checklist.add("41. Checks local foreign keys are not mapped as scalar fields");
        checklist.add("42. Checks local foreign keys are not missing relation fields");
        checklist.add("43. Ignores foreign keys pointing to external tables not present in the parsed schema");
        checklist.add("44. Checks @MapsId values against embedded id fields");
        checklist.add("45. Checks @MapsId join columns against embedded id column mappings");
        checklist.add("46. Checks local foreign-key Java type against referenced target column Java type");

        return checklist;
    }

    /**
     * Returns the generated entity display names that still contain TODO comments.
     *
     * @return ordered entity display names with TODO comments
     */
    public List<String> findEntityDisplayNamesWithTodoComments() {
        List<String> violations = new ArrayList<>();

        try {
            if (!Files.exists(Constants.GENERATED_JAVA_ROOT)) {
                return List.of();
            }

            List<JavaEntityDefinition> entityDefinitions = findGeneratedEntityDefinitions(violations);

            return entityDefinitions.stream()
                    .filter(JavaEntityDefinition::hasTodoComment)
                    .map(JavaEntityDefinition::displayName)
                    .sorted()
                    .toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    /**
     * Returns true when the field uses Hibernate/JPA UUID generation
     * and the SQL default is gen_random_uuid().
     *
     * @param columnDefinition parsed SQL column definition
     * @param javaFieldDefinition parsed Java field definition
     * @return true when UUID generation metadata is already covered
     */
    private boolean isUuidGeneratedValue(
            ColumnDefinition columnDefinition,
            JavaFieldDefinition javaFieldDefinition
    ) {
        String defaultValue = columnDefinition.defaultValue();

        if (defaultValue == null || defaultValue.isBlank()) {
            return false;
        }

        boolean sqlUsesUuidGeneration =
                normalizeName(defaultValue).contains("gen_random_uuid()");

        if (!sqlUsesUuidGeneration) {
            return false;
        }

        return javaFieldDefinition.hasAnnotation("GeneratedValue")
                && javaFieldDefinition.annotationRawArguments("GeneratedValue") != null
                && javaFieldDefinition.annotationRawArguments("GeneratedValue")
                .contains("GenerationType.UUID");
    }
}