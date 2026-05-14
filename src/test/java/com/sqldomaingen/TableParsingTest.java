package com.sqldomaingen;

import com.sqldomaingen.parser.PostgreSQLBaseListener;
import com.sqldomaingen.parser.PostgreSQLLexer;
import com.sqldomaingen.parser.PostgreSQLParser;
import lombok.extern.log4j.Log4j2;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Log4j2
class TableParsingTest {

    /**
     * Parses a SQL script and returns capture information for assertions.
     * Fails on any syntax error (strict mode).
     */
    private ParseCapture parseStrict(String sqlScript) {
        try {
            log.info("Starting strict SQL parsing test...");

            PostgreSQLLexer lexer = new PostgreSQLLexer(CharStreams.fromString(sqlScript));
            lexer.removeErrorListeners();

            List<String> syntaxErrors = new ArrayList<>();
            lexer.addErrorListener(new CollectingErrorListener("LEXER", syntaxErrors));

            CommonTokenStream tokens = new CommonTokenStream(lexer);

            PostgreSQLParser parser = new PostgreSQLParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(new CollectingErrorListener("PARSER", syntaxErrors));

            // Fail fast instead of recovering silently
            parser.setErrorHandler(new BailErrorStrategy());
            parser.setTrace(false);

            ParseTree tree = parser.sqlScript();

            assertNotNull(tree, "The ParseTree should not be null.");

            if (!syntaxErrors.isEmpty()) {
                fail("Syntax errors found:\n" + String.join("\n", syntaxErrors));
            }

            ParseCapture capture = new ParseCapture(tree.getText());

            ParseTreeWalker walker = new ParseTreeWalker();
            walker.walk(new PostgreSQLBaseListener() {
                @Override
                public void enterCreateTableStatement(PostgreSQLParser.CreateTableStatementContext ctx) {
                    assertNotNull(ctx, "CreateTableStatementContext should not be null.");

                    if (ctx.tableName() == null || ctx.tableName().isEmpty()) {
                        fail("CreateTableStatement has no tableName.");
                    }

                    String tableName = ctx.tableName(0).getText();
                    assertNotNull(tableName, "Table name should not be null.");

                    int tableConstraintCount = (ctx.tableConstraint() == null) ? 0 : ctx.tableConstraint().size();
                    String rawCreateTableText = ctx.getText();

                    capture.createTables.add(new CreateTableCapture(
                            tableName,
                            rawCreateTableText,
                            tableConstraintCount
                    ));

                    log.info("Captured CREATE TABLE: {} (tableConstraints={})",
                            tableName, tableConstraintCount);
                }
            }, tree);

            log.info("Strict SQL parsing test completed successfully.");
            return capture;

        } catch (ParseCancellationException e) {
            log.error("Parsing failed (cancelled): {}", e.getMessage(), e);
            fail("Parsing failed (syntax error / parse cancellation): " + e.getMessage());
            return null; // unreachable
        } catch (Exception e) {
            log.error("Parsing failed: {}", e.getMessage(), e);
            fail("Parsing failed: " + e.getMessage());
            return null; // unreachable
        }
    }

    /**
     * Validates a CREATE TABLE script strictly:
     * - no syntax errors
     * - exactly one CREATE TABLE statement
     * - expected table name
     * - expected number of table constraints
     * - required fragments (columns/types/defaults/fks/etc.) exist in the parsed CREATE TABLE text
     */
    private void parseCreateTableAndValidate(String sqlScript,
                                             String expectedTableName,
                                             int expectedTableConstraintCount,
                                             String... requiredFragments) {

        ParseCapture capture = parseStrict(sqlScript);

        assertEquals(1, capture.createTables.size(),
                "Expected exactly one CREATE TABLE statement in script.");

        CreateTableCapture table = capture.createTables.getFirst();

        assertEquals(normalize(expectedTableName), normalize(table.tableName),
                "Parsed table name mismatch.");

        assertEquals(expectedTableConstraintCount, table.tableConstraintCount,
                "Unexpected number of table constraints in CREATE TABLE.");

        String normalizedCreate = normalize(table.rawText);

        for (String fragment : requiredFragments) {
            assertTrue(
                    normalizedCreate.contains(normalize(fragment)),
                    () -> "Missing required fragment in CREATE TABLE [" + table.tableName + "]: " + fragment
            );
        }
    }

    /**
     * Validates a non-CREATE-TABLE SQL statement strictly (e.g. ALTER TABLE / CREATE TRIGGER):
     * - no syntax errors
     * - required fragments exist in the parsed tree text
     */
    private void parseStatementAndValidate(String sqlScript, String... requiredFragments) {
        ParseCapture capture = parseStrict(sqlScript);

        String normalizedTreeText = normalize(capture.fullTreeText);

        for (String fragment : requiredFragments) {
            assertTrue(
                    normalizedTreeText.contains(normalize(fragment)),
                    () -> "Missing required fragment in parsed statement: " + fragment
            );
        }
    }

    /**
     * Normalizes text to make assertions robust against whitespace/newline formatting.
     */
    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "")
                .toLowerCase();
    }

    @Test
    void testParseDepartmentTable() {
        String sql = """
                CREATE TABLE department (
                    department_id SERIAL PRIMARY KEY,
                    name VARCHAR(1000) NOT NULL,
                    description TEXT,
                    parent_dept_id INT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (parent_dept_id) REFERENCES department(department_id)
                );
                """;

        parseCreateTableAndValidate(
                sql,
                "department",
                1,
                "department_id SERIAL PRIMARY KEY",
                "name VARCHAR(1000) NOT NULL",
                "description TEXT",
                "parent_dept_id INT",
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "FOREIGN KEY (parent_dept_id) REFERENCES department(department_id)"
        );
    }

    @Test
    void testParseUserTable() {
        String sql = """
                CREATE TABLE user (
                    user_id SERIAL PRIMARY KEY,
                    username VARCHAR(50) NOT NULL UNIQUE,
                    password VARCHAR(2000) NOT NULL,
                    email VARCHAR(100) NOT NULL UNIQUE,
                    full_name VARCHAR(100) NOT NULL,
                    department_id INT,
                    role VARCHAR(50) NOT NULL,
                    supervisor_id INT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (department_id) REFERENCES department(department_id),
                    FOREIGN KEY (supervisor_id) REFERENCES user(user_id)
                );
                """;

        parseCreateTableAndValidate(
                sql,
                "user",
                2,
                "user_id SERIAL PRIMARY KEY",
                "username VARCHAR(50) NOT NULL UNIQUE",
                "password VARCHAR(2000) NOT NULL",
                "email VARCHAR(100) NOT NULL UNIQUE",
                "full_name VARCHAR(100) NOT NULL",
                "department_id INT",
                "role VARCHAR(50) NOT NULL",
                "supervisor_id INT",
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "FOREIGN KEY (department_id) REFERENCES department(department_id)",
                "FOREIGN KEY (supervisor_id) REFERENCES user(user_id)"
        );
    }

    @Test
    void testParseRecurringPatternTable() {
        String sql = """
                CREATE TABLE recurring_pattern (
                    pattern_id SERIAL PRIMARY KEY,
                    pattern_type VARCHAR(50) NOT NULL,
                    frequency VARCHAR(50),
                    days_of_week VARCHAR(50),
                    day_of_month INT,
                    month_of_year INT,
                    end_date DATE,
                    end_after_occur INT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                );
                """;

        parseCreateTableAndValidate(
                sql,
                "recurring_pattern",
                0,
                "pattern_id SERIAL PRIMARY KEY",
                "pattern_type VARCHAR(50) NOT NULL",
                "frequency VARCHAR(50)",
                "days_of_week VARCHAR(50)",
                "day_of_month INT",
                "month_of_year INT",
                "end_date DATE",
                "end_after_occur INT",
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
        );
    }

    @Test
    void testParseEventTable() {
        String sql = """
                CREATE TABLE event (
                    event_id SERIAL PRIMARY KEY,
                    title VARCHAR(100) NOT NULL,
                    description TEXT,
                    start_time TIMESTAMP NOT NULL,
                    end_time TIMESTAMP NOT NULL,
                    location VARCHAR(255),
                    event_type VARCHAR(50) NOT NULL,
                    visibility_type VARCHAR(50) NOT NULL,
                    creator_id INT NOT NULL,
                    is_recurring BOOLEAN DEFAULT FALSE,
                    recur_pattern_id INT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (creator_id) REFERENCES user(user_id),
                    FOREIGN KEY (recur_pattern_id) REFERENCES recurring_pattern(pattern_id)
                );
                """;

        parseCreateTableAndValidate(
                sql,
                "event",
                2,
                "event_id SERIAL PRIMARY KEY",
                "title VARCHAR(100) NOT NULL",
                "description TEXT",
                "start_time TIMESTAMP NOT NULL",
                "end_time TIMESTAMP NOT NULL",
                "location VARCHAR(255)",
                "event_type VARCHAR(50) NOT NULL",
                "visibility_type VARCHAR(50) NOT NULL",
                "creator_id INT NOT NULL",
                "is_recurring BOOLEAN DEFAULT FALSE",
                "recur_pattern_id INT",
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "FOREIGN KEY (creator_id) REFERENCES user(user_id)",
                "FOREIGN KEY (recur_pattern_id) REFERENCES recurring_pattern(pattern_id)"
        );
    }

    @Test
    void testParseEventAssignmentTable() {
        String sql = """
                CREATE TABLE event_assignment (
                    assignment_id SERIAL PRIMARY KEY,
                    event_id INT NOT NULL,
                    user_id INT,
                    department_id INT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (event_id) REFERENCES event(event_id),
                    FOREIGN KEY (user_id) REFERENCES user(user_id),
                    FOREIGN KEY (department_id) REFERENCES department(department_id)
                );
                """;

        parseCreateTableAndValidate(
                sql,
                "event_assignment",
                3,
                "assignment_id SERIAL PRIMARY KEY",
                "event_id INT NOT NULL",
                "user_id INT",
                "department_id INT",
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "FOREIGN KEY (event_id) REFERENCES event(event_id)",
                "FOREIGN KEY (user_id) REFERENCES user(user_id)",
                "FOREIGN KEY (department_id) REFERENCES department(department_id)"
        );
    }

    @Test
    void testParseEventExceptionTable() {
        String sql = """
                CREATE TABLE event_exception (
                    exception_id SERIAL PRIMARY KEY,
                    event_id INT NOT NULL,
                    exception_date DATE NOT NULL,
                    is_rescheduled BOOLEAN DEFAULT FALSE,
                    new_start_time TIMESTAMP,
                    new_end_time TIMESTAMP,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (event_id) REFERENCES event(event_id)
                );
                """;

        parseCreateTableAndValidate(
                sql,
                "event_exception",
                1,
                "exception_id SERIAL PRIMARY KEY",
                "event_id INT NOT NULL",
                "exception_date DATE NOT NULL",
                "is_rescheduled BOOLEAN DEFAULT FALSE",
                "new_start_time TIMESTAMP",
                "new_end_time TIMESTAMP",
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "FOREIGN KEY (event_id) REFERENCES event(event_id)"
        );
    }

    @Test
    void testParseTimeOffRequestTable() {
        String sql = """
                CREATE TABLE time_off_request (
                    request_id SERIAL PRIMARY KEY,
                    user_id INT NOT NULL,
                    start_date DATE NOT NULL,
                    end_date DATE NOT NULL,
                    type VARCHAR(50) NOT NULL,
                    status VARCHAR(50) NOT NULL,
                    supervisor_id INT,
                    reason TEXT,
                    comments TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_id) REFERENCES user(user_id),
                    FOREIGN KEY (supervisor_id) REFERENCES user(user_id)
                );
                """;

        parseCreateTableAndValidate(
                sql,
                "time_off_request",
                2,
                "request_id SERIAL PRIMARY KEY",
                "user_id INT NOT NULL",
                "start_date DATE NOT NULL",
                "end_date DATE NOT NULL",
                "type VARCHAR(50) NOT NULL",
                "status VARCHAR(50) NOT NULL",
                "supervisor_id INT",
                "reason TEXT",
                "comments TEXT",
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "FOREIGN KEY (user_id) REFERENCES user(user_id)",
                "FOREIGN KEY (supervisor_id) REFERENCES user(user_id)"
        );
    }

    @Test
    void testParseHolidayTable() {
        String sql = """
            CREATE TABLE holiday (
                holiday_id SERIAL PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                description TEXT,
                holiday_date DATE NOT NULL,
                is_recurring BOOLEAN DEFAULT FALSE,
                recur_pattern_id INT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (recur_pattern_id) REFERENCES recurring_pattern(pattern_id)
            );
            """;

        parseCreateTableAndValidate(
                sql,
                "holiday",
                1,
                "holiday_id SERIAL PRIMARY KEY",
                "name VARCHAR(100) NOT NULL",
                "description TEXT",
                "holiday_date DATE NOT NULL",
                "is_recurring BOOLEAN DEFAULT FALSE",
                "recur_pattern_id INT",
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "FOREIGN KEY (recur_pattern_id) REFERENCES recurring_pattern(pattern_id)"
        );
    }

    @Test
    void testParseDepartmentDayOffTable() {
        String sql = """
            CREATE TABLE department_day_off (
                day_off_id SERIAL PRIMARY KEY,
                department_id INT NOT NULL,
                name VARCHAR(100) NOT NULL,
                description TEXT,
                day_off_date DATE NOT NULL,
                is_recurring BOOLEAN DEFAULT FALSE,
                recur_pattern_id INT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (department_id) REFERENCES department(department_id),
                FOREIGN KEY (recur_pattern_id) REFERENCES recurring_pattern(pattern_id)
            );
            """;

        parseCreateTableAndValidate(
                sql,
                "department_day_off",
                2,
                "day_off_id SERIAL PRIMARY KEY",
                "department_id INT NOT NULL",
                "name VARCHAR(100) NOT NULL",
                "description TEXT",
                "day_off_date DATE NOT NULL",
                "is_recurring BOOLEAN DEFAULT FALSE",
                "recur_pattern_id INT",
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "FOREIGN KEY (department_id) REFERENCES department(department_id)",
                "FOREIGN KEY (recur_pattern_id) REFERENCES recurring_pattern(pattern_id)"
        );
    }

    @Test
    void testParseAbsenceTable() {
        String sql = """
                CREATE TABLE absence (
                    absence_id SERIAL PRIMARY KEY,
                    user_id INT NOT NULL,
                    start_time TIMESTAMP NOT NULL,
                    end_time TIMESTAMP NOT NULL,
                    reason TEXT,
                    is_notification BOOLEAN DEFAULT FALSE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    FOREIGN KEY (user_id) REFERENCES user(user_id)
                );
                """;

        parseCreateTableAndValidate(
                sql,
                "absence",
                1,
                "absence_id SERIAL PRIMARY KEY",
                "user_id INT NOT NULL",
                "start_time TIMESTAMP NOT NULL",
                "end_time TIMESTAMP NOT NULL",
                "reason TEXT",
                "is_notification BOOLEAN DEFAULT FALSE",
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "FOREIGN KEY (user_id) REFERENCES user(user_id)"
        );
    }

    @Test
    void testParseAlterRecurringPatternAddEventId() {
        String sql = """
                ALTER TABLE recurring_pattern
                ADD COLUMN event_id INT,
                ADD FOREIGN KEY (event_id) REFERENCES event(event_id);
                """;

        parseStatementAndValidate(
                sql,
                "ALTER TABLE recurring_pattern",
                "ADD COLUMN event_id INT",
                "ADD FOREIGN KEY (event_id) REFERENCES event(event_id)"
        );
    }

    @Test
    void testParseCreateTriggerStatement() {
        String sql = """
                CREATE TRIGGER trg_department_set_updated_at
                BEFORE UPDATE ON department
                FOR EACH ROW
                EXECUTE FUNCTION set_updated_at();
                """;

        parseStatementAndValidate(
                sql,
                "CREATE TRIGGER trg_department_set_updated_at",
                "BEFORE UPDATE ON department",
                "FOR EACH ROW",
                "EXECUTE FUNCTION set_updated_at()"
        );
    }

    @Test
    void testParseEmployeeDepartmentTable_WithCompositePrimaryKeyAndFKs() {
        String sql = """
                CREATE TABLE employee_department (
                    employee_id INT NOT NULL,
                    department_id INT NOT NULL,
                    assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    assigned_by VARCHAR(100),
                    PRIMARY KEY (employee_id, department_id),
                    FOREIGN KEY (employee_id) REFERENCES employee(id),
                    FOREIGN KEY (department_id) REFERENCES department(id)
                );
                """;

        parseCreateTableAndValidate(
                sql,
                "employee_department",
                3,
                "employee_id INT NOT NULL",
                "department_id INT NOT NULL",
                "assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP",
                "assigned_by VARCHAR(100)",
                "PRIMARY KEY (employee_id, department_id)",
                "FOREIGN KEY (employee_id) REFERENCES employee(id)",
                "FOREIGN KEY (department_id) REFERENCES department(id)"
        );
    }

    @Test
    void testParseUserProfileTable_WithManyToManyConstraint() {
        String sql = """
                CREATE TABLE user_profile (
                    profile_id SERIAL PRIMARY KEY,
                    bio TEXT,
                    phone VARCHAR(20),
                    user_id INT MANYTOMANY,
                    FOREIGN KEY (user_id) REFERENCES user(user_id)
                );
                """;

        parseCreateTableAndValidate(
                sql,
                "user_profile",
                1,
                "profile_id SERIAL PRIMARY KEY",
                "bio TEXT",
                "phone VARCHAR(20)",
                "user_id INT MANYTOMANY",
                "FOREIGN KEY (user_id) REFERENCES user(user_id)"
        );
    }


    @Test
    void testParseSalesOrderTable_WithOrderStatusCheckConstraint() {
        String sql = """
            CREATE TABLE pep_schema.sales_order (
                id uuid DEFAULT gen_random_uuid() NOT NULL,
                customer_id uuid NOT NULL,
                employee_id uuid NULL,
                order_number varchar(50) NOT NULL,
                order_status varchar(30) DEFAULT 'DRAFT' NOT NULL,
                order_date timestamp DEFAULT now() NOT NULL,
                total_amount numeric(19, 2) DEFAULT 0 NOT NULL,
                CONSTRAINT pk_sales_order PRIMARY KEY (id),
                CONSTRAINT uk_sales_order_number UNIQUE (order_number),
                CONSTRAINT fk_sales_order_customer FOREIGN KEY (customer_id) REFERENCES pep_schema.customer(id),
                CONSTRAINT fk_sales_order_employee FOREIGN KEY (employee_id) REFERENCES pep_schema.employee(id),
                CONSTRAINT chk_sales_order_status CHECK (order_status IN ('DRAFT','CONFIRMED','CANCELLED','COMPLETED'))
            );
            """;

        parseCreateTableAndValidate(
                sql,
                "pep_schema.sales_order",
                5,
                "order_status varchar(30) DEFAULT 'DRAFT' NOT NULL",
                "CONSTRAINT chk_sales_order_status CHECK (order_status IN ('DRAFT','CONFIRMED','CANCELLED','COMPLETED'))"
        );
    }

    /**
     * Collects parser/lexer syntax errors instead of printing them.
     */
    private static class CollectingErrorListener extends BaseErrorListener {
        private final String source;
        private final List<String> errors;

        private CollectingErrorListener(String source, List<String> errors) {
            this.source = source;
            this.errors = errors;
        }

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer,
                                Object offendingSymbol,
                                int line,
                                int charPositionInLine,
                                String msg,
                                RecognitionException e) {
            errors.add(source + " syntax error at " + line + ":" + charPositionInLine + " -> " + msg);
        }
    }

    /**
     * Holds captured parse information for assertions.
     */
    private static class ParseCapture {
        private final String fullTreeText;
        private final List<CreateTableCapture> createTables = new ArrayList<>();

        private ParseCapture(String fullTreeText) {
            this.fullTreeText = fullTreeText;
        }
    }

    /**
     * Holds one captured CREATE TABLE statement.
     */
    private static class CreateTableCapture {
        private final String tableName;
        private final String rawText;
        private final int tableConstraintCount;

        private CreateTableCapture(String tableName, String rawText, int tableConstraintCount) {
            this.tableName = tableName;
            this.rawText = rawText;
            this.tableConstraintCount = tableConstraintCount;
        }
    }


    @Test
    void testParseStressCheckConstraintsTable() {
        String sql = """
            CREATE TABLE pep_schema.stress_check_constraints (
                id uuid DEFAULT gen_random_uuid() NOT NULL,
                year_value int4 NOT NULL,
                status varchar(20) NOT NULL,
                percentage numeric(5, 2) NULL,
                CONSTRAINT stress_check_constraints_pkey PRIMARY KEY (id),
                CONSTRAINT chk_stress_year CHECK (((year_value >= 1900) AND (year_value <= 2100))),
                CONSTRAINT chk_stress_status CHECK (status IN ('NEW', 'ACTIVE', 'CLOSED')),
                CONSTRAINT chk_stress_percentage CHECK (((percentage IS NULL) OR ((percentage >= 0) AND (percentage <= 100))))
            );
            """;

        parseCreateTableAndValidate(
                sql,
                "pep_schema.stress_check_constraints",
                4,
                "CONSTRAINT stress_check_constraints_pkey PRIMARY KEY (id)",
                "CONSTRAINT chk_stress_year CHECK (((year_value >= 1900) AND (year_value <= 2100)))",
                "CONSTRAINT chk_stress_status CHECK (status IN ('NEW', 'ACTIVE', 'CLOSED'))",
                "CONSTRAINT chk_stress_percentage CHECK (((percentage IS NULL) OR ((percentage >= 0) AND (percentage <= 100))))"
        );
    }

    /**
     * Verifies that JSON/JSONB defaults with PostgreSQL casts are parsed without losing quotes.
     */
    @Test
    void testParseJsonbDefaultsWithCast() {
        String sql = """
        CREATE TABLE public.conservation_intervention (
            id BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
            photos JSONB DEFAULT '[]'::jsonb,
            assets JSONB DEFAULT '{}'::jsonb,
            CONSTRAINT conservation_intervention_pkey PRIMARY KEY (id)
        );
        """;

        parseCreateTableAndValidate(
                sql,
                "public.conservation_intervention",
                1,
                "photos JSONB DEFAULT '[]'::jsonb",
                "assets JSONB DEFAULT '{}'::jsonb",
                "CONSTRAINT conservation_intervention_pkey PRIMARY KEY (id)"
        );
    }

    @Test
    void testParseIndexStatements_WithExpressionsAndPartialWhere() {
        String sql = """
        CREATE INDEX idx_test_lower_name
        ON public.index_expression_test USING btree (lower((name_english)::text));

        CREATE INDEX idx_test_created_desc
        ON public.index_expression_test USING btree (created_at DESC);

        CREATE INDEX idx_test_status_created
        ON public.index_expression_test USING btree (status, created_at DESC);

        CREATE INDEX idx_test_partial_active
        ON public.index_expression_test USING btree (created_at DESC)
        WHERE deleted_at IS NULL;

        CREATE UNIQUE INDEX uq_test_email_active
        ON public.index_expression_test USING btree (lower((email)::text))
        WHERE deleted_at IS NULL;
        """;

        ParseCapture capture = parseStrict(sql);

        String normalizedTreeText = normalize(capture.fullTreeText);

        // expression index
        assertTrue(normalizedTreeText.contains(normalize("lower((name_english)::text)")));

        // DESC index
        assertTrue(normalizedTreeText.contains(normalize("created_at DESC")));

        // multi column index
        assertTrue(normalizedTreeText.contains(normalize("status, created_at DESC")));

        // partial index WHERE
        assertTrue(normalizedTreeText.contains(normalize("WHERE deleted_at IS NULL")));

        // unique index
        assertTrue(normalizedTreeText.contains(normalize("CREATE UNIQUE INDEX uq_test_email_active")));
    }

    /**
     * Verifies that column names starting with "check_" are parsed as normal columns
     * and are not confused with CHECK constraints.
     */
    @Test
    void testParseEmployeeAttendanceTable_WithCheckPrefixedColumns() {
        String sql = """
            CREATE TABLE neo_schema.employee_attendance (
                id int8 GENERATED BY DEFAULT AS IDENTITY NOT NULL,
                employee_id int8 NOT NULL,
                check_in_time timestamp NOT NULL,
                check_out_time timestamp NULL,
                attendance_date date NOT NULL,
                CONSTRAINT employee_attendance_pkey PRIMARY KEY (id),
                CONSTRAINT fk_employee_attendance_employee FOREIGN KEY (employee_id)
                    REFERENCES neo_schema.employee(id) ON DELETE CASCADE
            );
            """;

        parseCreateTableAndValidate(
                sql,
                "neo_schema.employee_attendance",
                2,
                "id int8 GENERATED BY DEFAULT AS IDENTITY NOT NULL",
                "employee_id int8 NOT NULL",
                "check_in_time timestamp NOT NULL",
                "check_out_time timestamp NULL",
                "attendance_date date NOT NULL",
                "CONSTRAINT employee_attendance_pkey PRIMARY KEY (id)",
                "CONSTRAINT fk_employee_attendance_employee FOREIGN KEY (employee_id) REFERENCES neo_schema.employee(id) ON DELETE CASCADE"
        );
    }
}