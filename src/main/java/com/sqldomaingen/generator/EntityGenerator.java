package com.sqldomaingen.generator;

import com.sqldomaingen.model.*;
import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.util.NamingConverter;
import com.sqldomaingen.util.PackageResolver;
import com.sqldomaingen.util.TypeMapper;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Log4j2
@NoArgsConstructor
@Getter
@Component
public class EntityGenerator {

    private final Map<String, Table> tableMap = new HashMap<>();

    /**
     * Generates entity classes for all non-pure-join tables.
     * <p>
     * Pure join tables are skipped because they are represented as synthetic
     * many-to-many metadata on their parent entities.
     *
     * @param tables the parsed tables
     * @param outputDir the root output directory
     * @param basePackage the base package name
     * @param overwrite whether existing files should be overwritten
     * @param useBuilder whether Lombok builder should be generated
     */
    public void generate(List<Table> tables,
                         String outputDir,
                         String basePackage,
                         boolean overwrite,
                         boolean useBuilder) {

        log.info("Starting entity generation...");

        if (tables == null || tables.isEmpty()) {
            log.warn("No tables provided for entity generation.");
            return;
        }

        Path entityDir = PackageResolver.resolvePath(outputDir, basePackage, "entity");

        try {
            Files.createDirectories(entityDir);
        } catch (IOException exception) {
            log.error("Failed to create entity output directory: {}", entityDir, exception);
            return;
        }

        Map<String, Table> tablesMap = tables.stream()
                .collect(Collectors.toMap(Table::getName, table -> table, (existing, replacement) -> existing, LinkedHashMap::new));

        this.tableMap.clear();
        this.tableMap.putAll(tablesMap);

        log.debug("Created tablesMap with keys: {}", tablesMap.keySet());

        for (Table table : this.tableMap.values()) {
            table.setRelationships(new ArrayList<>());
            table.setManyToManyRelations(new ArrayList<>());
            table.setPureJoinTable(false);
        }

        RelationshipResolver resolver = new RelationshipResolver(this.tableMap);
        resolver.resolveRelationshipsForAllTables();
        log.debug("RelationshipResolver initialized and relationships resolved.");

        String entityPackage = PackageResolver.resolvePackageName(basePackage, "entity");

        for (Table originalTable : tables) {
            Table resolvedTable = this.tableMap.getOrDefault(originalTable.getName(), originalTable);

            log.debug("Processing table: {}", resolvedTable.getName());

            if (resolvedTable.isPureJoinTable()) {
                log.info("Skipping pure join table entity generation for table: {}", resolvedTable.getName());
                continue;
            }

            String rawTableName = resolvedTable.getName();
            String tableName = rawTableName != null && rawTableName.contains(".")
                    ? rawTableName.substring(rawTableName.indexOf('.') + 1)
                    : rawTableName;

            String entityName = NamingConverter.toPascalCase(tableName);

            String entityContent = createEntityContent(resolvedTable, entityPackage, useBuilder);
            Path entityOutputPath = entityDir.resolve(entityName + ".java");

            if (!overwrite && Files.exists(entityOutputPath)) {
                log.warn("Entity file already exists, skipping: {}", entityOutputPath);
            } else {
                try {
                    writeToFile(entityOutputPath.toString(), entityContent);
                    log.debug("Generated entity for table: {}", resolvedTable.getName());
                } catch (IOException exception) {
                    log.error("Failed to write entity file for table: {}", resolvedTable.getName(), exception);
                }
            }

            if (hasCompositePrimaryKey(resolvedTable)) {
                String pkClassName = getEmbeddedIdTypeName(resolvedTable);
                String pkContent = createEmbeddedIdClassContent(resolvedTable, entityPackage);
                Path pkOutputPath = entityDir.resolve(pkClassName + ".java");

                if (!overwrite && Files.exists(pkOutputPath)) {
                    log.warn("PK file already exists, skipping: {}", pkOutputPath);
                } else {
                    try {
                        writeToFile(pkOutputPath.toString(), pkContent);
                        log.debug("Generated composite PK class '{}' for table: {}", pkClassName, resolvedTable.getName());
                    } catch (IOException exception) {
                        log.error("Failed to write composite PK file '{}' for table: {}", pkClassName, resolvedTable.getName(), exception);
                    }
                }
            }
        }

        log.debug("Entity generation complete. Output directory: {}", entityDir.toAbsolutePath());
    }



    /**
     * Creates the embedded id class content for a table with a composite primary key.
     *
     * @param table the source table
     * @param packageName the target package name
     * @return the generated embedded id class content
     */
    private String createEmbeddedIdClassContent(Table table, String packageName) {
        StringBuilder builder = new StringBuilder();

        String pkClassName = getEmbeddedIdTypeName(table);
        List<Column> pkColumns = getPrimaryKeyColumns(table);

        builder.append("package ").append(packageName).append(";\n\n");
        builder.append("import jakarta.persistence.*;\n");
        builder.append("import lombok.*;\n");
        builder.append("import java.io.Serializable;\n");

        Set<String> imports = new TreeSet<>();
        for (Column column : pkColumns) {
            if (column == null) {
                continue;
            }

            String javaType = resolveJavaType(column);
            if (javaType == null || javaType.isBlank()) {
                continue;
            }

            if (javaType.startsWith("java.time.")) {
                imports.add("import " + javaType + ";");
            } else if (javaType.startsWith("java.math.")) {
                imports.add("import " + javaType + ";");
            } else if (javaType.startsWith("java.util.") && !javaType.equals("java.util.List")) {
                imports.add("import " + javaType + ";");
            }
        }

        if (!imports.isEmpty()) {
            for (String currentImport : imports) {
                builder.append(currentImport).append("\n");
            }
        }

        builder.append("\n");
        builder.append("@Embeddable\n");
        builder.append("@Getter\n");
        builder.append("@Setter\n");
        builder.append("@NoArgsConstructor\n");
        builder.append("@AllArgsConstructor\n");
        builder.append("@EqualsAndHashCode\n");
        builder.append("public class ").append(pkClassName).append(" implements Serializable {\n\n");

        for (Column column : pkColumns) {
            if (column == null) {
                continue;
            }

            String columnName = GeneratorSupport.unquoteIdentifier(column.getName());

            builder.append("    @Column(name = \"").append(columnName).append("\"");
            if (!column.isNullable()) {
                builder.append(", nullable = false");
            }
            builder.append(")\n");

            builder.append("    private ")
                    .append(toSimpleJavaType(resolveJavaType(column)))
                    .append(" ")
                    .append(NamingConverter.toCamelCase(columnName))
                    .append(";\n\n");
        }

        builder.append("}\n");

        return builder.toString();
    }


    public String createEntityContent(Table table, String packageName, boolean useBuilder) {
        StringBuilder entityBuilder = new StringBuilder();

        generatePackageAndImports(entityBuilder, packageName, table);
        generateClassAnnotations(entityBuilder, table, useBuilder);
        generateFields(entityBuilder, table);

        entityBuilder.append("}\n");

        //log.debug("Generated entity content for table '{}':\n{}", table.getName(), entityBuilder);
        return entityBuilder.toString();
    }



    /**
     * Generates the package declaration and required imports for an entity class.
     *
     * @param builder the string builder receiving the generated content
     * @param packageName the target package name
     * @param table the source table metadata
     */
    public void generatePackageAndImports(StringBuilder builder, String packageName, Table table) {
        builder.append("package ").append(packageName).append(";\n\n");
        builder.append("import jakarta.persistence.*;\n");
        builder.append("import lombok.*;\n");

        Set<String> imports = new TreeSet<>();

        boolean needsCreationTimestamp = false;
        boolean needsUpdateTimestamp = false;
        boolean needsJsonImports = false;

        boolean compositePrimaryKey = hasCompositePrimaryKey(table);

        imports.add("import org.hibernate.envers.Audited;");

        for (Column column : table.getColumns()) {
            if (column == null) {
                continue;
            }

            if (compositePrimaryKey && column.isPrimaryKey()) {
                continue;
            }

            if (shouldImportScalarColumnJavaType(table, column)) {
                String javaType = resolveJavaType(column);

                if (javaType != null && !javaType.isBlank() && javaType.startsWith("java.")) {
                    imports.add("import " + javaType + ";");
                }
            }

            if (shouldUseCreationTimestamp(column)) {
                needsCreationTimestamp = true;
            }

            if (shouldUseUpdateTimestamp(column)) {
                needsUpdateTimestamp = true;
            }

            if (TypeMapper.isJsonType(column)) {
                needsJsonImports = true;
            }
        }

        boolean needsCollectionImports = table.getRelationships().stream()
                .anyMatch(relationship -> relationship.getRelationshipType() == Relationship.RelationshipType.MANYTOMANY);

        if (!needsCollectionImports) {
            needsCollectionImports = hasSyntheticManyToManyRelations(table);
        }

        if (needsCollectionImports) {
            imports.add("import java.util.List;");
            imports.add("import java.util.ArrayList;");
        }

        if (needsCreationTimestamp) {
            imports.add("import org.hibernate.annotations.CreationTimestamp;");
        }

        if (needsUpdateTimestamp) {
            imports.add("import org.hibernate.annotations.UpdateTimestamp;");
        }

        if (needsJsonImports) {
            imports.add("import org.hibernate.annotations.JdbcTypeCode;");
            imports.add("import org.hibernate.type.SqlTypes;");
        }

        for (String currentImport : imports) {
            builder.append(currentImport).append("\n");
        }

        builder.append("\n");
    }


    /**
     * Determines whether the Java type of column must be imported because
     * the column is generated as a scalar field in the entity.
     *
     * @param table the source table
     * @param column the source column
     * @return true when the generated entity will contain a scalar field for this column
     */
    private boolean shouldImportScalarColumnJavaType(Table table, Column column) {
        if (table == null || column == null) {
            return false;
        }

        if (!column.isForeignKey()) {
            return true;
        }

        ForeignKeyGenerationStrategy strategy = resolveForeignKeyGenerationStrategy(table, column);

        return strategy == ForeignKeyGenerationStrategy.SCALAR
                || strategy == ForeignKeyGenerationStrategy.UNRESOLVED_SCALAR;
    }


    private boolean isUuidType(String javaType) {
        String t = javaType.trim();
        return t.equalsIgnoreCase("UUID") || t.equals("java.util.UUID") || t.endsWith(".UUID");
    }


    public void generateClassAnnotations(StringBuilder builder, Table table, boolean useBuilder) {
        builder.append("@Entity\n");
        builder.append("@Audited\n");

        String rawTableName = table.getName();
        int lastDot = rawTableName.lastIndexOf('.');
        String tableName = (lastDot >= 0) ? rawTableName.substring(lastDot + 1) : rawTableName;

        builder.append("@Table(name = \"")
                .append(NamingConverter.toSnakeCase(tableName))
                .append("\"");

        List<UniqueConstraint> uniqueConstraints = table.getUniqueConstraints();

        if (uniqueConstraints != null && !uniqueConstraints.isEmpty()) {
            List<UniqueConstraint> composite = uniqueConstraints.stream()
                    .filter(uc -> uc.getColumns() != null && uc.getColumns().size() > 1)
                    .toList();

            if (!composite.isEmpty()) {
                builder.append(", uniqueConstraints = ");

                if (composite.size() == 1) {
                    appendUniqueConstraint(builder, composite.getFirst());
                } else {
                    builder.append("{");

                    for (int i = 0; i < composite.size(); i++) {
                        if (i > 0) builder.append(", ");
                        appendUniqueConstraint(builder, composite.get(i));
                    }

                    builder.append("}");
                }
            }
        }

        builder.append(")\n");
        builder.append("@Getter\n");
        builder.append("@Setter\n");

        if (useBuilder) {
            builder.append("@Builder\n");
        }

        builder.append("@NoArgsConstructor\n");
        builder.append("@AllArgsConstructor\n");

        String className = NamingConverter.toPascalCase(tableName);
        builder.append("public class ").append(className).append(" {\n\n");
    }

    private void appendUniqueConstraint(StringBuilder builder, UniqueConstraint uc) {
        builder.append("@UniqueConstraint(columnNames = {");

        List<String> columns = uc.getColumns();

        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) builder.append(", ");

            builder.append("\"")
                    .append(GeneratorSupport.unquoteIdentifier(columns.get(i)))
                    .append("\"");
        }

        builder.append("})");
    }



    /**
     * Generates all entity fields including:
     * <ul>
     *     <li>basic columns</li>
     *     <li>primary keys</li>
     *     <li>standard FK relationships</li>
     *     <li>composite primary-key FK relationships</li>
     *     <li>inverse many-to-many collections</li>
     *     <li>synthetic many-to-many fields from pure join tables</li>
     *     <li>inverse one-to-one relationships</li>
     * </ul>
     *
     * @param builder the builder receiving field content
     * @param table the source table metadata
     */
    public void generateFields(StringBuilder builder, Table table) {
        Set<String> generatedFieldNames = new HashSet<>();

        boolean compositePrimaryKey = hasCompositePrimaryKey(table);
        List<Column> primaryKeyColumns = compositePrimaryKey
                ? getPrimaryKeyColumns(table)
                : Collections.emptyList();

        boolean compositeJoinTable = compositePrimaryKey && isCompositeKeyJoinTable(table);

        if (compositePrimaryKey) {
            String embeddedIdTypeName = getEmbeddedIdTypeName(table);

            builder.append("    @EmbeddedId\n");
            builder.append("    private ").append(embeddedIdTypeName).append(" id;\n\n");
            generatedFieldNames.add("id");
        }

        for (Column column : table.getColumns()) {
            log.debug("Processing column: {}", column.getName());

            if (compositePrimaryKey && isCompositeKeyColumn(column, primaryKeyColumns)) {
                if (column.isForeignKey()) {
                    ForeignKeyGenerationStrategy strategy = resolveForeignKeyGenerationStrategy(table, column);

                    if (strategy == ForeignKeyGenerationStrategy.COMPOSITE_MAPS_ID_RELATION
                            || strategy == ForeignKeyGenerationStrategy.RELATION) {
                        addCompositeKeyRelationshipField(builder, column, table, generatedFieldNames);
                    }
                }

                continue;
            }

            if (column.isPrimaryKey()) {
                addPrimaryKeyAnnotations(builder, column);
                generatedFieldNames.add(column.getFieldName());
                continue;
            }

            if (!column.isForeignKey()) {
                resolveScalarFieldNameCollision(column, generatedFieldNames);
                addColumnField(builder, column);
                generatedFieldNames.add(column.getFieldName());
                continue;
            }

            ForeignKeyGenerationStrategy strategy = resolveForeignKeyGenerationStrategy(table, column);

            switch (strategy) {
                case RELATION -> addRelationshipField(builder, column, table, generatedFieldNames);

                case SCALAR -> {
                    addFlatForeignKeyScalarField(builder, column);
                    generatedFieldNames.add(column.getFieldName());
                }

                case UNRESOLVED_SCALAR -> {
                    addUnresolvedForeignKeyScalarField(builder, column);
                    generatedFieldNames.add(column.getFieldName());
                }

                case COMPOSITE_MAPS_ID_RELATION -> {
                    log.warn("Composite @MapsId strategy reached non-composite branch for column '{}.{}'. Falling back to standard relationship generation.",
                            table.getName(),
                            column.getName());
                    addRelationshipField(builder, column, table, generatedFieldNames);
                }
            }
        }

        if (!compositeJoinTable) {
            appendInverseManyToManyFields(builder, table, generatedFieldNames);
            appendOwningManyToManyFields(builder, table, generatedFieldNames);
            appendSyntheticManyToManyFields(builder, table, generatedFieldNames);
            appendInverseOneToOneFields(builder, table, generatedFieldNames);
        }
    }

    /**
     * Resolves scalar field name collisions before generating a column field.
     *
     * @param column source column
     * @param generatedFieldNames field names already generated in the entity
     */
    private void resolveScalarFieldNameCollision(Column column, Set<String> generatedFieldNames) {
        if (column == null || column.getFieldName() == null) {
            return;
        }

        if (!generatedFieldNames.contains(column.getFieldName())) {
            return;
        }

        String originalFieldName = column.getFieldName();
        String resolvedFieldName = originalFieldName + "Text";

        column.setFieldName(resolvedFieldName);

        log.warn("Resolved scalar field name collision for column '{}': '{}' -> '{}'",
                column.getName(),
                originalFieldName,
                resolvedFieldName);
    }

    /**
     * Appends inverse many-to-many fields for the current table.
     *
     * @param builder the builder receiving field content
     * @param table the source table metadata
     * @param generatedFieldNames already generated field names
     */
    private void appendInverseManyToManyFields(
            StringBuilder builder,
            Table table,
            Set<String> generatedFieldNames
    ) {
        for (Relationship relationship : table.getRelationships()) {
            boolean inverseManyToManySide =
                    relationship.getRelationshipType() == Relationship.RelationshipType.MANYTOMANY
                            && relationship.getMappedBy() != null;

            if (!inverseManyToManySide) {
                continue;
            }

            if (shouldGenerateInverseRelationship(relationship)) {
                log.debug("Skipping inverse relationship field for sourceTable='{}', target='{}', mappedBy='{}' because the owning side will not be generated as a relation field.",
                        relationship.getSourceTable(),
                        relationship.getTargetTable(),
                        relationship.getMappedBy());
                continue;
            }

            addInverseRelationshipField(builder, relationship, generatedFieldNames);
        }
    }

    /**
     * Appends owning many-to-many fields for the current table.
     *
     * @param builder the builder receiving field content
     * @param table the source table metadata
     * @param generatedFieldNames already generated field names
     */
    private void appendOwningManyToManyFields(
            StringBuilder builder,
            Table table,
            Set<String> generatedFieldNames
    ) {
        for (Relationship relationship : table.getRelationships()) {
            boolean owningManyToManySide =
                    relationship.getRelationshipType() == Relationship.RelationshipType.MANYTOMANY
                            && relationship.getMappedBy() == null
                            && relationship.getJoinTableName() != null;

            if (!owningManyToManySide) {
                continue;
            }

            String expectedFieldName = NamingConverter.toCamelCasePlural(stripSchema(relationship.getTargetTable()));
            if (generatedFieldNames.contains(expectedFieldName)) {
                log.warn("Skipping ManyToMany owning-side field '{}': already generated", expectedFieldName);
                continue;
            }

            addManyToManyParentSide(builder, relationship, generatedFieldNames);
        }
    }

    /**
     * Appends synthetic many-to-many fields for the current table.
     *
     * @param builder the builder receiving field content
     * @param table the source table metadata
     * @param generatedFieldNames already generated field names
     */
    private void appendSyntheticManyToManyFields(
            StringBuilder builder,
            Table table,
            Set<String> generatedFieldNames
    ) {
        for (ManyToManyRelation manyToManyRelation : table.getManyToManyRelations()) {
            addSyntheticManyToManyField(builder, manyToManyRelation, generatedFieldNames);
        }
    }

    /**
     * Appends inverse one-to-one fields for the current table.
     *
     * @param builder the builder receiving field content
     * @param table the source table metadata
     * @param generatedFieldNames already generated field names
     */
    private void appendInverseOneToOneFields(
            StringBuilder builder,
            Table table,
            Set<String> generatedFieldNames
    ) {
        for (Relationship relationship : table.getRelationships()) {
            boolean inverseOneToOne =
                    relationship.getRelationshipType() == Relationship.RelationshipType.ONETOONE
                            && relationship.getMappedBy() != null
                            && relationship.getSourceTable().equals(table.getName());

            if (!inverseOneToOne) {
                continue;
            }

            if (shouldGenerateInverseRelationship(relationship)) {
                log.debug("Skipping inverse @OneToOne for sourceTable='{}', target='{}', mappedBy='{}' because the owning side will not be generated as a relation field.",
                        relationship.getSourceTable(),
                        relationship.getTargetTable(),
                        relationship.getMappedBy());
                continue;
            }

            String targetEntity = NamingConverter.toPascalCase(stripSchema(relationship.getTargetTable()));
            String fieldName = java.beans.Introspector.decapitalize(targetEntity);
            String mappedBy = relationship.getMappedBy();

            if (generatedFieldNames.contains(fieldName)) {
                log.warn("Skipping inverse OneToOne field '{}': already generated", fieldName);
                continue;
            }

            generatedFieldNames.add(fieldName);

            builder.append("    @OneToOne(mappedBy = \"").append(mappedBy).append("\")\n");
            builder.append("    private ").append(targetEntity).append(" ").append(fieldName).append(";\n\n");
        }
    }


    /**
     * Checks whether an inverse relationship should be skipped because its owning side
     * will not be generated as a relation field.
     *
     * @param relationship inverse relationship metadata
     * @return true when the inverse relationship should be skipped
     */
    private boolean shouldGenerateInverseRelationship(Relationship relationship) {
        if (relationship == null || relationship.getMappedBy() == null || relationship.getMappedBy().isBlank()) {
            return false;
        }

        Optional<Table> targetTableOptional = findTargetTableInGeneratorMap(relationship.getTargetTable());
        if (targetTableOptional.isEmpty()) {
            return false;
        }

        Table targetTable = targetTableOptional.get();
        String expectedMappedBy = relationship.getMappedBy();
        String expectedParentTable = stripSchema(relationship.getSourceTable());

        for (Column column : targetTable.getColumns()) {
            if (column == null || !column.isForeignKey()) {
                continue;
            }

            ForeignKeyGenerationStrategy strategy = resolveForeignKeyGenerationStrategy(targetTable, column);
            if (strategy != ForeignKeyGenerationStrategy.RELATION
                    && strategy != ForeignKeyGenerationStrategy.COMPOSITE_MAPS_ID_RELATION) {
                continue;
            }

            Optional<Relationship> owningRelationship = resolveOwningRelationshipForFieldGeneration(targetTable, column);
            if (owningRelationship.isEmpty()) {
                continue;
            }

            Relationship resolvedOwningRelationship = owningRelationship.get();
            String resolvedParentTable = stripSchema(resolvedOwningRelationship.getTargetTable());
            String resolvedFieldName = resolveRelationFieldName(column.getName());

            if (expectedParentTable.equals(resolvedParentTable)
                    && expectedMappedBy.equals(resolvedFieldName)) {
                return false;
            }
        }

        return true;
    }


    /**
     * Generates a flat scalar field for a foreign key column that should remain an id
     * instead of becoming a JPA relationship.
     *
     * @param builder the builder receiving the generated content
     * @param column the foreign key column
     */
    private void addFlatForeignKeyScalarField(StringBuilder builder, Column column) {
        String columnName = GeneratorSupport.unquoteIdentifier(column.getName());
        String javaType = resolveJavaType(column);
        String cleanedType = toSimpleJavaType(javaType);
        String sqlType = normalizeSqlType(column.getSqlType());

        builder.append("    @Column(name = \"").append(columnName).append("\"");

        appendCharacterColumnMetadata(builder, column, sqlType);
        appendNumericColumnMetadata(builder, column, sqlType);

        if (column.isUnique()) {
            builder.append(", unique = true");
        }

        if (!column.isNullable()) {
            builder.append(", nullable = false");
        }

        builder.append(")\n");

        builder.append("    private ")
                .append(cleanedType)
                .append(" ")
                .append(column.getFieldName())
                .append(";\n\n");
    }

    /**
     * Appends JPA metadata for VARCHAR, CHAR, BPCHAR, and TEXT columns.
     *
     * @param builder the builder receiving annotation content
     * @param column the source column metadata
     * @param sqlType the normalized SQL type
     */
    private void appendCharacterColumnMetadata(StringBuilder builder, Column column, String sqlType) {
        int length = column.getLength();

        if (isTextType(sqlType)) {
            builder.append(", columnDefinition = \"text\"");
            return;
        }

        if (isFixedCharType(sqlType) && length > 0) {
            builder.append(", columnDefinition = \"char(")
                    .append(length)
                    .append(")\"");
            return;
        }

        if (isVarcharType(sqlType) && length > 0 && length != 255) {
            builder.append(", length = ").append(length);
        }
    }

    /**
     * Appends JPA precision and scale metadata for NUMERIC and DECIMAL columns.
     *
     * @param builder the builder receiving annotation content
     * @param column the source column metadata
     * @param sqlType the normalized SQL type
     */
    private void appendNumericColumnMetadata(StringBuilder builder, Column column, String sqlType) {
        if (!(sqlType.startsWith("DECIMAL") || sqlType.startsWith("NUMERIC"))) {
            return;
        }

        if (column.getPrecision() <= 0) {
            return;
        }

        builder.append(", precision = ").append(column.getPrecision());

        if (column.getScale() > 0) {
            builder.append(", scale = ").append(column.getScale());
        }
    }

    /**
     * Returns true when the SQL type is a VARCHAR family type.
     *
     * @param sqlType the normalized SQL type
     * @return true when the type is VARCHAR
     */
    private boolean isVarcharType(String sqlType) {
        return sqlType != null && sqlType.startsWith("VARCHAR");
    }

    /**
     * Returns true when the SQL type is a fixed CHAR family type.
     *
     * @param sqlType the normalized SQL type
     * @return true when the type is CHAR or BPCHAR
     */
    private boolean isFixedCharType(String sqlType) {
        return sqlType != null
                && (sqlType.startsWith("CHAR") || sqlType.startsWith("BPCHAR"));
    }

    /**
     * Returns true when the SQL type is a TEXT family type.
     *
     * @param sqlType the normalized SQL type
     * @return true when the type is TEXT
     */
    private boolean isTextType(String sqlType) {
        return sqlType != null && sqlType.startsWith("TEXT");
    }




    /**
     * Generates a synthetic pure many-to-many field using metadata registered on the table.
     *
     * @param builder the builder receiving generated content
     * @param manyToManyRelation the synthetic many-to-many metadata
     * @param generatedFieldNames already generated field names for duplicate protection
     */
    public void addSyntheticManyToManyField(StringBuilder builder,
                                            ManyToManyRelation manyToManyRelation,
                                            Set<String> generatedFieldNames) {
        if (manyToManyRelation == null) {
            return;
        }

        String fieldName = manyToManyRelation.getFieldName();
        if (fieldName == null || fieldName.isBlank()) {
            log.warn("Skipping synthetic ManyToMany field because fieldName is blank.");
            return;
        }

        if (generatedFieldNames.contains(fieldName)) {
            log.warn("Skipping synthetic ManyToMany field '{}': already generated", fieldName);
            return;
        }

        generatedFieldNames.add(fieldName);

        String targetEntity = manyToManyRelation.getTargetEntityName();

        if (manyToManyRelation.isOwningSide()) {
            builder.append("    @ManyToMany(fetch = FetchType.LAZY)\n");
            builder.append("    @JoinTable(name = \"")
                    .append(stripSchema(manyToManyRelation.getJoinTableName()))
                    .append("\", joinColumns = @JoinColumn(name = \"")
                    .append(manyToManyRelation.getJoinColumnName())
                    .append("\"), inverseJoinColumns = @JoinColumn(name = \"")
                    .append(manyToManyRelation.getInverseJoinColumnName())
                    .append("\"))\n");
        } else {
            builder.append("    @ManyToMany(mappedBy = \"")
                    .append(manyToManyRelation.getMappedBy())
                    .append("\", fetch = FetchType.LAZY)\n");
        }

        builder.append("    private List<")
                .append(targetEntity)
                .append("> ")
                .append(fieldName)
                .append(" = new ArrayList<>();\n\n");
    }

    /**
     * Checks whether the table contains synthetic many-to-many metadata.
     *
     * @param table the table to inspect
     * @return true when synthetic many-to-many relations exist
     */
    private boolean hasSyntheticManyToManyRelations(Table table) {
        return table != null
                && table.getManyToManyRelations() != null
                && !table.getManyToManyRelations().isEmpty();
    }



    private String getEmbeddedIdTypeName(Table table) {
        String rawTableName = table != null ? table.getName() : null;
        String simpleTableName = stripSchema(rawTableName);
        return NamingConverter.toPascalCase(simpleTableName) + "Key";
    }

    private boolean isCompositeKeyJoinTable(Table table) {
        if (!hasCompositePrimaryKey(table)) {
            return false;
        }

        List<Column> pkColumns = getPrimaryKeyColumns(table);

        // Composite join-like PK table if every PK column:
        // - is marked as FK OR
        // - has a resolved relationship OR
        // - at least looks like FK by naming (e.g. xxx_id)
        return pkColumns.stream().allMatch(col ->
                col.isForeignKey()
                        || findRelationshipForColumn(table, col).isPresent()
                        || looksLikeForeignKeyColumn(col)
        );
    }

    private boolean hasCompositePrimaryKey(Table table) {
        return getPrimaryKeyColumns(table).size() > 1;
    }

    private List<Column> getPrimaryKeyColumns(Table table) {
        if (table == null || table.getColumns() == null) {
            return Collections.emptyList();
        }

        return table.getColumns().stream()
                .filter(Column::isPrimaryKey)
                .collect(Collectors.toList());
    }



    private boolean isCompositeKeyColumn(Column column, List<Column> compositeKeyColumns) {
        if (column == null || compositeKeyColumns == null || compositeKeyColumns.isEmpty()) {
            return false;
        }

        String columnName = column.getName();
        if (columnName == null) {
            return false;
        }

        return compositeKeyColumns.stream()
                .map(Column::getName)
                .filter(Objects::nonNull)
                .anyMatch(columnName::equals);
    }

    private void addCompositeKeyRelationshipField(StringBuilder builder,
                                                  Column column,
                                                  Table table,
                                                  Set<String> generatedFieldNames) {
        log.debug("🔷 Generating composite-key relationship field for column '{}' in table '{}'",
                column.getName(), table.getName());

        Optional<Relationship> relationshipOpt = findRelationshipForColumn(table, column);

        if (relationshipOpt.isEmpty()) {
            relationshipOpt = inferCompositePkRelationship(table, column);
        }

        if (relationshipOpt.isEmpty()) {
            log.warn("⚠️ Composite PK column '{}' in table '{}' has no resolved/inferred relationship. " +
                            "Keeping it only inside EmbeddedId (no @MapsId field generated).",
                    column.getName(), table.getName());
            return;
        }

        Relationship relationship = relationshipOpt.get();

        String targetEntity = NamingConverter.toPascalCase(stripSchema(relationship.getTargetTable()));
        String fieldName = NamingConverter.toCamelCase(column.getName().replaceAll("(?i)_id$", ""));
        String embeddedIdFieldName = NamingConverter.toCamelCase(column.getName());

        if (generatedFieldNames.contains(fieldName)) {
            log.warn("⚠️ Skipping composite relationship field '{}': already generated", fieldName);
            return;
        }
        generatedFieldNames.add(fieldName);

        boolean requiredFk = isNotNullColumn(column);

        builder.append("    @MapsId(\"").append(embeddedIdFieldName).append("\")\n");
        builder.append("    @ManyToOne(fetch = FetchType.LAZY)\n");
        builder.append("    @JoinColumn(name = \"").append(column.getName()).append("\"");

        String targetColumn = relationship.getTargetColumn();
        if (shouldIncludeReferencedColumn(targetColumn)) {
            builder.append(", referencedColumnName = \"").append(targetColumn).append("\"");
        }

        if (requiredFk) {
            builder.append(", nullable = false");
        }

        builder.append(")\n");

        builder.append("    private ").append(targetEntity).append(" ").append(fieldName).append(";\n\n");
    }

    private Optional<Relationship> findRelationshipForColumn(Table table, Column column) {
        if (table == null || column == null || table.getRelationships() == null) {
            return Optional.empty();
        }

        return table.getRelationships().stream()
                .filter(rel -> Objects.equals(stripSchema(rel.getSourceTable()), stripSchema(table.getName())))
                .filter(rel -> Objects.equals(rel.getSourceColumn(), column.getName()))
                .findFirst();
    }

    private boolean looksLikeForeignKeyColumn(Column column) {
        if (column == null || column.getName() == null) {
            return false;
        }
        return column.getName().toLowerCase(Locale.ROOT).endsWith("_id");
    }

    private Optional<Relationship> inferCompositePkRelationship(Table sourceTable, Column sourceColumn) {
        if (sourceTable == null || sourceColumn == null || sourceColumn.getName() == null) {
            return Optional.empty();
        }

        // 1) Prefer explicit FK metadata if present (even if relationship resolver did not attach it)
        if (sourceColumn.getReferencedTable() != null && !sourceColumn.getReferencedTable().isBlank()) {
            Optional<Table> explicitTarget = findTargetTableInGeneratorMap(sourceColumn.getReferencedTable());

            Relationship rel = new Relationship();
            rel.setSourceTable(sourceTable.getName());
            rel.setSourceColumn(sourceColumn.getName());
            rel.setRelationshipType(Relationship.RelationshipType.MANYTOONE);
            rel.setOnDelete(sourceColumn.getOnDelete());
            rel.setOnUpdate(sourceColumn.getOnUpdate());

            if (explicitTarget.isPresent()) {
                Table targetTable = explicitTarget.get();
                String targetColumnName = resolveTargetColumnName(targetTable, sourceColumn.getReferencedColumn());

                rel.setTargetTable(targetTable.getName());
                rel.setTargetColumn(targetColumnName);

                log.info("🧩 Inferred composite PK relationship from explicit FK metadata (mapped target): {}.{} -> {}.{}",
                        rel.getSourceTable(), rel.getSourceColumn(), rel.getTargetTable(), rel.getTargetColumn());

                return Optional.of(rel);
            }

            // Fallback even if tableMap is not populated (e.g. direct unit test on createEntityContent)
            rel.setTargetTable(sourceColumn.getReferencedTable());
            String referencedColumn = sourceColumn.getReferencedColumn();
            rel.setTargetColumn((referencedColumn == null || referencedColumn.isBlank()) ? "id" : referencedColumn);

            log.info("🧩 Inferred composite PK relationship from explicit FK metadata (raw target): {}.{} -> {}.{}",
                    rel.getSourceTable(), rel.getSourceColumn(), rel.getTargetTable(), rel.getTargetColumn());

            return Optional.of(rel);
        }

        // 2) Fallback heuristic from column name (e.g. business_location_id -> business_location)
        if (!looksLikeForeignKeyColumn(sourceColumn)) {
            return Optional.empty();
        }

        Optional<Table> targetTableOpt = findTargetTableHeuristically(sourceTable, sourceColumn);
        if (targetTableOpt.isEmpty()) {
            return Optional.empty();
        }

        Table targetTable = targetTableOpt.get();
        String targetColumnName = firstPrimaryKeyColumnName(targetTable).orElse("id");

        Relationship rel = new Relationship();
        rel.setSourceTable(sourceTable.getName());
        rel.setSourceColumn(sourceColumn.getName());
        rel.setTargetTable(targetTable.getName());
        rel.setTargetColumn(targetColumnName);
        rel.setRelationshipType(Relationship.RelationshipType.MANYTOONE);
        rel.setOnDelete(sourceColumn.getOnDelete());
        rel.setOnUpdate(sourceColumn.getOnUpdate());

        log.info("🧩 Inferred composite PK relationship heuristically: {}.{} -> {}.{}",
                rel.getSourceTable(), rel.getSourceColumn(), rel.getTargetTable(), rel.getTargetColumn());

        return Optional.of(rel);
    }


    private Optional<Table> findTargetTableHeuristically(Table sourceTable, Column sourceColumn) {
        if (this.tableMap.isEmpty()) {
            return Optional.empty();
        }

        String columnName = sourceColumn.getName();
        String base = columnName.replaceFirst("(?i)_id$", "").trim();

        if (base.isBlank() || base.equalsIgnoreCase(columnName)) {
            return Optional.empty();
        }

        List<String> candidates = buildTargetTableNameCandidates(base);
        String sourceSchema = extractSchema(sourceTable != null ? sourceTable.getName() : null);

        // 1) Prefer same schema
        for (String candidate : candidates) {
            for (Table t : new LinkedHashSet<>(this.tableMap.values())) {
                if (t == null || t.getName() == null) {
                    continue;
                }

                String tSchema = extractSchema(t.getName());
                String tNameNoSchema = stripSchema(t.getName());

                if (sameSchema(sourceSchema, tSchema) && namesMatchLoosely(candidate, tNameNoSchema)) {
                    return Optional.of(t);
                }
            }
        }

        // 2) Any schema
        for (String candidate : candidates) {
            for (Table t : new LinkedHashSet<>(this.tableMap.values())) {
                if (t == null || t.getName() == null) {
                    continue;
                }

                String tNameNoSchema = stripSchema(t.getName());
                if (namesMatchLoosely(candidate, tNameNoSchema)) {
                    return Optional.of(t);
                }
            }
        }

        log.warn("⚠️ Could not infer target table for composite PK column '{}.{}' (candidates={})",
                sourceTable != null ? sourceTable.getName() : "null",
                sourceColumn.getName(),
                candidates);

        return Optional.empty();
    }

    private Optional<Table> findTargetTableInGeneratorMap(String referencedTableRaw) {
        if (referencedTableRaw == null || referencedTableRaw.isBlank() || this.tableMap.isEmpty()) {
            return Optional.empty();
        }

        String raw = referencedTableRaw.trim();

        // Exact key
        Table direct = this.tableMap.get(raw);
        if (direct != null) {
            return Optional.of(direct);
        }

        // Strip schema exact
        String rawNoSchema = stripSchema(raw);
        for (Table t : new LinkedHashSet<>(this.tableMap.values())) {
            if (t == null || t.getName() == null) {
                continue;
            }

            if (Objects.equals(stripSchema(t.getName()), rawNoSchema)) {
                return Optional.of(t);
            }
        }

        // Loose match
        for (Table t : new LinkedHashSet<>(this.tableMap.values())) {
            if (t == null || t.getName() == null) {
                continue;
            }

            if (namesMatchLoosely(rawNoSchema, stripSchema(t.getName()))) {
                return Optional.of(t);
            }
        }

        return Optional.empty();
    }

    private String resolveTargetColumnName(Table targetTable, String referencedColumnRaw) {
        if (targetTable == null) {
            return "id";
        }

        if (referencedColumnRaw != null && !referencedColumnRaw.isBlank()) {
            Optional<String> exact = targetTable.getColumns().stream()
                    .filter(Objects::nonNull)
                    .map(Column::getName)
                    .filter(Objects::nonNull)
                    .filter(c -> c.equalsIgnoreCase(referencedColumnRaw.trim()))
                    .findFirst();

            if (exact.isPresent()) {
                return exact.get();
            }
        }

        return firstPrimaryKeyColumnName(targetTable).orElse("id");
    }

    private boolean sameSchema(String s1, String s2) {
        return normalizeLooseName(s1).equals(normalizeLooseName(s2));
    }

    private boolean namesMatchLoosely(String a, String b) {
        return normalizeLooseName(a).equals(normalizeLooseName(b));
    }

    private String normalizeLooseName(String value) {
        if (value == null) {
            return "";
        }

        String v = unquoteIdentifier(value).trim().toLowerCase(Locale.ROOT);
        return v.replaceAll("[^a-z0-9]", "");
    }

    private String unquoteIdentifier(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\"", "");
    }


    private List<String> buildTargetTableNameCandidates(String baseName) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (baseName == null || baseName.isBlank()) {
            return new ArrayList<>();
        }

        String base = baseName.trim();
        candidates.add(base);
        candidates.add(base + "s");
        candidates.add(base + "es");

        if (base.endsWith("y") && base.length() > 1) {
            candidates.add(base.substring(0, base.length() - 1) + "ies");
        }

        return new ArrayList<>(candidates);
    }

    private Optional<String> firstPrimaryKeyColumnName(Table table) {
        if (table == null || table.getColumns() == null) {
            return Optional.empty();
        }

        return table.getColumns().stream()
                .filter(Objects::nonNull)
                .filter(Column::isPrimaryKey)
                .map(Column::getName)
                .filter(Objects::nonNull)
                .findFirst();
    }
    private String extractSchema(String rawTableName) {
        if (rawTableName == null) {
            return null;
        }

        String cleaned = unquoteIdentifier(rawTableName.trim());
        int dot = cleaned.lastIndexOf('.');

        if (dot <= 0) {
            return null;
        }

        return cleaned.substring(0, dot);
    }



    private String toSimpleJavaType(String javaType) {
        String type = javaType == null ? "" : javaType.trim();

        if (type.startsWith("java.lang.")) {
            return type.substring("java.lang.".length());
        }
        if (type.startsWith("java.time.")) {
            return type.substring("java.time.".length());
        }
        if (type.startsWith("java.math.")) {
            return type.substring("java.math.".length());
        }
        if (type.startsWith("java.util.")) {
            return type.substring("java.util.".length());
        }
        return type;
    }



    /**
     * Adds primary key annotations and generates the primary key field.
     *
     * @param builder the builder receiving generated content
     * @param column the primary key column metadata
     */
    private void addPrimaryKeyAnnotations(StringBuilder builder, Column column) {
        builder.append("    @Id\n");

        if (!column.isForeignKey()) {
            if (column.isIdentity()) {
                builder.append("    @GeneratedValue(strategy = GenerationType.IDENTITY)\n");
            } else if (shouldUseUuidGeneration(column)) {
                builder.append("    @GeneratedValue(strategy = GenerationType.UUID)\n");
            }
        }

        addColumnField(builder, column);
    }

    private boolean shouldUseUuidGeneration(Column column) {
        if (column == null) {
            return false;
        }

        if (!isUuidType(column.getJavaType())) {
            return false;
        }

        String defaultValue = column.getDefaultValue();
        if (defaultValue == null || defaultValue.isBlank()) {
            return false;
        }

        String normalizedDefaultValue = defaultValue.trim().toLowerCase(Locale.ROOT);
        return normalizedDefaultValue.contains("gen_random_uuid()");
    }


    /**
     * Generates an owning-side relationship field for a resolved foreign key column.
     * <p>
     * This method assumes that relationship-vs-scalar strategy has already been
     * decided by the caller. Its responsibility is limited to rendering the JPA
     * field for supported owning-side relationships.
     *
     * @param builder the builder receiving generated content
     * @param column the foreign key column
     * @param table the source table
     * @param generatedFieldNames already generated field names for duplicate protection
     */
    public void addRelationshipField(StringBuilder builder,
                                     Column column,
                                     Table table,
                                     Set<String> generatedFieldNames) {
        log.debug("Resolving owning-side relationship field for column '{}' in table '{}'",
                column.getName(),
                table.getName());

        Optional<Relationship> relationshipOpt = resolveOwningRelationshipForFieldGeneration(table, column);

        if (relationshipOpt.isEmpty()) {
            log.warn("No owning-side relationship found for FK column '{}.{}'. Falling back to unresolved scalar field.",
                    table.getName(),
                    column.getName());
            addUnresolvedForeignKeyScalarField(builder, column);
            return;
        }

        Relationship relationship = relationshipOpt.get();

        log.debug("Relationship details -> source='{}', target='{}', type={}, mappedBy='{}', joinTable='{}'",
                relationship.getSourceTable(),
                relationship.getTargetTable(),
                relationship.getRelationshipType(),
                relationship.getMappedBy(),
                relationship.getJoinTableName());

        if (relationship.getRelationshipType() == Relationship.RelationshipType.MANYTOMANY) {
            log.debug("Skipping MANYTOMANY generation for column '{}.{}' because it is handled elsewhere.",
                    table.getName(),
                    column.getName());
            return;
        }

        String targetEntity = NamingConverter.toPascalCase(stripSchema(relationship.getTargetTable()));
        String fieldName = resolveRelationFieldName(column.getName());

        if (generatedFieldNames.contains(fieldName)) {
            log.warn("Skipping relationship field '{}' because it has already been generated.", fieldName);
            return;
        }

        boolean requiredForeignKey = isNotNullColumn(column);

        switch (relationship.getRelationshipType()) {
            case ONETOONE -> {
                if (relationship.getMappedBy() != null) {
                    log.debug("Skipping ONETOONE field '{}' because it belongs to the inverse side (mappedBy='{}').",
                            fieldName,
                            relationship.getMappedBy());
                    return;
                }

                generatedFieldNames.add(fieldName);

                builder.append("    @OneToOne(fetch = FetchType.LAZY)\n");
                builder.append("    @JoinColumn(name = \"").append(column.getName()).append("\"");

                String targetColumn = relationship.getTargetColumn();
                if (shouldIncludeReferencedColumn(targetColumn)) {
                    builder.append(", referencedColumnName = \"").append(targetColumn).append("\"");
                }

                if (requiredForeignKey) {
                    builder.append(", nullable = false");
                }

                if (column.isUnique()) {
                    builder.append(", unique = true");
                }

                builder.append(")\n");

                builder.append("    private ")
                        .append(targetEntity)
                        .append(" ")
                        .append(fieldName)
                        .append(";\n\n");
            }

            case MANYTOONE -> {
                generatedFieldNames.add(fieldName);

                builder.append("    @ManyToOne(fetch = FetchType.LAZY)\n");
                builder.append("    @JoinColumn(name = \"").append(column.getName()).append("\"");

                String targetColumn = relationship.getTargetColumn();
                if (shouldIncludeReferencedColumn(targetColumn)) {
                    builder.append(", referencedColumnName = \"").append(targetColumn).append("\"");
                }

                if (requiredForeignKey) {
                    builder.append(", nullable = false");
                }

                builder.append(")\n");

                builder.append("    private ")
                        .append(targetEntity)
                        .append(" ")
                        .append(fieldName)
                        .append(";\n\n");
            }

            default -> {
                log.warn("Relationship type '{}' is not supported by addRelationshipField for column '{}.{}'. Falling back to unresolved scalar field.",
                        relationship.getRelationshipType(),
                        table.getName(),
                        column.getName());
                addUnresolvedForeignKeyScalarField(builder, column);
            }
        }
    }



    private boolean shouldIncludeReferencedColumn(String targetColumn) {
        if (targetColumn == null) {
            return false;
        }
        String tc = targetColumn.trim();
        if (tc.isEmpty()) {
            return false;
        }
        return !tc.equalsIgnoreCase("id");
    }

    private boolean isNotNullColumn(Column column) {
        try {
            var m = column.getClass().getMethod("isNullable");
            Object v = m.invoke(column);
            if (v instanceof Boolean b) {
                return !b;
            }
        } catch (Exception ignored) {
        }

        try {
            var m = column.getClass().getMethod("isNotNull");
            Object v = m.invoke(column);
            if (v instanceof Boolean b) {
                return b;
            }
        } catch (Exception ignored) {
        }

        try {
            var m = column.getClass().getMethod("getNullable");
            Object v = m.invoke(column);
            if (v instanceof Boolean b) {
                return !b;
            }
        } catch (Exception ignored) {
        }

        return false;
    }


    /**
     * Generates the owning side of a many-to-many relationship.
     *
     * @param builder the builder receiving generated content
     * @param relationship the many-to-many relationship metadata
     * @param generatedFieldNames already generated field names for duplicate protection
     */
    public void addManyToManyParentSide(StringBuilder builder,
                                        Relationship relationship,
                                        Set<String> generatedFieldNames) {
        String targetEntity = NamingConverter.toPascalCase(stripSchema(relationship.getTargetTable()));
        String fieldName = NamingConverter.toCamelCasePlural(stripSchema(relationship.getTargetTable()));

        if (generatedFieldNames.contains(fieldName)) {
            log.warn("⚠️ Skipping ManyToMany owning-side field '{}': already generated (likely duplicate)", fieldName);
            return;
        }
        generatedFieldNames.add(fieldName);

        String joinTableName = stripSchema(relationship.getJoinTableName());

        builder.append("    @ManyToMany(fetch = FetchType.LAZY)\n")
                .append("    @JoinTable(\n")
                .append("        name = \"").append(joinTableName).append("\",\n")
                .append("        joinColumns = @JoinColumn(name = \"").append(relationship.getSourceColumn()).append("\", nullable = false),\n")
                .append("        inverseJoinColumns = @JoinColumn(name = \"").append(relationship.getInverseJoinColumn()).append("\", nullable = false)\n")
                .append("    )\n");



        builder.append("    private List<").append(targetEntity).append("> ")
                .append(fieldName).append(" = new ArrayList<>();\n\n");
    }

    /**
     * Generates inverse-side collection fields for many-to-many relationships.
     *
     * @param builder the builder receiving generated content
     * @param relationship the inverse relationship metadata
     * @param generatedFieldNames already generated field names for duplicate protection
     */
    public void addInverseRelationshipField(StringBuilder builder,
                                            Relationship relationship,
                                            Set<String> generatedFieldNames) {
        String targetEntity = NamingConverter.toPascalCase(stripSchema(relationship.getTargetTable()));
        String rawTargetTableName = stripSchema(relationship.getTargetTable());

        String fieldName = NamingConverter.toCamelCasePlural(rawTargetTableName);

        if (generatedFieldNames.contains(fieldName)) {
            log.warn("⚠️ Skipping inverse field '{}': already generated (probably duplicate relationship)", fieldName);
            return;
        }

        generatedFieldNames.add(fieldName);

        log.debug("🔄 Creating inverse relationship field for table '{}' -> '{}', type: {}, mappedBy: '{}', fieldName: '{}'",
                relationship.getSourceTable(),
                relationship.getTargetTable(),
                relationship.getRelationshipType(),
                relationship.getMappedBy(),
                fieldName);

        if (relationship.getRelationshipType() != Relationship.RelationshipType.MANYTOMANY) {
            log.warn("⚠️ Relationship type {} is not handled here for inverse relationships.",
                    relationship.getRelationshipType());
            return;
        }

        if (relationship.getMappedBy() == null || relationship.getMappedBy().isBlank()) {
            log.warn("⚠️ Skipping ManyToMany inverse field '{}' because mappedBy is missing.", fieldName);
            return;
        }

        builder.append("    @ManyToMany(mappedBy = \"").append(relationship.getMappedBy())
                .append("\", fetch = FetchType.LAZY)\n");



        builder.append("    private List<").append(targetEntity).append("> ")
                .append(fieldName).append(" = new ArrayList<>();\n\n");
    }


    /**
     * Generates a simple column field with JPA annotations.
     * Handles JSON/JSONB columns explicitly for Hibernate and preserves NUMERIC/DECIMAL precision metadata.
     *
     * @param builder the builder receiving generated content
     * @param column the column metadata
     */
    private void addColumnField(StringBuilder builder, Column column) {
        boolean isForeignKey = column.isForeignKey();
        String columnName = GeneratorSupport.unquoteIdentifier(column.getName());
        String javaType = resolveJavaType(column);
        String sqlType = normalizeSqlType(column.getSqlType());

        boolean isJsonColumn = TypeMapper.isJsonType(column);
        boolean isGeneratedStoredColumn = column.getGeneratedAs() != null && !column.getGeneratedAs().isBlank();

        if (shouldUseCreationTimestamp(column)) {
            builder.append("    @CreationTimestamp\n");
        } else if (shouldUseUpdateTimestamp(column)) {
            builder.append("    @UpdateTimestamp\n");
        }

        if (!isForeignKey) {
            if (isJsonColumn) {
                builder.append("    @JdbcTypeCode(SqlTypes.JSON)\n");

                builder.append("    @Column(name = \"")
                        .append(columnName)
                        .append("\", columnDefinition = \"")
                        .append(TypeMapper.getJsonColumnDefinition(column))
                        .append("\"");

                if (column.isUnique()) {
                    builder.append(", unique = true");
                }

                if (!column.isNullable()) {
                    builder.append(", nullable = false");
                }

                builder.append(")\n");
            } else {
                builder.append("    @Column(name = \"").append(columnName).append("\"");

                appendCharacterColumnMetadata(builder, column, sqlType);
                appendNumericColumnMetadata(builder, column, sqlType);

                if (column.isUnique()) {
                    builder.append(", unique = true");
                }

                if (!column.isNullable()) {
                    builder.append(", nullable = false");
                }

                if (isCreationTimestampColumnName(columnName)) {
                    builder.append(", updatable = false");
                }

                if (isGeneratedStoredColumn) {
                    builder.append(", insertable = false, updatable = false");
                }

                builder.append(")\n");
            }
        }

        String cleanedType = toSimpleJavaType(javaType);


        builder.append("    private ")
                .append(cleanedType)
                .append(" ")
                .append(column.getFieldName())
                .append(";\n\n");
    }



    private boolean isLocalDateTimeType(String javaType) {
        return "java.time.LocalDateTime".equals(javaType) || "LocalDateTime".equals(javaType);
    }

    private boolean isCreationTimestampColumnName(String columnName) {
        if (columnName == null) {
            return false;
        }
        String name = columnName.trim().toLowerCase(Locale.ROOT);
        return name.equals("created_at") || name.equals("date_created");
    }

    private boolean isUpdateTimestampColumnName(String columnName) {
        if (columnName == null) {
            return false;
        }
        String name = columnName.trim().toLowerCase(Locale.ROOT);
        return name.equals("updated_at") || name.equals("last_updated");
    }

    private String normalizeSqlType(String sqlType) {
        if (sqlType == null) {
            return "";
        }
        return sqlType.trim().toUpperCase();
    }


    public void writeToFile(String filePath, String content) throws IOException {
        Objects.requireNonNull(filePath, "File path cannot be null");
        Objects.requireNonNull(content, "Content cannot be null");

        Path path = Paths.get(filePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);

        log.debug("File written successfully: {}", filePath);
    }

    /**
     * Converts parsed tables into generator entity metadata.
     * <p>
     * This method creates:
     * <ul>
     *     <li>composite id fields for tables with embedded keys</li>
     *     <li>scalar fields for normal columns</li>
     *     <li>owning-side relation fields for resolved FK relationships</li>
     *     <li>inverse relation fields for ONETOONE relationships</li>
     *     <li>synthetic MANYTOMANY collection fields from pure join tables</li>
     * </ul>
     *
     * @param tables the parsed tables
     * @return the generated entity metadata list
     */
    public List<Entity> toEntities(List<Table> tables) {
        List<Entity> result = new ArrayList<>();

        for (Table table : tables) {
            if (table.isPureJoinTable()) {
                continue;
            }

            Entity entity = new Entity();
            entity.setName(resolveEntityName(table));
            entity.setCompositeKey(table.getCompositeKey());

            List<Field> fields = new ArrayList<>();
            Set<String> generatedFieldNames = new HashSet<>();

            addCompositePrimaryKeyMetadataField(table, fields, generatedFieldNames);
            addColumnAndOwningRelationFields(table, fields, generatedFieldNames);
            addInverseRelationshipFields(table, fields, generatedFieldNames);
            addSyntheticManyToManyFields(table, fields, generatedFieldNames);

            entity.setFields(fields);
            result.add(entity);
        }

        return result;
    }

    /**
     * Adds the composite id metadata field for tables that use an embedded key.
     *
     * @param table the source table
     * @param fields the generated fields
     * @param generatedFieldNames already generated field names
     */
    private void addCompositePrimaryKeyMetadataField(
            Table table,
            List<Field> fields,
            Set<String> generatedFieldNames
    ) {
        if (!hasCompositePrimaryKey(table)) {
            return;
        }

        Field compositeIdField = Field.builder()
                .name("id")
                .type(getEmbeddedIdTypeName(table))
                .primaryKey(true)
                .foreignKey(false)
                .unique(false)
                .nullable(false)
                .columnName("id")
                .build();

        if (generatedFieldNames.add(compositeIdField.getName())) {
            fields.add(compositeIdField);
        }
    }

    /**
     * Resolves the entity class name for the given table.
     *
     * @param table the source table
     * @return the generated entity name
     */
    private String resolveEntityName(Table table) {
        String rawTableName = table.getName();
        String tableName = rawTableName != null && rawTableName.contains(".")
                ? rawTableName.substring(rawTableName.indexOf('.') + 1)
                : rawTableName;

        return NamingConverter.toPascalCase(tableName);
    }

    /**
     * Adds scalar fields and owning-side relation fields to the target field list.
     *
     * @param table the source table
     * @param fields the generated fields
     * @param generatedFieldNames already generated field names
     */
    private void addColumnAndOwningRelationFields(
            Table table,
            List<Field> fields,
            Set<String> generatedFieldNames
    ) {
        boolean compositePrimaryKey = hasCompositePrimaryKey(table);
        List<Column> compositePrimaryKeyColumns = compositePrimaryKey
                ? getPrimaryKeyColumns(table)
                : Collections.emptyList();

        for (Column column : table.getColumns()) {
            if (compositePrimaryKey && isCompositeKeyColumn(column, compositePrimaryKeyColumns)) {
                addCompositePrimaryKeyOwningRelationField(table, column, fields, generatedFieldNames);
                continue;
            }

            if (!column.isForeignKey()) {
                Field scalarField = createScalarField(column);

                if (generatedFieldNames.add(scalarField.getName())) {
                    fields.add(scalarField);
                }
                continue;
            }

            ForeignKeyGenerationStrategy strategy = resolveForeignKeyGenerationStrategy(table, column);

            switch (strategy) {
                case RELATION, COMPOSITE_MAPS_ID_RELATION -> {
                    Optional<Relationship> relationship = resolveOwningRelationshipForFieldGeneration(table, column);

                    if (relationship.isPresent()) {
                        Field relationField = createOwningRelationField(column, relationship.get());

                        if (generatedFieldNames.add(relationField.getName())) {
                            fields.add(relationField);
                        }
                    } else {
                        Field scalarField = createScalarField(column);

                        if (generatedFieldNames.add(scalarField.getName())) {
                            fields.add(scalarField);
                        }
                    }
                }

                case SCALAR, UNRESOLVED_SCALAR -> {
                    Field scalarField = createScalarField(column);

                    if (generatedFieldNames.add(scalarField.getName())) {
                        fields.add(scalarField);
                    }
                }
            }
        }
    }


    /**
     * Adds only the owning relation metadata for a composite primary key column.
     * <p>
     * The raw key values already live inside the generated embedded key field,
     * so direct scalar duplication must be avoided here.
     *
     * @param table the source table
     * @param column the composite primary key column
     * @param fields the generated fields
     * @param generatedFieldNames already generated field names
     */
    private void addCompositePrimaryKeyOwningRelationField(
            Table table,
            Column column,
            List<Field> fields,
            Set<String> generatedFieldNames
    ) {
        if (!column.isForeignKey()) {
            return;
        }

        ForeignKeyGenerationStrategy strategy = resolveForeignKeyGenerationStrategy(table, column);

        if (strategy != ForeignKeyGenerationStrategy.RELATION
                && strategy != ForeignKeyGenerationStrategy.COMPOSITE_MAPS_ID_RELATION) {
            return;
        }

        Optional<Relationship> relationship = resolveOwningRelationshipForFieldGeneration(table, column);

        if (relationship.isEmpty()) {
            return;
        }

        Field relationField = createOwningRelationField(column, relationship.get());

        if (generatedFieldNames.add(relationField.getName())) {
            fields.add(relationField);
        }
    }

    /**
     * Resolves the owning-side relationship used by field metadata generation.
     * Ensures that FK columns always produce a relation when possible.
     */
    private Optional<Relationship> resolveOwningRelationshipForFieldGeneration(Table table, Column column) {

        // 1. Try strict owning relationship
        Optional<Relationship> relationship = findOwningRelationship(table, column);
        if (relationship.isPresent()) {
            return relationship;
        }

        // 2. Try resolved relationships (non-owning filtered)
        relationship = findRelationshipForColumn(table, column)
                .filter(rel ->
                        rel.getMappedBy() == null &&
                                (rel.getRelationshipType() == Relationship.RelationshipType.MANYTOONE
                                        || rel.getRelationshipType() == Relationship.RelationshipType.ONETOONE)
                );

        if (relationship.isPresent()) {
            return relationship;
        }

        //  3. FORCE fallback inference for FK
        if (column.isForeignKey()) {
            Optional<Relationship> inferred = inferCompositePkRelationship(table, column);

            if (inferred.isPresent()) {
                log.debug("Fallback inferred relationship used for {}.{}", table.getName(), column.getName());
                return inferred;
            }
        }

        return Optional.empty();
    }


    /**
     * Finds the standard owning-side relationship for the given foreign key column.
     * <p>
     * This method only returns already resolved owning-side relationships and does
     * not apply fallback inference or strategy rules.
     *
     * @param table the source table
     * @param column the source foreign key column
     * @return the resolved owning-side relationship if present
     */
    private Optional<Relationship> findOwningRelationship(Table table, Column column) {
        if (table == null
                || column == null
                || !column.isForeignKey()
                || table.getRelationships() == null
                || table.getRelationships().isEmpty()) {
            return Optional.empty();
        }

        return table.getRelationships().stream()
                .filter(Objects::nonNull)
                .filter(relationship -> Objects.equals(relationship.getSourceTable(), table.getName()))
                .filter(relationship -> Objects.equals(relationship.getSourceColumn(), column.getName()))
                .filter(relationship -> relationship.getMappedBy() == null)
                .filter(relationship ->
                        relationship.getRelationshipType() == Relationship.RelationshipType.MANYTOONE
                                || relationship.getRelationshipType() == Relationship.RelationshipType.ONETOONE)
                .findFirst();
    }

    /**
     * Creates a scalar non-relationship field from a database column.
     *
     * @param column the source column
     * @return the generated scalar field
     */
    private Field createScalarField(Column column) {
        String columnName = GeneratorSupport.unquoteIdentifier(column.getName());

        return Field.builder()
                .name(column.getFieldName())
                .type(resolveJavaType(column))
                .primaryKey(column.isPrimaryKey())
                .foreignKey(column.isForeignKey())
                .unique(column.isUnique())
                .nullable(column.isNullable())
                .length(column.getLength())
                .columnName(columnName)
                .build();
    }


    /**
     * Creates an owning-side relation field from a foreign key column and a resolved relationship.
     *
     * @param column the foreign key column
     * @param relationship the resolved owning-side relationship
     * @return the generated relation field metadata
     */
    private Field createOwningRelationField(Column column, Relationship relationship) {
        String relationFieldName = resolveRelationFieldName(column.getName());
        String referencedEntity = NamingConverter.toPascalCase(stripSchema(relationship.getTargetTable()));
        Field.RelationKind relationKind = toRelationKind(relationship.getRelationshipType());

        return Field.builder()
                .name(relationFieldName)
                .type(referencedEntity)
                .primaryKey(false)
                .foreignKey(true)
                .unique(column.isUnique())
                .nullable(column.isNullable())
                .length(column.getLength())
                .columnName(column.getName())
                .referencedEntity(referencedEntity)
                .referencedColumn(relationship.getTargetColumn())
                .mappedBy(null)
                .cascade("ALL")
                .orphanRemoval(false)
                .relationKind(relationKind)
                .collection(false)
                .owningSide(true)
                .build();
    }

    /**
     * Resolves a relation field name from a foreign key column name.
     * Example:
     * company_status_id -> companyStatus
     * parent_company_id -> parentCompany
     *
     * @param columnName the physical FK column name
     * @return the generated relation field name
     */
    private String resolveRelationFieldName(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            return columnName;
        }

        String normalizedName = columnName.endsWith("_id")
                ? columnName.substring(0, columnName.length() - 3)
                : columnName;

        return NamingConverter.toCamelCase(normalizedName);
    }


    /**
     * Adds inverse-side fields derived from resolved relationships.
     *
     * @param table the source table
     * @param fields the generated fields
     * @param generatedFieldNames already generated field names
     */
    private void addInverseRelationshipFields(Table table,
                                              List<Field> fields,
                                              Set<String> generatedFieldNames) {
        for (Relationship relationship : table.getRelationships()) {
            if (relationship.getMappedBy() == null || relationship.getMappedBy().isBlank()) {
                continue;
            }

            if (shouldGenerateInverseRelationship(relationship)) {
                log.debug("Skipping inverse metadata field for sourceTable='{}', target='{}', mappedBy='{}' " +
                                "because the owning side will not be generated as a relation field.",
                        relationship.getSourceTable(),
                        relationship.getTargetTable(),
                        relationship.getMappedBy());
                continue;
            }

            if (relationship.getRelationshipType() == Relationship.RelationshipType.ONETOONE) {
                Field inverseOneToOneField = createInverseOneToOneField(relationship);

                if (generatedFieldNames.add(inverseOneToOneField.getName())) {
                    fields.add(inverseOneToOneField);
                }
            }
        }
    }


    /**
     * Creates an inverse one-to-one field.
     *
     * @param relationship the inverse relationship metadata
     * @return the generated inverse reference field
     */
    private Field createInverseOneToOneField(Relationship relationship) {
        String targetEntity = NamingConverter.toPascalCase(stripSchema(relationship.getTargetTable()));
        String fieldName = Character.toLowerCase(targetEntity.charAt(0)) + targetEntity.substring(1);

        return Field.builder()
                .name(fieldName)
                .type(targetEntity)
                .foreignKey(false)
                .referencedEntity(targetEntity)
                .mappedBy(relationship.getMappedBy())
                .cascade("ALL")
                .orphanRemoval(false)
                .relationKind(Field.RelationKind.ONE_TO_ONE)
                .collection(false)
                .owningSide(false)
                .build();
    }


    /**
     * Adds synthetic many-to-many fields defined on the table metadata.
     *
     * @param table the source table
     * @param fields the generated fields
     * @param generatedFieldNames already generated field names
     */
    private void addSyntheticManyToManyFields(Table table,
                                              List<Field> fields,
                                              Set<String> generatedFieldNames) {
        if (table.getManyToManyRelations() == null || table.getManyToManyRelations().isEmpty()) {
            return;
        }

        for (ManyToManyRelation relation : table.getManyToManyRelations()) {
            Field field = Field.builder()
                    .name(relation.getFieldName())
                    .type("List<" + relation.getTargetEntityName() + ">")
                    .foreignKey(false)
                    .referencedEntity(relation.getTargetEntityName())
                    .mappedBy(relation.getMappedBy())
                    .cascade("ALL")
                    .orphanRemoval(false)
                    .relationKind(Field.RelationKind.MANY_TO_MANY)
                    .collection(true)
                    .owningSide(relation.isOwningSide())
                    .joinTableName(relation.getJoinTableName())
                    .joinColumnName(relation.getJoinColumnName())
                    .inverseJoinColumnName(relation.getInverseJoinColumnName())
                    .build();

            if (generatedFieldNames.add(field.getName())) {
                fields.add(field);
            }
        }
    }

    /**
     * Converts the relationship model type to the field relation kind.
     *
     * @param relationshipType the relationship type from the resolver
     * @return the mapped field relation kind
     */
    private Field.RelationKind toRelationKind(Relationship.RelationshipType relationshipType) {
        return switch (relationshipType) {
            case ONETOONE -> Field.RelationKind.ONE_TO_ONE;
            case MANYTOONE -> Field.RelationKind.MANY_TO_ONE;
            case ONETOMANY -> Field.RelationKind.ONE_TO_MANY;
            case MANYTOMANY -> Field.RelationKind.MANY_TO_MANY;
        };
    }


    /**
     * Removes a schema prefix from a table name.
     *
     * @param tableName the raw table name
     * @return the schema-free table name
     */
    private String stripSchema(String tableName) {
        if (tableName == null) {
            return null;
        }

        return tableName.contains(".")
                ? tableName.substring(tableName.indexOf('.') + 1)
                : tableName;
    }

    /**
     * Generates a scalar fallback field for a foreign key column whose target
     * relationship could not be resolved to a generated entity.
     * <p>
     * This method only adds an explanatory TODO comment and delegates the actual
     * scalar field generation to the flat foreign key scalar renderer.
     *
     * @param builder the builder receiving the generated content
     * @param column the unresolved foreign key column
     */
    private void addUnresolvedForeignKeyScalarField(StringBuilder builder,
                                                    Column column) {
        String columnName = GeneratorSupport.unquoteIdentifier(column.getName());

        builder.append("    // TODO: Foreign key '")
                .append(columnName)
                .append("' was not resolved to a generated entity relationship.\n")
                .append("    // Keep it as a scalar field until the target entity becomes available.\n");

        addFlatForeignKeyScalarField(builder, column);
    }





    private boolean shouldUseCreationTimestamp(Column column) {
        if (column == null) {
            return false;
        }

        String javaType = column.getJavaType();
        if (!isLocalDateTimeType(javaType)) {
            return false;
        }

        String columnName = column.getName();
        if (!isCreationTimestampColumnName(columnName)) {
            return false;
        }

        String defaultValue = column.getDefaultValue();
        if (defaultValue == null || defaultValue.isBlank()) {
            return false;
        }

        String normalizedDefaultValue = defaultValue.trim().toLowerCase(Locale.ROOT);
        return normalizedDefaultValue.contains("now()")
                || normalizedDefaultValue.equals("current_timestamp")
                || normalizedDefaultValue.equals("localtimestamp");
    }

    /**
     * Determines if a column should use @UpdateTimestamp.
     */
    private boolean shouldUseUpdateTimestamp(Column column) {
        if (column == null) {
            return false;
        }

        return isLocalDateTimeType(column.getJavaType())
                && isUpdateTimestampColumnName(column.getName());
    }

    /**
     * Resolves the effective Java type for a column.
     *
     * @param column the source column
     * @return the effective Java type
     */
    private String resolveJavaType(Column column) {
        if (column == null) {
            return "";
        }

        return column.getJavaType() == null ? "" : column.getJavaType();
    }

    /**
     * Describes how a foreign key column should be generated in the entity model.
     */
    private enum ForeignKeyGenerationStrategy {
        SCALAR,
        RELATION,
        COMPOSITE_MAPS_ID_RELATION,
        UNRESOLVED_SCALAR
    }

    /**
     * Resolves the generation strategy for a foreign key column.
     * <p>
     * This method is the single decision point that determines whether a foreign key
     * should be generated as:
     * <ul>
     *     <li>a plain scalar id field</li>
     *     <li>a standard owning-side relation</li>
     *     <li>a composite-key {@code @MapsId} relation</li>
     *     <li>an unresolved scalar fallback</li>
     * </ul>
     *
     * @param table the source table
     * @param column the foreign key column to evaluate
     * @return the resolved generation strategy
     */
    private ForeignKeyGenerationStrategy resolveForeignKeyGenerationStrategy(Table table, Column column) {
        if (table == null || column == null || !column.isForeignKey()) {
            return ForeignKeyGenerationStrategy.SCALAR;
        }

        if (shouldGenerateCompositeMapsIdRelation(table, column)) {
            return ForeignKeyGenerationStrategy.COMPOSITE_MAPS_ID_RELATION;
        }

        Optional<Relationship> relationship = findRelationshipForColumn(table, column);

        if (relationship.isEmpty()) {
            return ForeignKeyGenerationStrategy.UNRESOLVED_SCALAR;
        }

        if (shouldKeepForeignKeyAsScalar(table, relationship.get())) {
            return ForeignKeyGenerationStrategy.SCALAR;
        }

        return ForeignKeyGenerationStrategy.RELATION;
    }

    /**
     * Checks whether the foreign key column should be generated as a composite-key
     * {@code @MapsId} relation.
     *
     * @param table the source table
     * @param column the foreign key column
     * @return true when the FK participates in a composite primary key and should
     *         become a {@code @MapsId} relation
     */
    private boolean shouldGenerateCompositeMapsIdRelation(Table table, Column column) {
        if (table == null || column == null) {
            return false;
        }

        if (!hasCompositePrimaryKey(table)) {
            return false;
        }

        List<Column> primaryKeyColumns = getPrimaryKeyColumns(table);

        if (!isCompositeKeyColumn(column, primaryKeyColumns)) {
            return false;
        }

        return findRelationshipForColumn(table, column).isPresent()
                || inferCompositePkRelationship(table, column).isPresent();
    }

    /**
     * Checks whether a resolved foreign key should remain a scalar field instead of
     * becoming a JPA relation.
     *
     * @param table the source table
     * @param relationship the resolved relationship metadata
     * @return true when the FK should remain scalar
     */
    private boolean shouldKeepForeignKeyAsScalar(Table table, Relationship relationship) {
        if (isI18nParentOrLanguageRelation(table, relationship)) {
            return false;
        }

        return isCrossSchemaRelationship(table, relationship);
    }


    /**
     * Checks whether the relationship is the typical i18n relation to a parent entity
     * or to the languages table.
     *
     * @param table the source table
     * @param relationship the resolved relationship metadata
     * @return true when the FK should remain a relation because it is part of the i18n model
     */
    private boolean isI18nParentOrLanguageRelation(Table table, Relationship relationship) {
        if (table == null || relationship == null || table.getName() == null || relationship.getTargetTable() == null) {
            return false;
        }

        String sourceTableName = stripSchema(table.getName()).toLowerCase(Locale.ROOT);
        if (!sourceTableName.contains("i18n")) {
            return false;
        }

        String targetTableName = stripSchema(relationship.getTargetTable()).toLowerCase(Locale.ROOT);

        if (targetTableName.equals("language") || targetTableName.equals("languages")) {
            return true;
        }

        String baseTableName = sourceTableName
                .replace("_i18n", "")
                .replace("i18n", "")
                .trim();

        return !baseTableName.isBlank() && targetTableName.equals(baseTableName);
    }

    /**
     * Checks whether the relationship points to a table in a different schema.
     *
     * @param table the source table
     * @param relationship the resolved relationship metadata
     * @return true when source and target tables belong to different schemas
     */
    private boolean isCrossSchemaRelationship(Table table, Relationship relationship) {
        if (table == null || relationship == null) {
            return false;
        }

        String sourceSchema = extractSchema(table.getName());
        String targetSchema = extractSchema(relationship.getTargetTable());

        if (sourceSchema == null || targetSchema == null) {
            return false;
        }

        return !sameSchema(sourceSchema, targetSchema);
    }


}