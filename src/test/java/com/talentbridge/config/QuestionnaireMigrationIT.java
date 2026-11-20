package com.talentbridge.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class QuestionnaireMigrationIT {
    @Test
    void additiveMigrationProvidesLegacyDefaultAndVersionedQuestionnaireTables() {
        var source = (DriverManagerDataSource) new DatabaseTestConfig().dataSource();
        var jdbc = new JdbcTemplate(source);
        // Match V1's idempotent prerequisite so this test also runs standalone on a fresh test database.
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\" WITH SCHEMA public");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_extension e JOIN pg_namespace n "
                + "ON n.oid=e.extnamespace WHERE e.extname='uuid-ossp' AND n.nspname='public'", Integer.class));
        String schema = "questionnaire_migration_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            Properties properties = new Properties();
            properties.setProperty("currentSchema", schema + ",public");
            source.setConnectionProperties(properties);
            assertEquals(schema, jdbc.queryForObject("SELECT current_schema()", String.class));
            var baseline = Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema)
                    .createSchemas(false).target("11").load();
            baseline.migrate();
            assertEquals("11", baseline.info().current().getVersion().getVersion(),
                    "The migration regression must start at V11, not silently reuse an already-upgraded schema");
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema=? AND table_name='submissions' AND column_name='assessment_version'", Integer.class, schema));
            UUID owner = UUID.randomUUID(), project = UUID.randomUUID(), party = UUID.randomUUID();
            UUID submission = UUID.randomUUID(), report = UUID.randomUUID(), scorecard = UUID.randomUUID();
            jdbc.update("INSERT INTO users(id,email,password,first_name,last_name,role,status) VALUES (?,?,?,'Legacy','Owner','STUDENT','APPROVED')",
                    owner, owner + "@migration.invalid", "synthetic-unused");
            jdbc.update("INSERT INTO projects(id,title,created_by_id) VALUES (?,'Legacy migration fixture',?)", project, owner);
            jdbc.update("INSERT INTO parties(id,name,leader_id) VALUES (?,'Legacy migration party',?)", party, owner);
            jdbc.update("INSERT INTO submissions(id,party_id,project_id,status) VALUES (?,?,?,'EVALUATED')", submission, party, project);
            jdbc.update("INSERT INTO evaluation_reports(id,submission_id,total_score,finalized) VALUES (?,?,84,true)", report, submission);
            jdbc.update("INSERT INTO scorecards(id,student_id,average_score,total_projects) VALUES (?,?,84,1)", scorecard, owner);
            var upgraded = Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema)
                    .createSchemas(false).load();
            upgraded.migrate();
            assertEquals("12", upgraded.info().current().getVersion().getVersion());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema=? AND table_name='submissions' AND column_name='assessment_version' AND column_default='1'",
                    Integer.class, schema));
            assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema=? "
                    + "AND table_name IN ('questionnaire_snapshots','questionnaire_evidence','questionnaire_assignments',"
                    + "'questionnaire_questions','questionnaire_jobs','questionnaire_mark_adjustments')", Integer.class, schema));
            assertEquals(1, jdbc.queryForObject("SELECT assessment_version FROM submissions WHERE id=?", Integer.class, submission));
            assertEquals(84.0, jdbc.queryForObject("SELECT total_score FROM evaluation_reports WHERE id=?", Double.class, report));
            assertTrue(jdbc.queryForObject("SELECT finalized FROM evaluation_reports WHERE id=?", Boolean.class, report));
            assertEquals(84.0, jdbc.queryForObject("SELECT average_score FROM scorecards WHERE id=?", Double.class, scorecard));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM questionnaire_snapshots WHERE submission_id=?", Integer.class, submission));
            System.out.println("Migration regression " + schema + ": verified V11 -> V12; legacy report/scorecard=84, enrollment=0");
        } finally {
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=?", Integer.class, schema));
        }
    }
}
