package com.sqldomaingen.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents a generated entity field.
 * <p>
 * A field may be a simple scalar column, an owning-side relationship,
 * an inverse-side relationship, or a synthetic many-to-many collection.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Field {

    /**
     * The Java field name.
     */
    private String name;

    /**
     * The Java field type.
     * Examples:
     * {@code String}, {@code UUID}, {@code Company}, {@code List<Profession>}
     */
    private String type;

    /**
     * Indicates whether this field represents a primary key.
     */
    private boolean primaryKey;

    /**
     * Indicates whether this field originates from a foreign key column.
     */
    private boolean foreignKey;

    /**
     * Indicates whether the underlying column is unique.
     */
    private boolean unique;

    /**
     * Indicates whether the underlying column is nullable.
     */
    @Builder.Default
    private boolean nullable = true;

    /**
     * The column length, when applicable.
     */
    private Integer length;

    /**
     * The physical database column name.
     */
    private String columnName;

    /**
     * The referenced database column name for owning-side relationships.
     */
    private String referencedColumn;

    /**
     * The referenced entity class name.
     */
    private String referencedEntity;

    /**
     * The mappedBy value for inverse-side relationships.
     */
    private String mappedBy;

    /**
     * The cascade strategy to be rendered.
     */
    private String cascade;

    /**
     * Indicates whether orphanRemoval should be rendered.
     */
    private boolean orphanRemoval;

    /**
     * The relationship kind of this field.
     */
    private RelationKind relationKind;

    /**
     * Indicates whether this field represents a collection.
     */
    private boolean collection;

    /**
     * Indicates whether this field is the owning side of a relationship.
     */
    private boolean owningSide;

    /**
     * The physical join table name for many-to-many relationships.
     */
    private String joinTableName;

    /**
     * The join column name used by the owning side.
     */
    private String joinColumnName;

    /**
     * The inverse join column name used by the owning side.
     */
    private String inverseJoinColumnName;

    /**
     * Returns true when this field represents any JPA relationship.
     *
     * @return true if the field is a relationship field
     */
    public boolean isRelationship() {
        return relationKind != null;
    }



    /**
     * Enumerates the supported relationship kinds for generated fields.
     */
    public enum RelationKind {
        ONE_TO_ONE,
        MANY_TO_ONE,
        ONE_TO_MANY,
        MANY_TO_MANY
    }
}