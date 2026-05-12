package com.sqldomaingen;

import com.sqldomaingen.model.Relationship;
import com.sqldomaingen.model.Relationship.RelationshipType;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Log4j2
class RelationshipTest {

    @Test
    void testRelationshipSettersAndGetters() {
        log.info("Running test: testRelationshipSettersAndGetters");

        Relationship relationship = createRelationshipFixture();

        log.info("Relationship initialized: {}", relationship);

        assertEquals("orders", relationship.getSourceTable());
        assertEquals("customer_id", relationship.getSourceColumn());
        assertEquals("customers", relationship.getTargetTable());
        assertEquals("id", relationship.getTargetColumn());
        assertEquals(RelationshipType.MANYTOONE, relationship.getRelationshipType());
        assertEquals("CASCADE", relationship.getOnUpdate());
        assertEquals("SET NULL", relationship.getOnDelete());
        assertEquals("order_customers", relationship.getJoinTableName());
        assertEquals("customer_id", relationship.getInverseJoinColumn());
        assertEquals("customer", relationship.getMappedBy());
    }

    /**
     * Creates a relationship fixture for testing setters and getters.
     *
     * @return populated relationship
     */
    private Relationship createRelationshipFixture() {
        Relationship relationship = new Relationship();

        relationship.setSourceTable("orders");
        relationship.setSourceColumn("customer_id");
        relationship.setTargetTable("customers");
        relationship.setTargetColumn("id");
        relationship.setRelationshipType(RelationshipType.MANYTOONE);
        relationship.setOnUpdate("CASCADE");
        relationship.setOnDelete("SET NULL");
        relationship.setJoinTableName("order_customers");
        relationship.setInverseJoinColumn("customer_id");
        relationship.setMappedBy("customer");

        return relationship;
    }

    @Test
    void testRelationshipToString() {
        log.info("Running test: testRelationshipToString");

        Relationship relationship = createRelationshipToStringFixture();

        String expected = buildExpectedRelationshipToString();

        log.info("Expected: {}", expected);
        log.info("Actual: {}", relationship);

        assertEquals(
                expected,
                relationship.toString(),
                "Relationship toString should match the expected format."
        );
    }

    /**
     * Builds the expected relationship toString value.
     *
     * @return expected relationship string
     */
    private String buildExpectedRelationshipToString() {
        return "Relationship(sourceColumn=customer_id, targetColumn=id, sourceTable=orders, " +
                "targetTable=customers, onUpdate=RESTRICT, onDelete=CASCADE, joinTableName=order_customers, " +
                "inverseJoinColumn=customer_id, mappedBy=customer, relationshipType=ONETOMANY)";
    }

    /**
     * Creates a relationship fixture for toString validation.
     *
     * @return populated relationship
     */
    private Relationship createRelationshipToStringFixture() {
        Relationship relationship = new Relationship();

        relationship.setSourceTable("orders");
        relationship.setSourceColumn("customer_id");
        relationship.setTargetTable("customers");
        relationship.setTargetColumn("id");
        relationship.setRelationshipType(RelationshipType.ONETOMANY);
        relationship.setOnUpdate("RESTRICT");
        relationship.setOnDelete("CASCADE");
        relationship.setJoinTableName("order_customers");
        relationship.setInverseJoinColumn("customer_id");
        relationship.setMappedBy("customer");

        return relationship;
    }

    @Test
    void testDefaultValues() {
        log.info("Running test: testDefaultValues");

        Relationship relationship = new Relationship();

        log.info("Default Relationship object: {}", relationship);

        assertNull(relationship.getSourceTable(), "Source table should be null.");
        assertNull(relationship.getSourceColumn(), "Source column should be null.");
        assertNull(relationship.getTargetTable(), "Target table should be null.");
        assertNull(relationship.getTargetColumn(), "Target column should be null.");
        assertNull(relationship.getRelationshipType(), "Relationship type should be null.");
        assertNull(relationship.getOnUpdate(), "OnUpdate action should be null.");
        assertNull(relationship.getOnDelete(), "OnDelete action should be null.");
        assertNull(relationship.getJoinTableName(), "Join table name should be null.");
        assertNull(relationship.getInverseJoinColumn(), "Inverse join column should be null.");
        assertNull(relationship.getMappedBy(), "MappedBy should be null.");
    }

    @Test
    void testOneToOneRelationship() {
        log.info("Running test: testOneToOneRelationship");

        Relationship relationship = new Relationship();
        relationship.setSourceTable("users");
        relationship.setSourceColumn("id");
        relationship.setTargetTable("profiles");
        relationship.setTargetColumn("user_id");
        relationship.setRelationshipType(RelationshipType.ONETOONE);
        relationship.setOnUpdate("CASCADE");
        relationship.setOnDelete("SET NULL");

        log.info(" Created Relationship: {}", relationship);

        assertEquals("users", relationship.getSourceTable());
        assertEquals("id", relationship.getSourceColumn());
        assertEquals("profiles", relationship.getTargetTable());
        assertEquals("user_id", relationship.getTargetColumn());
        assertEquals(RelationshipType.ONETOONE, relationship.getRelationshipType());
        assertEquals("CASCADE", relationship.getOnUpdate());
        assertEquals("SET NULL", relationship.getOnDelete());
        assertNull(relationship.getJoinTableName(), "Join table name should be null for one-to-one.");
        assertNull(relationship.getInverseJoinColumn(), "Inverse join column should be null for one-to-one.");
        assertNull(relationship.getMappedBy(), "MappedBy should be null for one-to-one.");
    }

    @Test
    void testManyToOneRelationship() {
        log.info("Running test: testManyToOneRelationship");

        Relationship relationship = new Relationship();
        relationship.setSourceTable("orders");
        relationship.setSourceColumn("customer_id");
        relationship.setTargetTable("customers");
        relationship.setTargetColumn("id");
        relationship.setRelationshipType(RelationshipType.MANYTOONE);
        relationship.setOnUpdate("RESTRICT");
        relationship.setOnDelete("CASCADE");

        log.info(" Created Relationship: {}", relationship);

        assertEquals("orders", relationship.getSourceTable());
        assertEquals("customer_id", relationship.getSourceColumn());
        assertEquals("customers", relationship.getTargetTable());
        assertEquals("id", relationship.getTargetColumn());
        assertEquals(RelationshipType.MANYTOONE, relationship.getRelationshipType());
        assertEquals("RESTRICT", relationship.getOnUpdate());
        assertEquals("CASCADE", relationship.getOnDelete());
        assertNull(relationship.getJoinTableName(), "Join table name should be null for many-to-one.");
        assertNull(relationship.getInverseJoinColumn(), "Inverse join column should be null for many-to-one.");
        assertNull(relationship.getMappedBy(), "MappedBy should be null for many-to-one.");
    }

    @Test
    void testOneToManyRelationship() {
        log.info(" Running test: testOneToManyRelationship");

        Relationship relationship = new Relationship();
        relationship.setSourceTable("customers");
        relationship.setSourceColumn("id");
        relationship.setTargetTable("orders");
        relationship.setTargetColumn("customer_id");
        relationship.setRelationshipType(RelationshipType.ONETOMANY);
        relationship.setOnUpdate("NO ACTION");
        relationship.setOnDelete("SET DEFAULT");
        relationship.setMappedBy("customer");

        log.info("Created Relationship: {}", relationship);

        assertEquals("customers", relationship.getSourceTable());
        assertEquals("id", relationship.getSourceColumn());
        assertEquals("orders", relationship.getTargetTable());
        assertEquals("customer_id", relationship.getTargetColumn());
        assertEquals(RelationshipType.ONETOMANY, relationship.getRelationshipType());
        assertEquals("NO ACTION", relationship.getOnUpdate());
        assertEquals("SET DEFAULT", relationship.getOnDelete());
        assertEquals("customer", relationship.getMappedBy());
        assertNull(relationship.getJoinTableName(), "Join table name should be null for one-to-many.");
        assertNull(relationship.getInverseJoinColumn(), "Inverse join column should be null for one-to-many.");
    }


    @Test
    void testManyToManyRelationship() {
        log.info("Running test: testManyToManyRelationship");

        Relationship relationship = createManyToManyRelationshipFixture();

        log.info("Created Relationship: {}", relationship);

        assertEquals("students", relationship.getSourceTable());
        assertEquals("id", relationship.getSourceColumn());
        assertEquals("courses", relationship.getTargetTable());
        assertEquals("id", relationship.getTargetColumn());
        assertEquals(RelationshipType.MANYTOMANY, relationship.getRelationshipType());
        assertEquals("student_courses", relationship.getJoinTableName());
        assertEquals("course_id", relationship.getInverseJoinColumn());
        assertEquals("CASCADE", relationship.getOnUpdate());
        assertEquals("CASCADE", relationship.getOnDelete());
        assertNull(relationship.getMappedBy(), "MappedBy should be null for owning many-to-many.");
    }

    /**
     * Creates a many-to-many relationship fixture for getter validation.
     *
     * @return populated many-to-many relationship
     */
    private Relationship createManyToManyRelationshipFixture() {
        Relationship relationship = new Relationship();

        relationship.setSourceTable("students");
        relationship.setSourceColumn("id");
        relationship.setTargetTable("courses");
        relationship.setTargetColumn("id");
        relationship.setRelationshipType(RelationshipType.MANYTOMANY);
        relationship.setJoinTableName("student_courses");
        relationship.setInverseJoinColumn("course_id");
        relationship.setOnUpdate("CASCADE");
        relationship.setOnDelete("CASCADE");

        return relationship;
    }



    @Test
    void testManyToManyRelationshipWithMappedBy() {
        log.info("Running test: testManyToManyRelationshipWithMappedBy");

        Relationship relationship = createInverseManyToManyRelationshipFixture();

        log.info("Created inverse many-to-many Relationship: {}", relationship);

        assertEquals("courses", relationship.getSourceTable());
        assertEquals("id", relationship.getSourceColumn());
        assertEquals("students", relationship.getTargetTable());
        assertEquals("id", relationship.getTargetColumn());
        assertEquals(RelationshipType.MANYTOMANY, relationship.getRelationshipType());
        assertEquals("student_courses", relationship.getJoinTableName());
        assertEquals("student_id", relationship.getInverseJoinColumn());
        assertEquals("courses", relationship.getMappedBy());
        assertEquals("CASCADE", relationship.getOnUpdate());
        assertEquals("CASCADE", relationship.getOnDelete());
    }

    /**
     * Creates an inverse many-to-many relationship fixture.
     *
     * @return populated inverse many-to-many relationship
     */
    private Relationship createInverseManyToManyRelationshipFixture() {
        Relationship relationship = new Relationship();

        relationship.setSourceTable("courses");
        relationship.setSourceColumn("id");
        relationship.setTargetTable("students");
        relationship.setTargetColumn("id");
        relationship.setRelationshipType(RelationshipType.MANYTOMANY);
        relationship.setJoinTableName("student_courses");
        relationship.setInverseJoinColumn("student_id");
        relationship.setMappedBy("courses");
        relationship.setOnUpdate("CASCADE");
        relationship.setOnDelete("CASCADE");

        return relationship;
    }

}