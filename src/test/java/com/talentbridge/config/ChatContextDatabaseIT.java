package com.talentbridge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.dto.response.ChatContextResponse;
import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.repository.*;
import com.talentbridge.service.ChatContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig({DatabaseTestConfig.class, ChatContextDatabaseIT.ChatConfig.class})
@TestPropertySource(properties = {"jwt.secret=synthetic-integration-signing-key-not-a-production-secret",
        "jwt.expiration-ms=60000", "jwt.refresh-expiration-ms=120000"})
class ChatContextDatabaseIT {
    @TestConfiguration(proxyBeanMethods = false)
    @Import(ChatContextService.class)
    static class ChatConfig {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2031-01-01T00:00:00Z"), ZoneOffset.UTC); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }
    @Autowired ChatContextService context;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired PartyRepository parties;
    @Autowired ApplicationRepository applications;
    @Autowired SubmissionRepository submissions;
    @Autowired EvaluationReportRepository evaluations;
    @Autowired ScorecardRepository scorecards;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource source;
    @Autowired ObjectMapper mapper;
    @Autowired jakarta.persistence.EntityManagerFactory entityManagerFactory;
    JdbcTemplate jdbc;
    User student, outsider, owner, otherOwner, supervisor, otherSupervisor, projectSupervisor, coordinator;
    Project assigned, hidden, open;
    Party ownParty, hiddenParty;
    EvaluationReport ownReport, hiddenReport;

    @BeforeEach
    void fixture() {
        jdbc = new JdbcTemplate(source);
        jdbc.execute("TRUNCATE TABLE users CASCADE");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            student = account(UserRole.STUDENT);
            outsider = account(UserRole.STUDENT);
            owner = account(UserRole.COMPANY);
            otherOwner = account(UserRole.COMPANY);
            supervisor = account(UserRole.PARTY_SUPERVISOR);
            otherSupervisor = account(UserRole.PARTY_SUPERVISOR);
            projectSupervisor = account(UserRole.PROJECT_SUPERVISOR);
            coordinator = account(UserRole.COORDINATOR);
            assigned = project(owner, "Assigned", ProjectStatus.ASSIGNED, LocalDateTime.of(2031,1,2,0,0));
            assigned.setProjectSupervisor(projectSupervisor);
            assigned.setScope("x".repeat(1700));
            assigned.setTools(new ArrayList<>(java.util.stream.IntStream.range(0, 22).mapToObj(i -> "tool" + i).toList()));
            assigned = projects.save(assigned);
            hidden = project(otherOwner, "Other company private", ProjectStatus.ASSIGNED, null);
            open = project(otherOwner, "Public open", ProjectStatus.OPEN, LocalDateTime.of(2031,1,3,0,0));
            ownParty = party(student, supervisor, assigned);
            hiddenParty = party(outsider, otherSupervisor, hidden);
            applications.save(Application.builder().party(ownParty).project(assigned).rankPosition(1).status(ApplicationStatus.ASSIGNED).build());
            applications.save(Application.builder().party(hiddenParty).project(assigned).rankPosition(1).status(ApplicationStatus.REJECTED).build());
            applications.save(Application.builder().party(hiddenParty).project(hidden).rankPosition(2).status(ApplicationStatus.ASSIGNED).build());
            ownReport = report(ownParty, assigned, 81.0);
            hiddenReport = report(hiddenParty, hidden, 22.0);
            scorecards.save(Scorecard.builder().student(student).averageScore(77.0).totalProjects(1).build());
        });
    }

    User account(UserRole role) {
        return users.save(User.builder().firstName("Fixture").lastName(role.name()).email(UUID.randomUUID() + "@example.test")
                .password("synthetic-hash").githubAccessToken("never-export-token").role(role).status(UserStatus.APPROVED).build());
    }
    Project project(User creator, String title, ProjectStatus status, LocalDateTime deadline) {
        return projects.save(Project.builder().createdBy(creator).title(title).status(status).deadline(deadline).build());
    }
    Party party(User member, User mentor, Project project) {
        return parties.save(Party.builder().name("Party " + member.getId()).leader(member).members(new HashSet<>(Set.of(member)))
                .supervisor(mentor).assignedProject(project).status(PartyStatus.ASSIGNED).build());
    }
    EvaluationReport report(Party party, Project project, double score) {
        Submission submission = submissions.save(Submission.builder().party(party).project(project).status(SubmissionStatus.EVALUATED).build());
        EvaluationReport report = EvaluationReport.builder().submission(submission).totalScore(score).finalized(false).build();
        report.getStudentScores().add(StudentEvaluationScore.builder().evaluationReport(report).student(party.getLeader())
                .individualScore(score - 4).totalCommits(8).contributionPercentage(60.0).performanceNotes("private-other-student-notes").build());
        return evaluations.save(report);
    }

    Set<UUID> projectIds(ChatContextResponse snapshot) {
        return snapshot.projects().items().stream().map(ChatContextResponse.ProjectSummary::id).collect(java.util.stream.Collectors.toSet());
    }
    Set<UUID> evaluationIds(ChatContextResponse snapshot) {
        return snapshot.evaluations().items().stream().map(ChatContextResponse.EvaluationSummary::id).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void studentKeepsOpenReadAndOwnAssignmentWithoutOtherPrivateRecordsOrIndividualScores() throws Exception {
        assertTrue(parties.existsByMemberId(student.getId()));
        assertEquals(assigned.getId(), jdbc.queryForObject("SELECT assigned_project_id FROM parties WHERE id = ?", UUID.class, ownParty.getId()));
        var snapshot = context.snapshot(student.getId());
        assertEquals(Set.of(assigned.getId()), projectIds(snapshot));
        assertEquals(List.of(open.getId()), snapshot.openProjects().items().stream().map(ChatContextResponse.ProjectSummary::id).toList());
        assertEquals(assigned.getId(), snapshot.myAssignedProject().id());
        assertEquals(1500, snapshot.myAssignedProject().scope().length());
        assertTrue(snapshot.myAssignedProject().truncatedFields().containsAll(List.of("scope", "tools")));
        assertEquals(20, snapshot.myAssignedProject().tools().size());
        assertEquals(2, snapshot.myAssignedProject().partyApplicationCount());
        assertEquals(1, snapshot.applications().total());
        assertEquals(Set.of(ownReport.getId()), evaluationIds(snapshot));
        assertFalse(snapshot.evaluations().items().get(0).finalized());
        assertEquals(77.0, snapshot.evaluations().items().get(0).myIndividualScore());
        String json = mapper.writeValueAsString(snapshot);
        assertFalse(json.contains("@example.test"));
        assertFalse(json.contains("never-export-token"));
        assertFalse(json.contains("private-other-student-notes"));
        assertFalse(json.contains(hiddenReport.getId().toString()));
        assertNull(snapshot.partyDirectory());
    }

    @Test
    void companyAndProjectSupervisorSeeOnlyTheirWorkspaceAndIncomingApplications() {
        for (User viewer : List.of(owner, projectSupervisor)) {
            var snapshot = context.snapshot(viewer.getId());
            assertEquals(Set.of(assigned.getId()), projectIds(snapshot));
            assertEquals(1, snapshot.openProjects().total());
            assertEquals(2, snapshot.applications().total());
            assertEquals(Set.of(ownReport.getId()), evaluationIds(snapshot));
            assertNull(snapshot.evaluations().items().get(0).myIndividualScore());
            assertNull(snapshot.myScorecard());
            assertNull(snapshot.counts().users());
        }
    }

    @Test
    void partyBrowseDoesNotAuthorizeOtherPartyApplicationsSubmissionOrEvaluation() {
        var snapshot = context.snapshot(supervisor.getId());
        assertEquals(2, snapshot.partyDirectory().total());
        assertEquals(1, snapshot.parties().total());
        assertEquals(1, snapshot.applications().total());
        assertEquals(1, snapshot.submissions().total());
        assertEquals(Set.of(ownReport.getId()), evaluationIds(snapshot));
        assertNull(snapshot.evaluations().items().get(0).myIndividualScore());
        assertEquals(Set.of(assigned.getId()), projectIds(snapshot));
    }

    @Test
    void coordinatorGetsExactAggregatesAndAllReportsWithoutAnEmailDirectory() throws Exception {
        var snapshot = context.snapshot(coordinator.getId());
        assertEquals(3, snapshot.projects().total());
        assertEquals(2, snapshot.evaluations().total());
        assertEquals(0, snapshot.counts().finalizedEvaluations());
        assertEquals(8, snapshot.counts().users().total());
        assertEquals(2L, snapshot.counts().users().byRole().get(UserRole.STUDENT));
        assertEquals(0L, snapshot.counts().users().byStatus().get(UserStatus.PENDING));
        assertFalse(mapper.writeValueAsString(snapshot).contains("@example.test"));
        assertEquals(Set.of(ownReport.getId(), hiddenReport.getId()), evaluationIds(snapshot));
    }

    @Test
    void versionTwoDraftGradesNeverEnterStudentCompanySupervisorOrCoordinatorChatAndLegacyDraftsStayCompatible() throws Exception {
        jdbc.update("UPDATE submissions SET assessment_version=2 WHERE id=?",ownReport.getSubmission().getId());
        for(User viewer:List.of(student,owner,supervisor,projectSupervisor,coordinator)) {
            var snapshot=context.snapshot(viewer.getId());
            assertFalse(evaluationIds(snapshot).contains(ownReport.getId()));
            assertFalse(mapper.writeValueAsString(snapshot).contains("private-other-student-notes"));
        }
        assertTrue(evaluationIds(context.snapshot(coordinator.getId())).contains(hiddenReport.getId()),"Historical v1 drafts retain their existing contract");
        jdbc.update("UPDATE evaluation_reports SET finalized=true WHERE id=?",ownReport.getId());
        assertTrue(evaluationIds(context.snapshot(student.getId())).contains(ownReport.getId()));
        assertTrue(evaluationIds(context.snapshot(owner.getId())).contains(ownReport.getId()));
    }

    @Test
    void exactCountsAndUpcomingQueryAreIndependentOfFiftyNewestSummariesWithStableTies() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            for (int i = 0; i < 55; i++) project(owner, "Recent " + i, ProjectStatus.OPEN, null);
        });
        jdbc.update("UPDATE projects SET created_at = '2030-12-31 00:00:00'");
        jdbc.update("UPDATE projects SET created_at = '2029-01-01 00:00:00' WHERE id = ?", open.getId());
        var snapshot = context.snapshot(coordinator.getId());
        assertEquals(58, snapshot.projects().total());
        assertEquals(50, snapshot.projects().items().size());
        assertTrue(snapshot.projects().truncated());
        assertEquals(56L, snapshot.counts().projectsByStatus().get(ProjectStatus.OPEN));
        assertFalse(projectIds(snapshot).contains(open.getId()));
        assertEquals(List.of(open.getId()), snapshot.upcomingDeadlines().items().stream().map(ChatContextResponse.DeadlineSummary::projectId).toList());
        List<UUID> expected = jdbc.queryForList("SELECT id FROM projects ORDER BY created_at DESC, id ASC LIMIT 50", UUID.class);
        assertEquals(expected, snapshot.projects().items().stream().map(ChatContextResponse.ProjectSummary::id).toList());
        assertEquals(expected, context.snapshot(coordinator.getId()).projects().items().stream().map(ChatContextResponse.ProjectSummary::id).toList());
    }

    @Test
    void summaryRowsDoNotLoadProjectEntitiesOrIssuePerRecordQueries() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            for (int i = 0; i < 60; i++) project(owner, "Bounded " + i, ProjectStatus.OPEN, null);
        });
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var snapshot = context.snapshot(coordinator.getId());
            assertEquals(50, snapshot.projects().items().size());
            assertTrue(statistics.getEntityLoadCount() < 10, "Summaries must remain scalar projections");
            assertTrue(statistics.getPrepareStatementCount() <= 32, "Query count must not grow per summary row");
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }
}
