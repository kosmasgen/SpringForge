package com.sqldomaingen.generator;

import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.ManyToManyRelation;
import com.sqldomaingen.model.Relationship;
import com.sqldomaingen.model.Table;
import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.util.NamingConverter;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.*;

@Getter
@NoArgsConstructor
@Component
@Log4j2
public class RelationshipResolver {

    private final Map<String, Table> tableMap = new HashMap<>();
    private final List<Relationship> relationships = new ArrayList<>();

    public RelationshipResolver(Map<String, Table> tableMap) {
        this.tableMap.putAll(tableMap);
    }

    /**
     * Resolves and attaches relationships for a given table.
     * <p>
     * If the table is a pure join table, it is registered as a synthetic
     * many-to-many relation and no standard FK relationships are created.
     *
     * @param sourceTable the table to analyze
     * @return list of relationships created for the source table
     */
    public List<Relationship> resolveRelationships(Table sourceTable) {
        log.info("Resolving relationships for table: {}", sourceTable.getName());

        if (sourceTable.isPureJoinTable()) {
            log.debug("Table '{}' is already marked as pure join table. Skipping standard resolution.",
                    sourceTable.getName());
            return Collections.emptyList();
        }

        if (isPureManyToManyJoinTable(sourceTable)) {
            registerPureManyToMany(sourceTable);
            return Collections.emptyList();
        }

        return handleForeignKeyRelationships(sourceTable);
    }

    /**
     * Adds the inverse relationship to the referenced table.
     * <p>
     * Standard inverse rules:
     * <ul>
     *     <li>MANYTOONE -> ONETOMANY or ONETOONE if FK is unique</li>
     *     <li>ONETOONE -> ONETOONE</li>
     *     <li>MANYTOMANY pseudo is skipped here</li>
     * </ul>
     *
     * @param relationship the owning-side relationship
     * @param column       the FK column used to create the relationship
     */
    private void addInverseRelationship(Relationship relationship, Column column) {
        if (relationship.getRelationshipType() == Relationship.RelationshipType.MANYTOMANY) {
            log.info("Skipping inverse for pseudo-ManyToMany on '{}.{}'",
                    relationship.getSourceTable(), relationship.getSourceColumn());
            return;
        }

        Table targetTable = findTargetTable(relationship.getTargetTable());
        if (targetTable == null) {
            log.warn("Target table '{}' not found while adding inverse relationship for '{}'",
                    relationship.getTargetTable(), column.getName());
            return;
        }

        Relationship.RelationshipType inverseType = determineInverseType(relationship, column);

        Relationship inverseRelationship = new Relationship();
        inverseRelationship.setSourceColumn(relationship.getTargetColumn());
        inverseRelationship.setTargetColumn(relationship.getSourceColumn());
        inverseRelationship.setSourceTable(relationship.getTargetTable());
        inverseRelationship.setTargetTable(relationship.getSourceTable());
        inverseRelationship.setRelationshipType(inverseType);
        inverseRelationship.setMappedBy(getMappedByFieldName(column));

        if (!targetTable.getRelationships().contains(inverseRelationship)) {
            targetTable.addRelationship(inverseRelationship);
            log.info("Inverse relationship added: {} -> {} ({})",
                    inverseRelationship.getSourceTable(),
                    inverseRelationship.getTargetTable(),
                    inverseRelationship.getRelationshipType());
        } else {
            log.debug("Inverse relationship already exists in '{}'. Skipping: {}",
                    targetTable.getName(), inverseRelationship);
        }
    }

    /**
     * Determines the inverse relationship type based on the owning-side type.
     *
     * @param relationship the owning-side relationship
     * @param column       the FK column used by the owning side
     * @return the inverse relationship type
     */
    private Relationship.RelationshipType determineInverseType(Relationship relationship, Column column) {
        if (relationship.getRelationshipType() == Relationship.RelationshipType.MANYTOONE) {
            return column.isUnique()
                    ? Relationship.RelationshipType.ONETOONE
                    : Relationship.RelationshipType.ONETOMANY;
        }

        if (relationship.getRelationshipType() == Relationship.RelationshipType.ONETOONE) {
            return Relationship.RelationshipType.ONETOONE;
        }

        return Relationship.RelationshipType.ONETOMANY;
    }

    /**
     * Derives the owning-side field name referenced by mappedBy.
     * Example: teacher_id -> teacher
     *
     * @param column the owning FK column
     * @return the mappedBy value
     */
    private String getMappedByFieldName(Column column) {
        String rawName = column.getName();

        if (rawName.toLowerCase().endsWith("_id")) {
            rawName = rawName.substring(0, rawName.length() - 3);
        }

        return NamingConverter.toCamelCase(rawName);
    }

    /**
     * Creates standard FK relationships for all FK columns of the source table.
     *
     * @param sourceTable the table being analyzed
     * @return locally created owning-side relationships
     */
    private List<Relationship> handleForeignKeyRelationships(Table sourceTable) {
        log.debug("Handling foreign key relationships for table: {}", sourceTable.getName());

        List<Relationship> localRelationships = new ArrayList<>();
        List<Column> foreignKeys = getForeignKeys(sourceTable);

        for (Column column : foreignKeys) {
            Relationship relationship = createRelationship(column, sourceTable);

            if (relationship == null) {
                log.warn("No relationship created for foreign key '{}'", column.getName());
                continue;
            }

            localRelationships.add(relationship);
            sourceTable.addRelationship(relationship);
            this.relationships.add(relationship);

            log.info("Relationship created: {} -> {} ({})",
                    relationship.getSourceTable(),
                    relationship.getTargetTable(),
                    relationship.getRelationshipType());

            addInverseRelationship(relationship, column);
        }

        log.info("Finished handling foreign key relationships for table '{}'. Relationships created: {}",
                sourceTable.getName(), localRelationships.size());

        return localRelationships;
    }

    /**
     * Returns all FK columns of the given table.
     *
     * @param table the table to inspect
     * @return the FK columns
     */
    private List<Column> getForeignKeys(Table table) {
        return table.getColumns().stream()
                .filter(Column::isForeignKey)
                .toList();
    }

    /**
     * Builds a standard {@link Relationship} object for a given FK column.
     *
     * @param column      FK column
     * @param sourceTable owning table
     * @return relationship or null if target table/column cannot be resolved
     */
    public Relationship createRelationship(Column column, Table sourceTable) {
        log.info("Creating relationship for column '{}' in table '{}', referencing '{}.{}'",
                column.getName(), sourceTable.getName(), column.getReferencedTable(), column.getReferencedColumn());

        Table targetTable = findTargetTable(column.getReferencedTable());

        //  FIX: Do NOT drop FK if target table is missing
        if (targetTable == null) {
            log.warn("Target table '{}' not found for column '{}'. Keeping as scalar FK field.",
                    column.getReferencedTable(), column.getName());
            return null; // IMPORTANT: relation skipped, but column MUST remain
        }

        Column targetColumn = findTargetColumn(targetTable, column.getReferencedColumn());
        if (targetColumn == null) {
            log.warn("Target column '{}' not found in table '{}'",
                    column.getReferencedColumn(), targetTable.getName());

            //  same logic εδώ
            return null;
        }

        Relationship.RelationshipType relationshipType = determineType(column);
        if (relationshipType == null) {
            log.warn("Unable to determine relationship type for column '{}' in table '{}'",
                    column.getName(), sourceTable.getName());
            return null;
        }

        Relationship relationship = new Relationship();
        relationship.setSourceTable(sourceTable.getName());
        relationship.setSourceColumn(column.getName());
        relationship.setTargetTable(targetTable.getName());
        relationship.setTargetColumn(targetColumn.getName());
        relationship.setRelationshipType(relationshipType);
        relationship.setOnDelete(column.getOnDelete());
        relationship.setOnUpdate(column.getOnUpdate());

        log.debug("Created relationship: {}", relationship);
        return relationship;
    }

    /**
     * Checks whether the given table should be treated as a synthetic pure many-to-many join table.
     * <p>
     * This implementation is intentionally conservative.
     * A table must:
     * <ul>
     *     <li>have exactly 2 columns</li>
     *     <li>both columns must be FK</li>
     *     <li>both columns must be PK</li>
     *     <li>both FK targets must be resolvable</li>
     *     <li>and the normalized table name must be explicitly allowed</li>
     * </ul>
     *
     * @param table the table to inspect
     * @return true if the table should be converted to synthetic many-to-many metadata
     */
    private boolean isPureManyToManyJoinTable(Table table) {
        if (table == null || table.getColumns() == null || table.getColumns().size() != 2) {
            return false;
        }

        List<Column> foreignKeys = getForeignKeys(table);
        if (foreignKeys.size() != 2) {
            return false;
        }

        boolean allForeignKeysArePrimaryKeys = foreignKeys.stream()
                .allMatch(Column::isPrimaryKey);

        if (!allForeignKeysArePrimaryKeys) {
            return false;
        }

        boolean allForeignKeysHaveTargets = foreignKeys.stream()
                .allMatch(this::hasReferencedTarget);

        if (!allForeignKeysHaveTargets) {
            return false;
        }

        return isExplicitSyntheticManyToManyTable(table);
    }

    /**
     * Determines whether a join table name is explicitly allowed to be generated
     * as a synthetic pure many-to-many relationship.
     * <p>
     * This avoids accidentally converting association entities that happen to have
     * two FK primary-key columns but are still expected to exist as standalone entities.
     *
     * @param table the table to inspect
     * @return true only for explicitly supported synthetic many-to-many join tables
     */
    private boolean isExplicitSyntheticManyToManyTable(Table table) {
        String normalizedTableName = GeneratorSupport.normalizeTableName(table.getName());

        Set<String> syntheticManyToManyTables = Set.of(
                "company_language"
        );

        return syntheticManyToManyTables.contains(normalizedTableName);
    }

    /**
     * Checks whether the FK column references a resolvable target table and column.
     *
     * @param column the FK column to inspect
     * @return true when target metadata can be resolved
     */
    private boolean hasReferencedTarget(Column column) {
        if (column.getReferencedTable() == null || column.getReferencedColumn() == null) {
            return false;
        }

        Table targetTable = findTargetTable(column.getReferencedTable());
        if (targetTable == null) {
            return false;
        }

        return findTargetColumn(targetTable, column.getReferencedColumn()) != null;
    }

    /**
     * Registers synthetic many-to-many metadata on the two parent tables and
     * marks the join table as pure so that no entity is generated for it later.
     *
     * @param joinTable the pure join table
     */
    private void registerPureManyToMany(Table joinTable) {
        List<Column> foreignKeys = getForeignKeys(joinTable);
        Column owningColumn = foreignKeys.get(0);
        Column inverseColumn = foreignKeys.get(1);

        Table owningTable = findTargetTable(owningColumn.getReferencedTable());
        Table inverseTable = findTargetTable(inverseColumn.getReferencedTable());

        if (owningTable == null || inverseTable == null) {
            log.warn("Skipping pure many-to-many registration for '{}'. Parent table resolution failed.",
                    joinTable.getName());
            return;
        }

        String owningFieldName = toCollectionFieldName(inverseTable.getName());
        String inverseFieldName = toCollectionFieldName(owningTable.getName());

        ManyToManyRelation owningRelation = ManyToManyRelation.builder()
                .fieldName(owningFieldName)
                .targetEntityName(toEntityName(inverseTable.getName()))
                .joinTableName(GeneratorSupport.normalizeTableName(joinTable.getName()))
                .joinColumnName(owningColumn.getName())
                .inverseJoinColumnName(inverseColumn.getName())
                .owningSide(true)
                .build();

        ManyToManyRelation inverseRelation = ManyToManyRelation.builder()
                .fieldName(inverseFieldName)
                .targetEntityName(toEntityName(owningTable.getName()))
                .mappedBy(owningFieldName)
                .owningSide(false)
                .build();

        addManyToManyRelationIfAbsent(owningTable, owningRelation);
        addManyToManyRelationIfAbsent(inverseTable, inverseRelation);

        joinTable.setPureJoinTable(true);

        log.info("Registered pure many-to-many join table '{}': {} <-> {}",
                joinTable.getName(), owningTable.getName(), inverseTable.getName());
    }

    /**
     * Adds synthetic many-to-many metadata to a table if the same relation
     * has not already been registered.
     *
     * @param table    the table receiving the metadata
     * @param relation the relation metadata to register
     */
    private void addManyToManyRelationIfAbsent(Table table, ManyToManyRelation relation) {
        boolean alreadyExists = table.getManyToManyRelations().stream()
                .anyMatch(existing ->
                        existing.isOwningSide() == relation.isOwningSide()
                                && safeEquals(existing.getFieldName(), relation.getFieldName())
                                && safeEquals(existing.getTargetEntityName(), relation.getTargetEntityName())
                                && safeEquals(existing.getMappedBy(), relation.getMappedBy()));

        if (!alreadyExists) {
            table.addManyToManyRelation(relation);
        }
    }

    /**
     * Builds the generated entity name from a physical table name.
     * Example: pep_schema.company_language -> CompanyLanguage
     *
     * @param tableName the physical table name
     * @return the generated entity class name
     */
    private String toEntityName(String tableName) {
        String normalized = GeneratorSupport.normalizeTableName(tableName);
        String[] parts = normalized.split("_");
        StringBuilder builder = new StringBuilder();

        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }

            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1).toLowerCase());
            }
        }

        return builder.toString();
    }

    /**
     * Builds the collection field name for a generated many-to-many relation.
     * Example: company -> companies, languages -> languages
     *
     * @param tableName the referenced table name
     * @return the collection field name
     */
    private String toCollectionFieldName(String tableName) {
        String camelCaseName = NamingConverter.toCamelCase(GeneratorSupport.normalizeTableName(tableName));

        if (camelCaseName.endsWith("s")) {
            return camelCaseName;
        }

        return pluralize(camelCaseName);
    }

    /**
     * Applies a basic English pluralization rule for generated collection fields.
     *
     * @param singularName the singular field base name
     * @return a pluralized field name
     */
    private String pluralize(String singularName) {
        String lower = singularName.toLowerCase();

        if (lower.endsWith("y")
                && singularName.length() > 1
                && !isVowel(lower.charAt(lower.length() - 2))) {
            return singularName.substring(0, singularName.length() - 1) + "ies";
        }

        if (lower.endsWith("s")
                || lower.endsWith("x")
                || lower.endsWith("z")
                || lower.endsWith("ch")
                || lower.endsWith("sh")) {
            return singularName + "es";
        }

        return singularName + "s";
    }

    /**
     * Checks whether the provided character is a vowel.
     *
     * @param character the character to inspect
     * @return true when the character is a vowel
     */
    private boolean isVowel(char character) {
        return character == 'a'
                || character == 'e'
                || character == 'i'
                || character == 'o'
                || character == 'u';
    }

    /**
     * Performs a null-safe equality check.
     *
     * @param left  first value
     * @param right second value
     * @return true when both values are equal
     */
    private boolean safeEquals(String left, String right) {
        if (left == null) {
            return right == null;
        }

        return left.equals(right);
    }

    /**
     * Finds a target table by trying exact, schema-stripped, and normalized matches.
     *
     * @param referencedTableRaw the raw referenced table name
     * @return the matching table or null
     */
    private Table findTargetTable(String referencedTableRaw) {
        String raw = referencedTableRaw == null ? "" : referencedTableRaw.trim();
        if (raw.isBlank()) {
            log.error("Referenced table name is null or blank.");
            return null;
        }

        Table direct = tableMap.get(raw);
        if (direct != null) {
            return direct;
        }

        String noSchema = GeneratorSupport.normalizeTableName(raw);
        Table noSchemaHit = tableMap.get(noSchema);
        if (noSchemaHit != null) {
            return noSchemaHit;
        }

        for (Map.Entry<String, Table> entry : tableMap.entrySet()) {
            String keyNorm = GeneratorSupport.normalizeTableName(entry.getKey());
            if (keyNorm.equalsIgnoreCase(noSchema)) {
                return entry.getValue();
            }
        }

        log.error("Table '{}' not found in tableMap keys: {}", raw, tableMap.keySet());
        return null;
    }


    /**
     * Determines the relationship type for a standard FK column.
     *
     * @param column      the FK column
     * @return the resolved relationship type
     */
    private Relationship.RelationshipType determineType(
            Column column
    ) {
        if (column.isManyToMany()) {
            log.info("Column '{}' has explicit MANYTOMANY pseudo-constraint. Assigning MANYTOMANY.",
                    column.getName());
            return Relationship.RelationshipType.MANYTOMANY;
        }

        if (column.isUnique()) {
            log.info("Column '{}' is unique. Assigning ONETOONE.", column.getName());
            return Relationship.RelationshipType.ONETOONE;
        }

        log.info("Column '{}' is not unique. Assigning MANYTOONE.", column.getName());
        return Relationship.RelationshipType.MANYTOONE;
    }

    /**
     * Finds the referenced column in the target table using case-insensitive comparison.
     *
     * @param targetTable      the referenced table
     * @param targetColumnName the referenced column name
     * @return the resolved target column or null
     */
    private Column findTargetColumn(Table targetTable, String targetColumnName) {
        if (targetTable == null || targetColumnName == null || targetColumnName.isBlank()) {
            log.warn("Invalid target column lookup. Table: '{}', Column: '{}'",
                    targetTable != null ? targetTable.getName() : "null",
                    targetColumnName);
            return null;
        }

        Column targetColumn = targetTable.getColumns().stream()
                .filter(column -> column.getName().equalsIgnoreCase(targetColumnName))
                .findFirst()
                .orElse(null);

        if (targetColumn == null) {
            log.warn("Column '{}' not found in table '{}'",
                    targetColumnName, targetTable.getName());
        }

        return targetColumn;
    }

    /**
     * Resolves relationships for every table currently present in the resolver map.
     * Pure join tables are registered as synthetic many-to-many metadata and skipped
     * from the standard FK relationship flow.
     */
    public void resolveRelationshipsForAllTables() {
        log.info("Starting to resolve relationships for all {} tables...", tableMap.size());

        for (Table table : tableMap.values()) {
            log.info(" Resolving relationships for table: {}", table.getName());

            if (isPureManyToManyJoinTable(table)) {
                registerPureManyToMany(table);
                continue;
            }

            List<Relationship> localRelationships = resolveRelationships(table);

            for (Relationship relationship : localRelationships) {
                log.info("Relationship created: {} -> {} | Type: {}",
                        relationship.getSourceTable(),
                        relationship.getTargetTable(),
                        relationship.getRelationshipType());
            }
        }

        log.info("Relationships resolved for all tables.");
    }
}