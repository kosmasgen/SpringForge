package com.sqldomaingen.generator;

import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.Table;
import com.sqldomaingen.model.Relationship;
import com.sqldomaingen.model.UniqueConstraint;
import com.sqldomaingen.util.GeneratorSupport;
import com.sqldomaingen.util.JavaTypeSupport;
import com.sqldomaingen.util.NamingConverter;
import com.sqldomaingen.util.PackageResolver;
import com.sqldomaingen.util.PrimaryKeySupport;
import lombok.extern.log4j.Log4j2;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Generates Spring Data JPA repositories for parsed tables.
 */
@Log4j2
public class RepositoryGenerator {

    public void generateRepositories(
            List<Table> tables,
            String outputDir,
            String basePackage,
            boolean overwrite,
            Set<String> lookupTables
    ) {
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(outputDir, "outputDir must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        Path repositoriesDir = GeneratorSupport.ensureDirectory(
                PackageResolver.resolvePath(outputDir, basePackage, "repository")
        );

        for (Table table : tables) {
            String entityName = NamingConverter.toPascalCase(
                    GeneratorSupport.normalizeTableName(table.getName())
            );
            String repositoryCode = generateRepositoryForTable(table, basePackage, lookupTables);
            Path filePath = repositoriesDir.resolve(entityName + "Repository.java");
            GeneratorSupport.writeFile(filePath, repositoryCode, overwrite);
        }

        log.debug("Repository generation completed. Output directory: {}", repositoriesDir.toAbsolutePath());
    }



    /**
     * Generates repository code for a single table.
     *
     * @param table table metadata
     * @param basePackage base Java package
     * @param lookupTables configured lookup table names
     * @return generated repository source code
     */
    public String generateRepositoryForTable(Table table, String basePackage, Set<String> lookupTables) {
        Objects.requireNonNull(table, "table must not be null");
        Objects.requireNonNull(basePackage, "basePackage must not be null");

        boolean lookupTable = isLookupTable(table, lookupTables);

        String entityName = NamingConverter.toPascalCase(
                GeneratorSupport.normalizeTableName(table.getName())
        );
        String repositoryName = entityName + "Repository";
        String repositoryPackage = PackageResolver.resolvePackageName(basePackage, "repository");
        String entityPackage = PackageResolver.resolvePackageName(basePackage, "entity");

        TypeRef primaryKeyTypeRef = resolvePrimaryKeyTypeRef(table, entityName, entityPackage);
        Set<String> importLines = collectRepositoryImports(entityPackage, entityName, primaryKeyTypeRef, table, lookupTable);

        StringBuilder builder = new StringBuilder();

        appendPackage(builder, repositoryPackage);
        appendImports(builder, importLines);
        appendRepositoryJavaDoc(builder, entityName, !lookupTable && hasCustomRepositoryMethods(table));
        appendRepositoryDeclaration(builder, repositoryName, entityName, primaryKeyTypeRef.simpleName());

        if (!lookupTable) {
            appendExistsByMethodsForUniqueColumns(builder, table);
            appendExistsByMethodsForCompositeUniqueConstraints(builder, table);
        }

        builder.append("}\n");

        return builder.toString();
    }


    /**
     * Resolves the primary key type reference for the repository.
     *
     * @param table table metadata
     * @param entityName entity simple name
     * @param entityPackage entity package name
     * @return primary key type reference
     */
    private TypeRef resolvePrimaryKeyTypeRef(Table table, String entityName, String entityPackage) {
        PrimaryKeySupport.TypeRef primaryKeyTypeRef =
                PrimaryKeySupport.resolvePrimaryKeyTypeRef(table, entityName, entityPackage);

        return new TypeRef(primaryKeyTypeRef.simpleName(), primaryKeyTypeRef.importLine());
    }

    /**
     * Returns whether the repository will contain custom query methods.
     *
     * @param table table metadata
     * @return true when at least one eligible custom query method exists
     */
    private boolean hasCustomRepositoryMethods(Table table) {
        return !getEligibleInlineUniqueColumns(table).isEmpty()
                || !getEligibleCompositeUniqueConstraints(table).isEmpty();
    }

    /**
     * Returns whether the table has at least one restrict relationship
     * that can produce a repository existsBy method.
     *
     * @param table table metadata
     * @return true when at least one eligible restrict relationship exists
     */
    private boolean hasRestrictRelationshipMethods(Table table) {
        if (table.getRelationships() == null || table.getRelationships().isEmpty()) {
            return false;
        }

        return table.getRelationships().stream()
                .filter(Objects::nonNull)
                .filter(relationship -> relationship.getSourceColumn() != null)
                .filter(this::isRestrictRelationship)
                .map(relationship -> findColumnByName(table, relationship.getSourceColumn()))
                .filter(Objects::nonNull)
                .anyMatch(column -> !isUnsupportedForDerivedQuery(column.getJavaType()));
    }

    /**
     * Returns all eligible inline unique columns.
     *
     * @param table table metadata
     * @return eligible inline unique columns
     */
    private List<Column> getEligibleInlineUniqueColumns(Table table) {
        return table.getColumns().stream()
                .filter(column -> !isNotEligibleInlineUniqueColumn(column))
                .toList();
    }

    /**
     * Returns all eligible composite unique constraints.
     *
     * @param table table metadata
     * @return eligible composite unique constraints
     */
    private List<UniqueConstraint> getEligibleCompositeUniqueConstraints(Table table) {
        if (table.getUniqueConstraints() == null || table.getUniqueConstraints().isEmpty()) {
            return List.of();
        }

        return table.getUniqueConstraints().stream()
                .filter(uniqueConstraint -> !isNotEligibleCompositeUniqueConstraint(table, uniqueConstraint))
                .toList();
    }

    /**
     * Appends existsBy methods for columns marked as inline unique.
     *
     * @param builder target source builder
     * @param table table metadata
     */
    private void appendExistsByMethodsForUniqueColumns(StringBuilder builder, Table table) {
        Objects.requireNonNull(builder, "builder must not be null");
        Objects.requireNonNull(table, "table must not be null");

        for (Column column : getEligibleInlineUniqueColumns(table)) {
            appendExistsByMethodForSingleColumn(builder, table, column);
        }
    }

    /**
     * Returns whether the given column is NOT eligible for inline existsBy method generation.
     *
     * @param column table column
     * @return true when the column is NOT eligible
     */
    private boolean isNotEligibleInlineUniqueColumn(Column column) {
        return column == null
                || !column.isUnique()
                || isUnsupportedForDerivedQuery(column.getJavaType());
    }

    /**
     * Appends a single existsBy method for one unique column.
     *
     * @param builder target source builder
     * @param table table metadata
     * @param column unique column
     */
    private void appendExistsByMethodForSingleColumn(StringBuilder builder, Table table, Column column) {
        String parameterName = toParameterName(column.getName());

        appendExistsMethodJavaDoc(builder, table, List.of(parameterName));

        builder.append("    boolean ")
                .append(buildExistsByMethodName(table, List.of(column.getName())))
                .append("(")
                .append(JavaTypeSupport.resolveSimpleType(column.getJavaType()))
                .append(" ")
                .append(parameterName)
                .append(");\n");
    }

    /**
     * Appends the package declaration.
     *
     * @param builder target source builder
     * @param repositoryPackage repository package name
     */
    private void appendPackage(StringBuilder builder, String repositoryPackage) {
        builder.append("package ").append(repositoryPackage).append(";\n\n");
    }

    /**
     * Appends import lines in deterministic order.
     *
     * @param builder target source builder
     * @param importLines ordered import lines
     */
    private void appendImports(StringBuilder builder, Set<String> importLines) {
        for (String importLine : importLines) {
            builder.append(importLine).append("\n");
        }
        builder.append("\n");
    }

    /**
     * Appends the repository interface declaration.
     *
     * @param builder target source builder
     * @param repositoryName repository simple name
     * @param entityName entity simple name
     * @param primaryKeyType primary key simple type name
     */
    private void appendRepositoryDeclaration(
            StringBuilder builder,
            String repositoryName,
            String entityName,
            String primaryKeyType
    ) {
        builder.append("@Repository\n");
        builder.append("public interface ").append(repositoryName)
                .append(" extends JpaRepository<")
                .append(entityName)
                .append(", ")
                .append(primaryKeyType)
                .append("> {\n");
    }

    /**
     * Collects all import lines required by the generated repository.
     *
     * @param entityPackage entity package name
     * @param entityName entity simple name
     * @param primaryKeyTypeRef resolved primary key type reference
     * @param table table metadata
     * @return ordered unique import lines
     */
    private Set<String> collectRepositoryImports(
            String entityPackage,
            String entityName,
            TypeRef primaryKeyTypeRef,
            Table table,
            boolean lookupTable
    ) {
        Set<String> importLines = new LinkedHashSet<>();

        importLines.add("import " + entityPackage + "." + entityName + ";");
        importLines.add("import org.springframework.data.jpa.repository.JpaRepository;");
        importLines.add("import org.springframework.stereotype.Repository;");

        if (primaryKeyTypeRef.importLine() != null && !primaryKeyTypeRef.importLine().isBlank()) {
            importLines.add(primaryKeyTypeRef.importLine());
        }

        if (!lookupTable) {
            addUniqueMethodImports(importLines, table);
        }

        return importLines;
    }

    /**
     * Returns whether the table is configured as a lookup table.
     *
     * @param table table metadata
     * @param lookupTables configured lookup table names
     * @return true when the table is configured as lookup
     */
    private boolean isLookupTable(Table table, Set<String> lookupTables) {
        if (table == null || lookupTables == null || lookupTables.isEmpty()) {
            return false;
        }

        String normalizedTableName = GeneratorSupport.normalizeTableName(table.getName());

        return lookupTables.stream()
                .filter(Objects::nonNull)
                .anyMatch(lookupTable -> normalizedTableName.equalsIgnoreCase(
                        GeneratorSupport.normalizeTableName(lookupTable)
                ));
    }

    /**
     * Adds import lines required by generated unique-query methods.
     *
     * @param importLines ordered import collection
     * @param table table metadata
     */
    private void addUniqueMethodImports(Set<String> importLines, Table table) {
        addInlineUniqueMethodImports(importLines, table);
        addCompositeUniqueMethodImports(importLines, table);
    }

    /**
     * Adds import lines required by ON DELETE RESTRICT or ON UPDATE RESTRICT relationship methods.
     *
     * @param importLines ordered import collection
     * @param table table metadata
     */
    private void addRestrictRelationshipMethodImports(
            Set<String> importLines,
            Table table
    ) {
        if (table.getRelationships() == null || table.getRelationships().isEmpty()) {
            return;
        }

        Set<String> processedColumns = new LinkedHashSet<>();

        for (Relationship relationship : table.getRelationships()) {
            if (relationship == null
                    || relationship.getSourceColumn() == null) {
                continue;
            }

            if (!isRestrictRelationship(relationship)) {
                continue;
            }

            String columnName = relationship.getSourceColumn();

            if (!processedColumns.add(columnName.toLowerCase())) {
                continue;
            }

            Column column = findColumnByName(table, columnName);

            if (column == null) {
                continue;
            }

            addDerivedQueryTypeImport(importLines, column.getJavaType());
        }
    }

    /**
     * Returns whether the relationship has ON DELETE RESTRICT or ON UPDATE RESTRICT.
     *
     * @param relationship relationship metadata
     * @return true when delete or update action is RESTRICT
     */
    private boolean isRestrictRelationship(Relationship relationship) {
        return "RESTRICT".equalsIgnoreCase(relationship.getOnDelete())
                || "RESTRICT".equalsIgnoreCase(relationship.getOnUpdate());
    }

    /**
     * Adds import lines required by inline unique methods.
     *
     * @param importLines ordered import collection
     * @param table table metadata
     */
    private void addInlineUniqueMethodImports(Set<String> importLines, Table table) {
        for (Column column : getEligibleInlineUniqueColumns(table)) {
            addDerivedQueryTypeImport(importLines, column.getJavaType());
        }
    }

    /**
     * Adds import lines required by composite unique methods.
     *
     * @param importLines ordered import collection
     * @param table table metadata
     */
    private void addCompositeUniqueMethodImports(Set<String> importLines, Table table) {
        for (UniqueConstraint uniqueConstraint : getEligibleCompositeUniqueConstraints(table)) {
            for (String columnName : uniqueConstraint.getColumns()) {
                Column column = findColumnByName(table, columnName);
                if (column == null) {
                    continue;
                }

                addDerivedQueryTypeImport(importLines, column.getJavaType());
            }
        }
    }

    /**
     * Adds a single derived-query type import when needed.
     *
     * @param importLines ordered import collection
     * @param javaType full or simple Java type
     */
    private void addDerivedQueryTypeImport(Set<String> importLines, String javaType) {
        if (isUnsupportedForDerivedQuery(javaType)) {
            return;
        }

        String importLine = JavaTypeSupport.resolveImportLine(javaType);
        if (importLine != null && !importLine.isBlank()) {
            importLines.add(importLine);
        }
    }

    /**
     * Appends existsBy methods for composite unique constraints.
     *
     * @param builder target source builder
     * @param table table metadata
     */
    private void appendExistsByMethodsForCompositeUniqueConstraints(StringBuilder builder, Table table) {
        Objects.requireNonNull(builder, "builder must not be null");
        Objects.requireNonNull(table, "table must not be null");

        for (UniqueConstraint uniqueConstraint : getEligibleCompositeUniqueConstraints(table)) {
            String methodSignature = buildCompositeExistsByMethodSignature(table, uniqueConstraint);
            if (methodSignature == null || methodSignature.isBlank()) {
                continue;
            }

            List<String> parameterNames = uniqueConstraint.getColumns().stream()
                    .map(this::toParameterName)
                    .toList();

            appendExistsMethodJavaDoc(builder, table, parameterNames);

            builder.append("    ")
                    .append(methodSignature)
                    .append("\n");
        }
    }

    /**
     * Returns whether the given composite unique constraint is NOT eligible
     * for repository method generation.
     *
     * @param table table metadata
     * @param uniqueConstraint unique constraint metadata
     * @return true when the constraint is NOT eligible
     */
    private boolean isNotEligibleCompositeUniqueConstraint(Table table, UniqueConstraint uniqueConstraint) {
        return !isValidCompositeUniqueConstraint(uniqueConstraint)
                || containsUnsupportedDerivedQueryType(table, uniqueConstraint);
    }

    /**
     * Builds an existsBy method signature for a composite unique constraint.
     *
     * @param table table metadata
     * @param uniqueConstraint composite unique constraint
     * @return repository method signature or null when generation is not possible
     */
    private String buildCompositeExistsByMethodSignature(Table table, UniqueConstraint uniqueConstraint) {
        StringBuilder parameters = new StringBuilder();
        boolean first = true;

        for (String columnName : uniqueConstraint.getColumns()) {
            Column column = findColumnByName(table, columnName);
            if (column == null) {
                return null;
            }

            String javaType = column.getJavaType();
            if (isUnsupportedForDerivedQuery(javaType)) {
                return null;
            }

            if (!first) {
                parameters.append(", ");
            }

            parameters.append(JavaTypeSupport.resolveSimpleType(javaType))
                    .append(" ")
                    .append(toParameterName(column.getName()));

            first = false;
        }

        if (parameters.isEmpty()) {
            return null;
        }

        return "boolean "
                + buildExistsByMethodName(table, uniqueConstraint.getColumns())
                + "("
                + parameters
                + ");";
    }

    /**
     * Returns true when the given composite unique constraint can produce a repository method.
     *
     * @param uniqueConstraint unique constraint metadata
     * @return true when the constraint is non-null and contains at least two columns
     */
    private boolean isValidCompositeUniqueConstraint(UniqueConstraint uniqueConstraint) {
        return uniqueConstraint != null
                && uniqueConstraint.getColumns() != null
                && uniqueConstraint.getColumns().size() >= 2;
    }

    /**
     * Returns true when at least one participating column uses an unsupported derived-query type.
     *
     * @param table table metadata
     * @param uniqueConstraint composite unique constraint
     * @return true when method generation should be skipped
     */
    private boolean containsUnsupportedDerivedQueryType(Table table, UniqueConstraint uniqueConstraint) {
        for (String columnName : uniqueConstraint.getColumns()) {
            Column column = findColumnByName(table, columnName);
            if (column == null || isUnsupportedForDerivedQuery(column.getJavaType())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Builds an existsBy method name from one or more column names.
     *
     * @param table table metadata
     * @param columnNames participating column names
     * @return generated method name
     */
    private String buildExistsByMethodName(Table table, List<String> columnNames) {
        StringBuilder methodName = new StringBuilder("existsBy");
        boolean first = true;

        for (String columnName : columnNames) {
            if (columnName == null || columnName.isBlank()) {
                continue;
            }

            if (!first) {
                methodName.append("And");
            }

            methodName.append(resolvePropertyPath(table, columnName));
            first = false;
        }

        return methodName.toString();
    }

    /**
     * Converts a column name to a Java parameter name.
     *
     * @param columnName database column name
     * @return camelCase parameter name
     */
    private String toParameterName(String columnName) {
        return NamingConverter.toCamelCase(columnName);
    }

    /**
     * Finds a column in the table by its name.
     *
     * @param table table metadata
     * @param columnName target column name
     * @return matching column or null when not found
     */
    private Column findColumnByName(Table table, String columnName) {
        if (columnName == null || columnName.isBlank()) {
            return null;
        }

        return table.getColumns().stream()
                .filter(Objects::nonNull)
                .filter(column -> columnName.equalsIgnoreCase(column.getName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Determines whether a Java type is unsupported for Spring Data derived query methods.
     *
     * @param javaType full or simple Java type
     * @return true when method generation should be skipped
     */
    private boolean isUnsupportedForDerivedQuery(String javaType) {
        if (javaType == null || javaType.isBlank()) {
            return true;
        }

        String normalizedType = javaType.trim();

        return normalizedType.equals("byte[]")
                || normalizedType.contains("List")
                || normalizedType.contains("Map")
                || normalizedType.contains("Set");
    }

    /**
     * Appends Javadoc for repository interface.
     *
     * @param builder target source builder
     * @param entityName entity simple name
     * @param hasCustomMethods whether repository contains custom methods
     */
    private void appendRepositoryJavaDoc(StringBuilder builder, String entityName, boolean hasCustomMethods) {
        builder.append("/**\n");

        if (hasCustomMethods) {
            builder.append(" * Repository interface for managing {@link ")
                    .append(entityName)
                    .append("} entities and custom persistence operations.\n");
        } else {
            builder.append(" * Repository interface for managing {@link ")
                    .append(entityName)
                    .append("} entities.\n");
        }

        builder.append(" */\n");
    }

    /**
     * Appends Javadoc for an existsBy repository method.
     *
     * @param builder target source builder
     * @param table table metadata
     * @param parameterNames repository method parameter names
     */
    private void appendExistsMethodJavaDoc(
            StringBuilder builder,
            Table table,
            List<String> parameterNames
    ) {
        String entityName = NamingConverter.toPascalCase(
                GeneratorSupport.normalizeTableName(table.getName())
        );
        String lowerDisplayLabel = NamingConverter.toLogLabel(entityName);

        builder.append("\n    /**\n");
        builder.append("     * Checks whether ")
                .append(NamingConverter.resolveIndefiniteArticle(lowerDisplayLabel))
                .append(" ")
                .append(lowerDisplayLabel)
                .append(" exists with the given ")
                .append(buildParameterDescription(parameterNames))
                .append(".\n");
        builder.append("     *\n");

        for (String parameterName : parameterNames) {
            String readableParameterName = buildRepositoryParameterDescription(
                    lowerDisplayLabel,
                    parameterName
            );

            builder.append("     * @param ")
                    .append(parameterName)
                    .append(" ")
                    .append(readableParameterName)
                    .append("\n");
        }

        builder.append("     * @return {@code true} if a matching entity exists; otherwise {@code false}\n");
        builder.append("     */\n");
    }

    /**
     * Builds a readable JavaDoc description for a repository method parameter.
     *
     * @param lowerDisplayLabel lowercase entity display label
     * @param parameterName repository method parameter name
     * @return readable parameter description
     */
    private String buildRepositoryParameterDescription(String lowerDisplayLabel, String parameterName) {
        String readableParameterName = NamingConverter.toLogLabel(parameterName)
                .replaceAll("\\bid\\b", "identifier");

        if (readableParameterName.contains("identifier")) {
            return readableParameterName;
        }

        return lowerDisplayLabel + " " + readableParameterName;
    }

    /**
     * Builds a human-readable parameter description for Javadoc.
     *
     * @param parameters parameter names
     * @return formatted description
     */
    private String buildParameterDescription(List<String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }

        if (parameters.size() == 1) {
            return parameters.getFirst();
        }

        if (parameters.size() == 2) {
            return parameters.get(0) + " and " + parameters.get(1);
        }

        return String.join(", ", parameters.subList(0, parameters.size() - 1))
                + " and "
                + parameters.getLast();
    }

    /**
     * Resolves the derived-query property path for a column.
     *
     * @param table table metadata
     * @param columnName column name
     * @return repository property path
     */
    private String resolvePropertyPath(Table table, String columnName) {
        if (PrimaryKeySupport.hasCompositePrimaryKey(table) && isPrimaryKeyColumn(table, columnName)) {
            return "Id" + NamingConverter.toPascalCase(
                    NamingConverter.toCamelCase(columnName)
            );
        }

        return NamingConverter.toPascalCase(
                NamingConverter.toCamelCase(columnName)
        );
    }

    /**
     * Returns whether the given column is part of the table primary key.
     *
     * @param table table metadata
     * @param columnName column name
     * @return true when the column belongs to the primary key
     */
    private boolean isPrimaryKeyColumn(Table table, String columnName) {
        if (columnName == null || columnName.isBlank()) {
            return false;
        }

        return PrimaryKeySupport.getPrimaryKeyColumns(table).stream()
                .anyMatch(primaryKeyColumn -> columnName.equalsIgnoreCase(primaryKeyColumn.getName()));
    }

    /**
     * Holds a resolved type name and its optional import line.
     *
     * @param simpleName simple type name
     * @param importLine optional import line
     */
    private record TypeRef(String simpleName, String importLine) {
    }

    /**
     * Appends existsBy methods for ON DELETE RESTRICT or ON UPDATE RESTRICT relationships.
     *
     * @param builder target source builder
     * @param table table metadata
     */
    private void appendExistsByMethodsForRestrictRelationships(
            StringBuilder builder,
            Table table
    ) {
        if (table.getRelationships() == null || table.getRelationships().isEmpty()) {
            return;
        }

        Set<String> generatedMethodNames = collectExistingExistsByMethodNames(table);

        for (Relationship relationship : table.getRelationships()) {
            if (relationship == null
                    || relationship.getSourceColumn() == null) {
                continue;
            }

            if (!isRestrictRelationship(relationship)) {
                continue;
            }

            Column sourceColumn = findColumnByName(table, relationship.getSourceColumn());

            if (sourceColumn == null || isUnsupportedForDerivedQuery(sourceColumn.getJavaType())) {
                continue;
            }

            String methodName = buildExistsByMethodName(table, List.of(sourceColumn.getName()));

            if (!generatedMethodNames.add(methodName)) {
                continue;
            }

            appendExistsByMethodForSingleColumn(builder, table, sourceColumn);
        }
    }

    /**
     * Collects existing existsBy method names generated from unique definitions.
     *
     * @param table table metadata
     * @return existing existsBy method names
     */
    private Set<String> collectExistingExistsByMethodNames(Table table) {
        Set<String> methodNames = new LinkedHashSet<>();

        for (Column column : getEligibleInlineUniqueColumns(table)) {
            methodNames.add(buildExistsByMethodName(table, List.of(column.getName())));
        }

        for (UniqueConstraint uniqueConstraint : getEligibleCompositeUniqueConstraints(table)) {
            methodNames.add(buildExistsByMethodName(table, uniqueConstraint.getColumns()));
        }

        return methodNames;
    }
}