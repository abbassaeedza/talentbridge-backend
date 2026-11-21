package com.talentbridge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.talentbridge.controller.QuestionnaireController;
import com.talentbridge.dto.request.QuestionnaireAnswerRequest;
import com.talentbridge.entity.*;
import com.talentbridge.exception.GlobalExceptionHandler;
import com.talentbridge.repository.*;
import com.talentbridge.service.QuestionnaireService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringJUnitConfig(classes = {DatabaseTestConfig.class, QuestionnaireDatabaseIT.AssessmentTestConfig.class})
@TestPropertySource(properties = {"jwt.secret=synthetic-questionnaire-integration-signing-key-not-production",
        "jwt.expiration-ms=60000", "jwt.refresh-expiration-ms=120000"})
class QuestionnaireDatabaseIT {
    @Autowired QuestionnaireService service;
    @Autowired QuestionnaireAssignmentRepository assignments;
    @Autowired QuestionnaireSnapshotRepository snapshots;
    @Autowired QuestionnaireEvidenceRepository evidence;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MutableClock clock;
    JdbcTemplate jdbc;
    final Instant now = Instant.parse("2026-11-20T12:00:00Z");
    UUID owner, project, party, submission, snapshot, assignment;
    List<UUID> questions;

    @BeforeEach
    void fixture() {
        jdbc = new JdbcTemplate(source);
        clock.set(now);
        owner = UUID.randomUUID(); project = UUID.randomUUID(); party = UUID.randomUUID();
        submission = UUID.randomUUID(); snapshot = UUID.randomUUID(); assignment = UUID.randomUUID();
        questions = new ArrayList<>();
        jdbc.update("INSERT INTO users(id,email,password,first_name,last_name,role,status) VALUES (?,?,?,'Test','Owner','STUDENT','APPROVED')",
                owner, owner + "@questionnaire.invalid", "synthetic-not-a-real-password-hash");
        jdbc.update("INSERT INTO projects(id,title,created_by_id) VALUES (?,'Questionnaire fixture',?)", project, owner);
        jdbc.update("INSERT INTO parties(id,name,leader_id) VALUES (?,'Frozen fixture',?)", party, owner);
        jdbc.update("INSERT INTO party_members(party_id,user_id) VALUES (?,?)", party, owner);
        jdbc.update("INSERT INTO submissions(id,party_id,project_id,status) VALUES (?,?,?,'SUBMITTED')", submission, party, project);
        jdbc.update("INSERT INTO questionnaire_snapshots(id,submission_id,state,frozen_brief,opens_at,closes_at) "
                + "VALUES (?,?,'OPEN','Frozen approved brief',?::timestamptz,?::timestamptz)",
                snapshot, submission, now.minusSeconds(3600).toString(), now.plusSeconds(169200).toString());
        jdbc.update("INSERT INTO questionnaire_assignments(id,snapshot_id,student_id,student_name) VALUES (?,?,?,'Frozen name')",
                assignment, snapshot, owner);
        for (int i = 1; i <= 5; i++) {
            UUID id = UUID.randomUUID(); questions.add(id);
            jdbc.update("INSERT INTO questionnaire_questions(id,assignment_id,position,prompt,source_references,hidden_rubric) "
                    + "VALUES (?,?,?,?,'[]'::jsonb,'Private expected reasoning')", id, assignment, i, "Explain code " + i);
        }
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM questionnaire_mark_adjustments WHERE question_id IN (SELECT id FROM questionnaire_questions WHERE assignment_id=?)", assignment);
        jdbc.update("DELETE FROM questionnaire_jobs WHERE snapshot_id=?", snapshot);
        jdbc.update("DELETE FROM questionnaire_questions WHERE assignment_id=?", assignment);
        jdbc.update("DELETE FROM questionnaire_assignments WHERE snapshot_id=?", snapshot);
        jdbc.update("DELETE FROM questionnaire_evidence WHERE snapshot_id=?", snapshot);
        jdbc.update("DELETE FROM questionnaire_snapshots WHERE id=?", snapshot);
        jdbc.update("DELETE FROM evaluation_reports WHERE submission_id=?", submission);
        jdbc.update("DELETE FROM submissions WHERE id=?", submission);
        jdbc.update("DELETE FROM party_members WHERE party_id=?", party);
        jdbc.update("DELETE FROM parties WHERE id=?", party);
        jdbc.update("DELETE FROM projects WHERE id=?", project);
        jdbc.update("DELETE FROM users WHERE id=?", owner);
    }

    @Test
    void savingRoundTripsJsonAnswersAndJpaVersionWithNoMembershipDependency() {
        jdbc.update("DELETE FROM party_members WHERE party_id=?", party);
        var response = service.save(assignment, owner, request("Saved answer", 0));
        assertEquals(1, response.revision());
        assertEquals(5, response.answeredCount());
        var reloaded = service.get(assignment, owner);
        assertEquals(1, reloaded.revision());
        assertEquals("Saved answer", reloaded.questions().get(0).answer());
        assertEquals(now, reloaded.savedAt());
        assertEquals(now.minusSeconds(3600), reloaded.opensAt());
        assertEquals(now.plusSeconds(169200), reloaded.closesAt());
    }

    @Test
    void concurrentTabsWithSameRevisionCannotOverwriteEachOther() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String text : List.of("Tab A", "Tab B")) {
                results.add(pool.submit(() -> {
                    start.await();
                    try { service.save(assignment, owner, request(text, 0)); return 200; }
                    catch (ResponseStatusException e) { return e.getStatusCode().value(); }
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) statuses.add(f.get(15, TimeUnit.SECONDS));
            Collections.sort(statuses);
            assertEquals(List.of(200, 409), statuses);
            var saved = service.get(assignment, owner);
            assertEquals(1, saved.revision());
            assertEquals(1, saved.questions().stream().map(q -> q.answer()).distinct().count());
            assertTrue(List.of("Tab A", "Tab B").contains(saved.questions().get(0).answer()));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void invalidFifthAnswerRollsBackWithoutChangingAnySavedAnswersOrRevision() {
        service.save(assignment, owner, request("Original", 0));
        var entries = new ArrayList<>(request("Replacement", 1).answers());
        entries.set(4, new QuestionnaireAnswerRequest.Answer(UUID.randomUUID(), "Unknown"));
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.save(assignment, owner, new QuestionnaireAnswerRequest(1L, entries))).getStatusCode().value());
        var saved = service.get(assignment, owner);
        assertEquals(1, saved.revision());
        assertTrue(saved.questions().stream().allMatch(q -> q.answer().equals("Original")));
    }

    @ParameterizedTest(name = "HTTP unsupported Unicode case {index} preserves the saved set")
    @ValueSource(strings = {"synthetic\0", "synthetic\uD800", "synthetic\uDC00", "synthetic\uDC00\uD800",
            "synthetic\u0001", "synthetic\u0008", "synthetic\u000b", "synthetic\u000c", "synthetic\u000e", "synthetic\u001f"})
    void httpRejectsUnsupportedUnicodeWithoutChangingSavedSet(String invalid) throws Exception {
        service.save(assignment, owner, request("Previously saved", 0));
        var entries = new ArrayList<>(request("Replacement", 1).answers());
        entries.set(4, new QuestionnaireAnswerRequest.Answer(questions.get(4), invalid));
        var mapper = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();
        String body = mapper.writeValueAsString(new QuestionnaireAnswerRequest(1L, entries));

        var result = http().perform(put("/api/questionnaires/{id}/answers", assignment)
                .contentType("application/json").content(body)).andReturn();

        assertEquals(400, result.getResponse().getStatus(), "Unsupported Unicode must be rejected before JSONB");
        assertEquals("Answer text contains unsupported Unicode",
                mapper.readTree(result.getResponse().getContentAsString()).get("message").asText());
        var saved = service.get(assignment, owner);
        assertEquals(1, saved.revision());
        assertEquals(now, saved.savedAt());
        assertTrue(saved.questions().stream().allMatch(q -> q.answer().equals("Previously saved")));
    }

    @Test
    void httpRoundTripsValidEmojiAndOrdinaryUnicodeUnchanged() throws Exception {
        String text = "  \uD83D\uDE80 \uD83D\uDE00 \u0627\u0631\u062F\u0648 e\u0301\n\t\r\\u0001  ";
        var mapper = JsonMapper.builder().enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();
        var result = http().perform(put("/api/questionnaires/{id}/answers", assignment)
                .contentType("application/json").content(mapper.writeValueAsString(request(text, 0)))).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        var saved = service.get(assignment, owner);
        assertEquals(1, saved.revision());
        assertEquals(5, saved.answeredCount());
        assertTrue(saved.questions().stream().allMatch(q -> q.answer().equals(text)));
    }

    @Test
    void exactExpiryUsesInjectedClockAndLeavesIncompleteDistinctFromPendingFailure() {
        var entries = new ArrayList<>(request("", 0).answers());
        entries.set(0, new QuestionnaireAnswerRequest.Answer(questions.get(0), "One answer"));
        service.save(assignment, owner, new QuestionnaireAnswerRequest(0L, entries));
        clock.set(now.plusSeconds(169200));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.save(assignment, owner, request("Too late", 1))).getStatusCode().value());
        assertEquals("INCOMPLETE", service.get(assignment, owner).answerState());
        assertNull(jdbc.queryForObject("SELECT effective_score FROM questionnaire_assignments WHERE id=?", Integer.class, assignment));
        jdbc.update("UPDATE questionnaire_snapshots SET state='FAILED' WHERE id=?", snapshot);
        assertEquals("PENDING", service.get(assignment, owner).answerState());
    }

    @Test
    void generatedButUnpublishedQuestionsRemainInvisibleInRealPersistence() {
        jdbc.update("UPDATE questionnaire_snapshots SET state='GENERATING',opens_at=NULL,closes_at=NULL WHERE id=?", snapshot);
        var response = service.get(assignment, owner);
        assertTrue(response.questions().isEmpty());
        assertEquals("PENDING", response.answerState());
        assertFalse(response.editable());
    }

    @Test
    void legacySubmissionAndFinalizedReportRemainVersionOneAndUntouched() {
        UUID report = UUID.randomUUID();
        jdbc.update("INSERT INTO evaluation_reports(id,submission_id,total_score,finalized) VALUES (?,?,84,true)", report, submission);
        service.save(assignment, owner, request("Answer", 0));
        assertEquals(1, jdbc.queryForObject("SELECT assessment_version FROM submissions WHERE id=?", Integer.class, submission));
        assertEquals(84.0, jdbc.queryForObject("SELECT total_score FROM evaluation_reports WHERE id=?", Double.class, report));
        assertTrue(jdbc.queryForObject("SELECT finalized FROM evaluation_reports WHERE id=?", Boolean.class, report));
    }

    @Test
    void immutableEvidenceIsInsertedOnceAndCannotBeReplacedThroughJpa() throws Exception {
        var mapper = new ObjectMapper();
        var row = QuestionnaireEvidence.builder().snapshot(snapshots.getReferenceById(snapshot))
                .repositoryUrl("https://github.com/example/public-fixture").repositoryBranch("main")
                .commitSha("a".repeat(40)).capturedAt(now).evidence(mapper.readTree("{\"files\":[]}")).build();
        row = evidence.saveAndFlush(row);
        UUID evidenceId = row.getId();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var existing = evidence.findById(evidenceId).orElseThrow();
            existing.setCommitSha("b".repeat(40));
            evidence.saveAndFlush(existing);
        });
        assertEquals("a".repeat(40), evidence.findById(evidenceId).orElseThrow().getCommitSha());
        assertEquals("{\"files\": []}", jdbc.queryForObject("SELECT evidence::text FROM questionnaire_evidence WHERE id=?", String.class, evidenceId));
    }

    @Test
    void databaseEnforcesFrozenMemberAndPositionUniquenessAndExactWindow() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO questionnaire_assignments(snapshot_id,student_id,student_name) VALUES (?,?,'Duplicate')", snapshot, owner));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO questionnaire_questions(assignment_id,position,prompt,source_references,hidden_rubric) VALUES (?,1,'Duplicate','[]','Private')", assignment));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE questionnaire_questions SET position=6 WHERE id=?", questions.get(0)));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE questionnaire_snapshots SET closes_at=opens_at + INTERVAL '47 hours' WHERE id=?", snapshot));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE questionnaire_questions SET ai_score=21 WHERE id=?", questions.get(0)));
    }

    @Test
    void publicationCannotPersistOnlyOneWindowBoundary() {
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE questionnaire_snapshots SET closes_at=NULL WHERE id=?", snapshot));
    }

    @Test
    void durableJobsDeduplicateAndAuditPreservesOriginalAiMark() {
        String key = "generate-member/" + assignment;
        jdbc.update("INSERT INTO questionnaire_jobs(snapshot_id,assignment_id,job_key,kind) VALUES (?,?,?,'GENERATE_MEMBER')", snapshot, assignment, key);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO questionnaire_jobs(snapshot_id,assignment_id,job_key,kind) VALUES (?,?,?,'GENERATE_MEMBER')", snapshot, assignment, key));
        jdbc.update("UPDATE questionnaire_questions SET ai_score=12,effective_score=15 WHERE id=?", questions.get(0));
        jdbc.update("INSERT INTO questionnaire_mark_adjustments(question_id,actor_id,previous_score,adjusted_score,reason,adjusted_at) "
                + "VALUES (?,?,12,15,'Coordinator evidence review',?::timestamptz)", questions.get(0), owner, now.toString());
        assertEquals(12, jdbc.queryForObject("SELECT ai_score FROM questionnaire_questions WHERE id=?", Integer.class, questions.get(0)));
        assertEquals(15, jdbc.queryForObject("SELECT adjusted_score FROM questionnaire_mark_adjustments WHERE question_id=?", Integer.class, questions.get(0)));
    }

    private QuestionnaireAnswerRequest request(String answer, long revision) {
        return new QuestionnaireAnswerRequest(revision, questions.stream()
                .map(q -> new QuestionnaireAnswerRequest.Answer(q, answer)).toList());
    }

    private MockMvc http() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner, null));
        return MockMvcBuilders.standaloneSetup(new QuestionnaireController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> value = new AtomicReference<>();
        void set(Instant instant) { value.set(instant); }
        public Instant instant() { return value.get(); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
    }

    @TestConfiguration @Import(QuestionnaireService.class)
    static class AssessmentTestConfig {
        @Bean MutableClock clock() { return new MutableClock(); }
    }
}
