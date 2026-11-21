package com.talentbridge.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QuestionnaireEvaluationMigrationIT {
    @Test void v15ToV16AddsDurableEvaluationWithoutChangingHistoricalGrades() {
        var source=(DriverManagerDataSource)new DatabaseTestConfig().dataSource();var jdbc=new JdbcTemplate(source);
        String schema="questionnaire_migration_"+UUID.randomUUID().toString().replace("-","");jdbc.execute("CREATE SCHEMA "+schema);
        try {
            var properties=new Properties();properties.setProperty("currentSchema",schema+",public");source.setConnectionProperties(properties);
            Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema).createSchemas(false).target("15").load().migrate();
            UUID student=UUID.randomUUID(),project=UUID.randomUUID(),party=UUID.randomUUID(),submission=UUID.randomUUID(),report=UUID.randomUUID(),scorecard=UUID.randomUUID();
            jdbc.update("INSERT INTO users(id,email,password,first_name,last_name,role,status) VALUES (?,?,?,'Legacy','Owner','STUDENT','APPROVED')",student,student+"@migration.invalid","synthetic-unused");
            jdbc.update("INSERT INTO projects(id,title,created_by_id) VALUES (?,'Historical project',?)",project,student);
            jdbc.update("INSERT INTO parties(id,name,leader_id) VALUES (?,'Historical party',?)",party,student);
            jdbc.update("INSERT INTO submissions(id,party_id,project_id,status) VALUES (?,?,?,'EVALUATED')",submission,party,project);
            jdbc.update("INSERT INTO evaluation_reports(id,submission_id,total_score,finalized) VALUES (?,?,84.37,true)",report,submission);
            jdbc.update("INSERT INTO student_evaluation_scores(evaluation_report_id,student_id,individual_score,contribution_percentage) VALUES (?,?,81.23,33.33)",report,student);
            jdbc.update("INSERT INTO scorecards(id,student_id,average_score,total_projects) VALUES (?,?,81.2,1)",scorecard,student);
            jdbc.update("INSERT INTO scorecard_entries(scorecard_id,project_id,evaluation_report_id,score) VALUES (?,?,?,81.23)",scorecard,project,report);
            var before=jdbc.queryForMap("SELECT total_score,finalized,evaluated_at,created_at,updated_at FROM evaluation_reports WHERE id=?",report);
            var upgrade=Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema).createSchemas(false).target("16").load();upgrade.migrate();
            assertEquals("16",upgrade.info().current().getVersion().getVersion());
            assertEquals(before,jdbc.queryForMap("SELECT total_score,finalized,evaluated_at,created_at,updated_at FROM evaluation_reports WHERE id=?",report));
            assertEquals(81.23,jdbc.queryForObject("SELECT individual_score FROM student_evaluation_scores WHERE evaluation_report_id=?",Double.class,report));
            assertEquals(81.23,jdbc.queryForObject("SELECT score FROM scorecard_entries WHERE evaluation_report_id=?",Double.class,report));
            assertEquals(1,jdbc.queryForObject("SELECT assessment_version FROM submissions WHERE id=?",Integer.class,submission));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM questionnaire_evaluation_runs",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM questionnaire_snapshots",Integer.class));
        } finally {jdbc.execute("DROP SCHEMA "+schema+" CASCADE");assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM pg_namespace WHERE nspname=?",Integer.class,schema));}
    }
}
