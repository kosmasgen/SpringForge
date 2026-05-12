package com.sqldomaingen;

import com.sqldomaingen.liquibase.LiquibaseDependencyGraphBuilder;
import com.sqldomaingen.model.Column;
import com.sqldomaingen.model.Table;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiquibaseDependencyGraphBuilderTest {

    @Test
    void shouldResolveSchemaFreeForeignKeysWhenMatchIsUnique() {
        Table languages = table("pep_schema.languages");
        Table companyProfile = table("pep_schema.company_profile");
        Table companyProfileI18n = table(
                "pep_schema.company_profile_i18n",
                foreignKeyColumn("company_profile_id", "company_profile"),
                foreignKeyColumn("language_id", "languages")
        );

        LiquibaseDependencyGraphBuilder builder = new LiquibaseDependencyGraphBuilder();

        Map<String, Set<String>> graph = builder.buildDependencyGraph(
                List.of(companyProfileI18n, languages, companyProfile)
        );

        assertEquals(Set.of(), graph.get("pep_schema.languages"));
        assertEquals(Set.of(), graph.get("pep_schema.company_profile"));
        assertEquals(
                Set.of("pep_schema.company_profile", "pep_schema.languages"),
                graph.get("pep_schema.company_profile_i18n")
        );
    }

    @Test
    void shouldIgnoreAmbiguousSchemaFreeForeignKeysAcrossSchemas() {
        Table coreUsers = table("core.users");
        Table auditUsers = table("audit.users");
        Table userSession = table(
                "app.user_session",
                foreignKeyColumn("user_id", "users")
        );

        LiquibaseDependencyGraphBuilder builder = new LiquibaseDependencyGraphBuilder();

        Map<String, Set<String>> graph = builder.buildDependencyGraph(
                List.of(coreUsers, auditUsers, userSession)
        );

        assertEquals(Set.of(), graph.get("core.users"));
        assertEquals(Set.of(), graph.get("audit.users"));
        assertEquals(Set.of(), graph.get("app.user_session"));
    }

    @Test
    void shouldBuildIdenticalGraphFromListAndMapRegardlessOfInputOrder() {
        Table languages = table("pep_schema.languages");
        Table companyStatus = table("pep_schema.company_status");
        Table company = table("pep_schema.company");
        Table department = table(
                "pep_schema.department",
                foreignKeyColumn("company_id", "pep_schema.company")
        );
        Table employee = table(
                "pep_schema.employee",
                foreignKeyColumn("company_id", "pep_schema.company"),
                foreignKeyColumn("department_id", "pep_schema.department"),
                foreignKeyColumn("status_id", "pep_schema.company_status")
        );
        Table employeeI18n = table(
                "pep_schema.employee_i18n",
                foreignKeyColumn("employee_id", "employee"),
                foreignKeyColumn("language_id", "languages")
        );
        Table project = table(
                "pep_schema.project",
                foreignKeyColumn("company_id", "company")
        );
        Table projectAssignment = table(
                "pep_schema.project_assignment",
                foreignKeyColumn("project_id", "project"),
                foreignKeyColumn("employee_id", "employee")
        );

        LiquibaseDependencyGraphBuilder builder = new LiquibaseDependencyGraphBuilder();

        Map<String, Set<String>> graphFromList = builder.buildDependencyGraph(
                List.of(projectAssignment, employeeI18n, employee, department, company, companyStatus, languages, project)
        );

        Map<String, Table> tableMap = new LinkedHashMap<>();
        tableMap.put(project.getName(), project);
        tableMap.put(languages.getName(), languages);
        tableMap.put(company.getName(), company);
        tableMap.put(projectAssignment.getName(), projectAssignment);
        tableMap.put(employee.getName(), employee);
        tableMap.put(companyStatus.getName(), companyStatus);
        tableMap.put(employeeI18n.getName(), employeeI18n);
        tableMap.put(department.getName(), department);

        Map<String, Set<String>> graphFromMap = builder.buildDependencyGraph(tableMap);

        assertEquals(graphFromList, graphFromMap);
    }

    @Test
    void shouldBuildComplexDependencyGraphForMultiBranchSchema() {
        Table languages = table("pep_schema.languages");
        Table companyStatus = table("pep_schema.company_status");
        Table company = table("pep_schema.company");
        Table companyProfile = table(
                "pep_schema.company_profile",
                foreignKeyColumn("company_id", "pep_schema.company")
        );
        Table companyProfileI18n = table(
                "pep_schema.company_profile_i18n",
                foreignKeyColumn("company_profile_id", "company_profile"),
                foreignKeyColumn("language_id", "languages")
        );
        Table department = table(
                "pep_schema.department",
                foreignKeyColumn("company_id", "company")
        );
        Table employee = table(
                "pep_schema.employee",
                foreignKeyColumn("company_id", "pep_schema.company"),
                foreignKeyColumn("department_id", "department"),
                foreignKeyColumn("status_id", "pep_schema.company_status")
        );
        Table employeeI18n = table(
                "pep_schema.employee_i18n",
                foreignKeyColumn("employee_id", "employee"),
                foreignKeyColumn("language_id", "pep_schema.languages")
        );
        Table project = table(
                "pep_schema.project",
                foreignKeyColumn("company_id", "company")
        );
        Table projectAssignment = table(
                "pep_schema.project_assignment",
                foreignKeyColumn("project_id", "project"),
                foreignKeyColumn("employee_id", "employee")
        );
        Table auditTrail = table(
                "pep_schema.audit_trail",
                foreignKeyColumn("employee_id", "employee")
        );

        LiquibaseDependencyGraphBuilder builder = new LiquibaseDependencyGraphBuilder();

        Map<String, Set<String>> graph = builder.buildDependencyGraph(
                List.of(
                        projectAssignment,
                        auditTrail,
                        employeeI18n,
                        employee,
                        department,
                        companyProfileI18n,
                        companyProfile,
                        project,
                        company,
                        companyStatus,
                        languages
                )
        );

        assertEquals(Set.of(), graph.get("pep_schema.languages"));
        assertEquals(Set.of(), graph.get("pep_schema.company_status"));
        assertEquals(Set.of(), graph.get("pep_schema.company"));
        assertEquals(Set.of("pep_schema.company"), graph.get("pep_schema.department"));
        assertEquals(Set.of("pep_schema.company"), graph.get("pep_schema.company_profile"));
        assertEquals(
                Set.of("pep_schema.company_profile", "pep_schema.languages"),
                graph.get("pep_schema.company_profile_i18n")
        );
        assertEquals(
                Set.of("pep_schema.company", "pep_schema.company_status", "pep_schema.department"),
                graph.get("pep_schema.employee")
        );
        assertEquals(
                Set.of("pep_schema.employee", "pep_schema.languages"),
                graph.get("pep_schema.employee_i18n")
        );
        assertEquals(Set.of("pep_schema.company"), graph.get("pep_schema.project"));
        assertEquals(
                Set.of("pep_schema.employee", "pep_schema.project"),
                graph.get("pep_schema.project_assignment")
        );
        assertEquals(Set.of("pep_schema.employee"), graph.get("pep_schema.audit_trail"));
    }

    private Table table(String tableName, Column... columns) {
        Table table = new Table();
        table.setName(tableName);
        table.setColumns(new ArrayList<>(List.of(columns)));
        return table;
    }

    private Column foreignKeyColumn(String columnName, String referencedTable) {
        Column column = new Column();
        column.setName(columnName);
        column.setForeignKey(true);
        column.setReferencedTable(referencedTable);
        column.setReferencedColumn("id");
        return column;
    }
}