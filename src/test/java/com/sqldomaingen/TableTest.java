package com.sqldomaingen;

import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.Table;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

@Log4j2
public class TableTest {

    @Test
    public void testAddDecimalColumnToEventAssignmentTable() {
        log.info("Testing addColumn with DECIMAL column in 'event_assignment' table...");

        Table table = createEventAssignmentTableWithDecimalColumn();

        log.info("Final table: {}", table);

        // Assertions
        Assertions.assertEquals(5, table.getColumns().size());

        Column decimalCol = table.getColumns().stream()
                .filter(c -> "workload_percentage".equals(c.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("DECIMAL column not found"));

        Assertions.assertEquals("DECIMAL", decimalCol.getSqlType());
        Assertions.assertEquals(5, decimalCol.getPrecision());
        Assertions.assertEquals(2, decimalCol.getScale());
        Assertions.assertFalse(decimalCol.isNullable());
    }

    /**
     * Creates a test table with a DECIMAL workload column.
     *
     * @return populated test table
     */
    private Table createEventAssignmentTableWithDecimalColumn() {
        Table table = new Table();
        table.setName("event_assignment");

        Column id = new Column();
        id.setName("assignment_id");
        id.setSqlType("SERIAL");
        id.setPrimaryKey(true);
        id.setNullable(false);

        Column eventId = new Column();
        eventId.setName("event_id");
        eventId.setSqlType("INT");
        eventId.setNullable(false);

        Column userId = new Column();
        userId.setName("user_id");
        userId.setSqlType("INT");
        userId.setNullable(false);

        Column workload = new Column();
        workload.setName("workload_percentage");
        workload.setSqlType("DECIMAL");
        workload.setPrecision(5);
        workload.setScale(2);
        workload.setNullable(false);

        Column note = new Column();
        note.setName("note");
        note.setSqlType("TEXT");
        note.setNullable(true);

        table.addColumn(id);
        table.addColumn(eventId);
        table.addColumn(userId);
        table.addColumn(workload);
        table.addColumn(note);

        return table;
    }


    @Test
    public void testAddConstraints() {
        log.info("Testing addConstraints with current schema...");


        Table table = new Table();
        table.setName("user");


        Column userId = new Column();
        userId.setName("user_id");
        userId.setSqlType("SERIAL");
        userId.setPrimaryKey(true);


        Column departmentId = new Column();
        departmentId.setName("department_id");
        departmentId.setSqlType("INT");


        table.addColumn(userId);
        table.addColumn(departmentId);

        // table-level constraint (FOREIGN KEY)
        table.addConstraints(List.of("FOREIGN KEY (department_id) REFERENCES department(department_id)"));

        // Logging
        log.info("Columns: {}", table.getColumns());
        log.info("Constraints: {}", table.getConstraints());

        // Assertions
        Assertions.assertEquals(2, table.getColumns().size());
        Assertions.assertEquals("user_id", table.getColumns().getFirst().getName());
        Assertions.assertTrue(table.getColumns().getFirst().isPrimaryKey());

        Assertions.assertEquals(1, table.getConstraints().size());
        Assertions.assertTrue(table.getConstraints().getFirst().contains("FOREIGN KEY"));
    }


    @Test
    void testParseCreateDepartmentTable_ToStringValidation() {
        log.info("Testing Table.toString with parsed 'department' table structure...");

        Table table = createDepartmentTableFixture();

        String expected = "Table{name='department', columns=[" +
                "Column(name=department_id, sqlType=SERIAL, length=0, isIdentity=false, identityGeneration=null, sequenceName=null, constraints=[], defaultValue=null, primaryKey=true, unique=false, manyToMany=false, nullable=false, isDefaultExpression=null, defaultExpression=null, checkConstraint=null, targetTable=null, joinTableName=null, inverseJoinColumn=null, isRelationship=false, onUpdate=null, onDelete=null, generatedAs=null, javaType=null, foreignKey=false, formattedName=null, referencedTable=null, referencedColumn=null, precision=0, scale=0, mappedBy=null, fieldName=null, primaryKeyConstraintName=null, foreignKeyConstraintName=null), " +
                "Column(name=name, sqlType=VARCHAR, length=100, isIdentity=false, identityGeneration=null, sequenceName=null, constraints=[], defaultValue=null, primaryKey=false, unique=false, manyToMany=false, nullable=false, isDefaultExpression=null, defaultExpression=null, checkConstraint=null, targetTable=null, joinTableName=null, inverseJoinColumn=null, isRelationship=false, onUpdate=null, onDelete=null, generatedAs=null, javaType=null, foreignKey=false, formattedName=null, referencedTable=null, referencedColumn=null, precision=0, scale=0, mappedBy=null, fieldName=null, primaryKeyConstraintName=null, foreignKeyConstraintName=null), " +
                "Column(name=description, sqlType=TEXT, length=0, isIdentity=false, identityGeneration=null, sequenceName=null, constraints=[], defaultValue=null, primaryKey=false, unique=false, manyToMany=false, nullable=true, isDefaultExpression=null, defaultExpression=null, checkConstraint=null, targetTable=null, joinTableName=null, inverseJoinColumn=null, isRelationship=false, onUpdate=null, onDelete=null, generatedAs=null, javaType=null, foreignKey=false, formattedName=null, referencedTable=null, referencedColumn=null, precision=0, scale=0, mappedBy=null, fieldName=null, primaryKeyConstraintName=null, foreignKeyConstraintName=null), " +
                "Column(name=parent_dept_id, sqlType=INT, length=0, isIdentity=false, identityGeneration=null, sequenceName=null, constraints=[], defaultValue=null, primaryKey=false, unique=false, manyToMany=false, nullable=true, isDefaultExpression=null, defaultExpression=null, checkConstraint=null, targetTable=null, joinTableName=null, inverseJoinColumn=null, isRelationship=false, onUpdate=null, onDelete=null, generatedAs=null, javaType=null, foreignKey=false, formattedName=null, referencedTable=null, referencedColumn=null, precision=0, scale=0, mappedBy=null, fieldName=null, primaryKeyConstraintName=null, foreignKeyConstraintName=null), " +
                "Column(name=created_at, sqlType=TIMESTAMP, length=0, isIdentity=false, identityGeneration=null, sequenceName=null, constraints=[], defaultValue=CURRENT_TIMESTAMP, primaryKey=false, unique=false, manyToMany=false, nullable=true, isDefaultExpression=null, defaultExpression=null, checkConstraint=null, targetTable=null, joinTableName=null, inverseJoinColumn=null, isRelationship=false, onUpdate=null, onDelete=null, generatedAs=null, javaType=null, foreignKey=false, formattedName=null, referencedTable=null, referencedColumn=null, precision=0, scale=0, mappedBy=null, fieldName=null, primaryKeyConstraintName=null, foreignKeyConstraintName=null), " +
                "Column(name=updated_at, sqlType=TIMESTAMP, length=0, isIdentity=false, identityGeneration=null, sequenceName=null, constraints=[], defaultValue=CURRENT_TIMESTAMP, primaryKey=false, unique=false, manyToMany=false, nullable=true, isDefaultExpression=null, defaultExpression=null, checkConstraint=null, targetTable=null, joinTableName=null, inverseJoinColumn=null, isRelationship=false, onUpdate=null, onDelete=null, generatedAs=null, javaType=null, foreignKey=false, formattedName=null, referencedTable=null, referencedColumn=null, precision=0, scale=0, mappedBy=null, fieldName=null, primaryKeyConstraintName=null, foreignKeyConstraintName=null)" +
                "], constraints=[PRIMARY KEY (department_id), FOREIGN KEY (parent_dept_id) REFERENCES department(department_id)], relationships=[], manyToManyRelations=[], pureJoinTable=false, indexes=[]}";

        log.info("Table state: {}", table);

        Assertions.assertEquals(expected, table.toString(), "Table toString should match the expected format.");
    }

    /**
     * Creates a department table fixture for toString validation.
     *
     * @return populated department table
     */
    private Table createDepartmentTableFixture() {
        Table table = new Table();
        table.setName("department");

        Column idColumn = new Column();
        idColumn.setName("department_id");
        idColumn.setSqlType("SERIAL");
        idColumn.setPrimaryKey(true);
        idColumn.setNullable(false);

        Column nameColumn = new Column();
        nameColumn.setName("name");
        nameColumn.setSqlType("VARCHAR");
        nameColumn.setLength(100);
        nameColumn.setNullable(false);

        Column descriptionColumn = new Column();
        descriptionColumn.setName("description");
        descriptionColumn.setSqlType("TEXT");
        descriptionColumn.setNullable(true);

        Column parentDeptIdColumn = new Column();
        parentDeptIdColumn.setName("parent_dept_id");
        parentDeptIdColumn.setSqlType("INT");
        parentDeptIdColumn.setNullable(true);

        Column createdAtColumn = new Column();
        createdAtColumn.setName("created_at");
        createdAtColumn.setSqlType("TIMESTAMP");
        createdAtColumn.setDefaultValue("CURRENT_TIMESTAMP");
        createdAtColumn.setNullable(true);

        Column updatedAtColumn = new Column();
        updatedAtColumn.setName("updated_at");
        updatedAtColumn.setSqlType("TIMESTAMP");
        updatedAtColumn.setDefaultValue("CURRENT_TIMESTAMP");
        updatedAtColumn.setNullable(true);

        table.addColumn(idColumn);
        table.addColumn(nameColumn);
        table.addColumn(descriptionColumn);
        table.addColumn(parentDeptIdColumn);
        table.addColumn(createdAtColumn);
        table.addColumn(updatedAtColumn);

        table.setConstraints(Arrays.asList(
                "PRIMARY KEY (department_id)",
                "FOREIGN KEY (parent_dept_id) REFERENCES department(department_id)"
        ));

        return table;
    }


    @Test
    public void testRecurringPatternTable_WithForeignKeyColumn() {
        log.info("Testing parsed structure of 'recurring_pattern' table...");

        Table table = new Table();
        table.setName("recurring_pattern");

        Column id = new Column();
        id.setName("pattern_id");
        id.setSqlType("SERIAL");
        id.setPrimaryKey(true);
        id.setNullable(false);

        Column recurrence = new Column();
        recurrence.setName("recurrence_rule");
        recurrence.setSqlType("VARCHAR");
        recurrence.setLength(255);
        recurrence.setNullable(true);

        Column eventId = new Column();
        eventId.setName("event_id");
        eventId.setSqlType("INT");
        eventId.setNullable(true);
        eventId.setForeignKey(true);
        eventId.setReferencedTable("event");
        eventId.setReferencedColumn("event_id");

        table.addColumn(id);
        table.addColumn(recurrence);
        table.addColumn(eventId);

        table.setConstraints(List.of(
                "PRIMARY KEY (pattern_id)",
                "FOREIGN KEY (event_id) REFERENCES event(event_id)"
        ));

        log.info(" Recurring Pattern table:\n{}", table);

        Assertions.assertEquals(3, table.getColumns().size());
        Column fk = table.getColumns().get(2);
        Assertions.assertTrue(fk.isForeignKey());
        Assertions.assertEquals("event", fk.getReferencedTable());
        Assertions.assertEquals("event_id", fk.getReferencedColumn());
    }

    @Test
    public void testDepartmentTableStructure_UnaffectedByTrigger() {
        log.info("Testing 'department' table structure – ignoring trigger effects...");

        Table table = new Table();
        table.setName("department");

        Column id = new Column();
        id.setName("department_id");
        id.setSqlType("SERIAL");
        id.setPrimaryKey(true);
        id.setNullable(false);

        Column name = new Column();
        name.setName("name");
        name.setSqlType("VARCHAR");
        name.setLength(100);
        name.setNullable(false);

        Column updatedAt = new Column();
        updatedAt.setName("updated_at");
        updatedAt.setSqlType("TIMESTAMP");
        updatedAt.setDefaultValue("CURRENT_TIMESTAMP");
        updatedAt.setNullable(true);

        table.addColumn(id);
        table.addColumn(name);
        table.addColumn(updatedAt);

        table.setConstraints(List.of("PRIMARY KEY (department_id)"));

        log.info("Department table (static structure):\n{}", table);

        Assertions.assertEquals(3, table.getColumns().size());
        Assertions.assertEquals("updated_at", table.getColumns().get(2).getName());
    }

    @Test
    public void testEmployeeDepartmentTable_WithCompositePrimaryKeyAndFKs() {
        log.info("Testing 'employee_department' table with composite PK and two FKs...");

        Table table = new Table();
        table.setName("employee_department");

        Column employeeId = new Column();
        employeeId.setName("employee_id");
        employeeId.setSqlType("INT");
        employeeId.setNullable(false);
        employeeId.setPrimaryKey(true);
        employeeId.setForeignKey(true);
        employeeId.setReferencedTable("employee");
        employeeId.setReferencedColumn("id");

        Column departmentId = new Column();
        departmentId.setName("department_id");
        departmentId.setSqlType("INT");
        departmentId.setNullable(false);
        departmentId.setPrimaryKey(true);
        departmentId.setForeignKey(true);
        departmentId.setReferencedTable("department");
        departmentId.setReferencedColumn("id");

        Column assignedAt = new Column();
        assignedAt.setName("assigned_at");
        assignedAt.setSqlType("TIMESTAMP");
        assignedAt.setDefaultValue("CURRENT_TIMESTAMP");
        assignedAt.setNullable(true);

        Column assignedBy = new Column();
        assignedBy.setName("assigned_by");
        assignedBy.setSqlType("VARCHAR");
        assignedBy.setLength(100);
        assignedBy.setNullable(true);

        table.addColumn(employeeId);
        table.addColumn(departmentId);
        table.addColumn(assignedAt);
        table.addColumn(assignedBy);

        table.setConstraints(List.of(
                "PRIMARY KEY (employee_id, department_id)",
                "FOREIGN KEY (employee_id) REFERENCES employee(id)",
                "FOREIGN KEY (department_id) REFERENCES department(id)"
        ));

        log.info(" Employee-Department table:\n{}", table);

        Assertions.assertEquals(4, table.getColumns().size());
        Assertions.assertEquals(3, table.getConstraints().size());


        long pkCount = table.getColumns().stream().filter(Column::isPrimaryKey).count();
        Assertions.assertEquals(2, pkCount, "There should be 2 primary key columns");


        Assertions.assertTrue(employeeId.isForeignKey());
        Assertions.assertEquals("employee", employeeId.getReferencedTable());
        Assertions.assertEquals("id", employeeId.getReferencedColumn());

        Assertions.assertTrue(departmentId.isForeignKey());
        Assertions.assertEquals("department", departmentId.getReferencedTable());
        Assertions.assertEquals("id", departmentId.getReferencedColumn());
    }

    @Test
    public void testUserProfileTable_WithManyToManyConstraint() {
        log.info("Testing 'user_profile' table with MANYTOMANY pseudo-constraint...");

        Table table = new Table();
        table.setName("user_profile");

        Column profileId = new Column();
        profileId.setName("profile_id");
        profileId.setSqlType("SERIAL");
        profileId.setPrimaryKey(true);
        profileId.setNullable(false);

        Column bio = new Column();
        bio.setName("bio");
        bio.setSqlType("TEXT");
        bio.setNullable(true);

        Column phone = new Column();
        phone.setName("phone");
        phone.setSqlType("VARCHAR");
        phone.setLength(20);
        phone.setNullable(true);

        Column userId = new Column();
        userId.setName("user_id");
        userId.setSqlType("INT");
        userId.setNullable(false);
        userId.setForeignKey(true);
        userId.setReferencedTable("user");
        userId.setReferencedColumn("user_id");


        userId.setRelationship(true);
        userId.setMappedBy("profiles");
        userId.setTargetTable("user");

        table.addColumn(profileId);
        table.addColumn(bio);
        table.addColumn(phone);
        table.addColumn(userId);

        table.setConstraints(List.of(
                "PRIMARY KEY (profile_id)",
                "FOREIGN KEY (user_id) REFERENCES user(user_id)"
        ));

        log.info(" User Profile table:\n{}", table);

        Assertions.assertEquals(4, table.getColumns().size());

        Column fk = table.getColumns().get(3);
        Assertions.assertTrue(fk.isForeignKey(), "user_id should be a foreign key");
        Assertions.assertTrue(fk.isRelationship(), "user_id should be treated as a relationship");
        Assertions.assertEquals("user", fk.getReferencedTable());
        Assertions.assertEquals("user_id", fk.getReferencedColumn());
        Assertions.assertEquals("profiles", fk.getMappedBy());
    }

}
