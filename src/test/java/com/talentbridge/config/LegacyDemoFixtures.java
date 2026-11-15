package com.talentbridge.config;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class LegacyDemoFixtures {
    private LegacyDemoFixtures() {}

    static void reset(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE TABLE users CASCADE");
        jdbc.update("UPDATE application_settings SET demo_data_version = 5 WHERE id = 1");
        Map<String, UUID> users = new LinkedHashMap<>();
        addUser(jdbc, users, "coordinator@talentbridge.com", "COORDINATOR", "APPROVED");
        addUser(jdbc, users, "unlisted@example.test", "STUDENT", "APPROVED");
        for (String email : DemoDataSeeder.LEGACY_USER_EMAILS) {
            String role = email.contains("party-supervisor") ? "PARTY_SUPERVISOR"
                    : email.contains("project-supervisor") ? "PROJECT_SUPERVISOR"
                    : email.contains("company") ? "COMPANY" : "STUDENT";
            String status = email.startsWith("pending.") ? "PENDING" : email.startsWith("rejected.") ? "REJECTED"
                    : email.startsWith("suspended.") ? "SUSPENDED" : "APPROVED";
            addUser(jdbc, users, email, role, status);
        }
        UUID company = UUID.randomUUID(), project = UUID.randomUUID(), party = UUID.randomUUID();
        UUID submission = UUID.randomUUID(), evaluation = UUID.randomUUID(), scorecard = UUID.randomUUID();
        UUID leader = users.get("alice.student@talentbridge.com");
        jdbc.update("INSERT INTO company_profiles (id,user_id,company_name) VALUES (?,?,?)",
                company, users.get("demo.company@talentbridge.com"), "Synthetic legacy company");
        jdbc.update("INSERT INTO student_profiles (id,user_id,university) VALUES (?,?,?)",
                UUID.randomUUID(), leader, "Synthetic university");
        jdbc.update("INSERT INTO projects (id,title,status,created_by_id,company_id,approved_by_id) VALUES (?,?,?,?,?,?)",
                project, "Synthetic legacy project", "CLOSED", users.get("demo.company@talentbridge.com"), company,
                users.get("coordinator@talentbridge.com"));
        jdbc.update("INSERT INTO parties (id,name,leader_id,status,assigned_project_id,supervisor_id) VALUES (?,?,?,?,?,?)",
                party, "Synthetic legacy party", leader, "COMPLETED", project, users.get("demo.party-supervisor@talentbridge.com"));
        for (String email : java.util.List.of("alice.student@talentbridge.com", "bob.student@talentbridge.com", "carol.student@talentbridge.com"))
            jdbc.update("INSERT INTO party_members (party_id,user_id) VALUES (?,?)", party, users.get(email));
        jdbc.update("INSERT INTO applications (id,party_id,project_id,rank_position,status) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), party, project, 1, "ASSIGNED");
        jdbc.update("INSERT INTO submissions (id,party_id,project_id,status) VALUES (?,?,?,?)",
                submission, party, project, "EVALUATED");
        jdbc.update("INSERT INTO evaluation_reports (id,submission_id,total_score,triggered_by_id,finalized) VALUES (?,?,?,?,?)",
                evaluation, submission, 80, users.get("demo.party-supervisor@talentbridge.com"), true);
        jdbc.update("INSERT INTO scorecards (id,student_id,average_score,total_projects) VALUES (?,?,?,?)",
                scorecard, leader, 80, 1);
        jdbc.update("INSERT INTO scorecard_entries (id,scorecard_id,project_id,evaluation_report_id,score) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), scorecard, project, evaluation, 80);
    }

    private static void addUser(JdbcTemplate jdbc, Map<String, UUID> users, String email, String role, String status) {
        UUID id = UUID.randomUUID();
        users.put(email, id);
        jdbc.update("INSERT INTO users (id,email,password,first_name,last_name,role,status) VALUES (?,?,?,?,?,?,?)",
                id, email, "synthetic-unused-legacy-hash", "Synthetic", "Fixture", role, status);
    }
}
