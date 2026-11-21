package com.talentbridge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.repository.*;
import com.talentbridge.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringJUnitConfig(classes = {DatabaseTestConfig.class, QuestionnaireGenerationDatabaseIT.Config.class})
@TestPropertySource(properties = {"jwt.secret=synthetic-questionnaire-integration-signing-key-not-production",
        "jwt.expiration-ms=60000", "jwt.refresh-expiration-ms=120000", "app.questionnaire-enabled=true"})
class QuestionnaireGenerationDatabaseIT {
    @Autowired QuestionnaireGenerationService worker;
    @Autowired QuestionnaireFinalSubmissionService finals;
    @Autowired SubmissionService drafts;
    @Autowired QuestionnaireService owners;
    @Autowired AssessmentTaskQueue queue;
    @Autowired GitHubEvidenceService github;
    @Autowired AssessmentAIService ai;
    @Autowired QuestionnaireSnapshotRepository snapshots;
    @Autowired QuestionnaireAssignmentRepository assignments;
    @Autowired QuestionnaireJobRepository jobs;
    @Autowired MutableClock clock;
    @Autowired DataSource source;
    JdbcTemplate jdbc;
    UUID leader, member, party, project, submission;
    final Instant now = Instant.parse("2026-11-21T12:00:00Z");
    final GitHubEvidence.Resolution resolution = new GitHubEvidence.Resolution(
            new GitHubEvidence.Repository("example", "synthetic", "https://github.com/example/synthetic"),
            "main", "main", "a".repeat(40), "b".repeat(40));

    @BeforeEach void fixture() {
        reset(queue, github, ai); jdbc = new JdbcTemplate(source); clock.set(now);
        leader=UUID.randomUUID(); member=UUID.randomUUID(); party=UUID.randomUUID(); project=UUID.randomUUID(); submission=UUID.randomUUID();
        for (UUID id : List.of(leader, member)) jdbc.update("INSERT INTO users(id,email,password,first_name,last_name,role,status,github_username) VALUES (?,?,?,'Synthetic','Member','STUDENT','APPROVED',?)",
                id, id+"@example.test", "synthetic", id.equals(leader)?"frozen-leader":"frozen-member");
        jdbc.update("INSERT INTO projects(id,title,created_by_id,status,scope,deliverables,deadline) VALUES (?,'Frozen project',?,'ASSIGNED','Frozen scope','Frozen deliverables',?)",
                project, leader, LocalDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC));
        jdbc.update("INSERT INTO parties(id,name,leader_id,assigned_project_id,status) VALUES (?,'Synthetic team',?,?,'ASSIGNED')",party,leader,project);
        for (UUID id : List.of(leader,member)) jdbc.update("INSERT INTO party_members(party_id,user_id) VALUES (?,?)",party,id);
        jdbc.update("INSERT INTO submissions(id,party_id,project_id,status,repo_url,repo_branch) VALUES (?,?,?,'DRAFT',?,'main')",submission,party,project,resolution.repository().url());
        when(github.resolveRepository(anyString(),any(),any())).thenAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return resolution; });
        doAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return null; }).when(queue).enqueue(any(),anyInt(),any());
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM notifications WHERE recipient_id IN (?,?)",leader,member);
        jdbc.update("DELETE FROM questionnaire_job_attempts WHERE job_id IN (SELECT j.id FROM questionnaire_jobs j JOIN questionnaire_snapshots s ON s.id=j.snapshot_id WHERE s.submission_id=?)",submission);
        jdbc.update("DELETE FROM questionnaire_jobs WHERE snapshot_id IN (SELECT id FROM questionnaire_snapshots WHERE submission_id=?)",submission);
        jdbc.update("DELETE FROM questionnaire_questions WHERE assignment_id IN (SELECT a.id FROM questionnaire_assignments a JOIN questionnaire_snapshots s ON s.id=a.snapshot_id WHERE s.submission_id=?)",submission);
        jdbc.update("DELETE FROM questionnaire_assignments WHERE snapshot_id IN (SELECT id FROM questionnaire_snapshots WHERE submission_id=?)",submission);
        jdbc.update("DELETE FROM questionnaire_evidence WHERE snapshot_id IN (SELECT id FROM questionnaire_snapshots WHERE submission_id=?)",submission);
        jdbc.update("DELETE FROM questionnaire_snapshots WHERE submission_id=?",submission);
        jdbc.update("DELETE FROM submissions WHERE id=?",submission);
        jdbc.update("DELETE FROM party_members WHERE party_id=?",party);
        jdbc.update("DELETE FROM parties WHERE id=?",party);
        jdbc.update("DELETE FROM projects WHERE id=?",project);
        jdbc.update("DELETE FROM users WHERE id IN (?,?)",leader,member);
    }

    @Test void finalRequiresSavedVersionAndFlushReturnsThePostUpdateVersion() {
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> finals.finalSubmit(party,leader,null)).getStatusCode().value());
        var saved=drafts.saveDraft(party,leader,null,"release",null,"Saved note");
        assertEquals(1,saved.getVersion());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> finals.finalSubmit(party,leader,0L)).getStatusCode().value());
        verifyNoInteractions(github);
    }

    @Test void freezesExactAcceptanceInputsAndRetainsOwnerAccessWithoutMembership() {
        var accepted=finals.finalSubmit(party,leader,0L);
        assertEquals(2,accepted.getAssessmentVersion()); assertEquals(1,accepted.getVersion());
        var snapshot=snapshot();
        assertEquals(resolution.commitSha(),snapshot.getFrozenResolution().path("commitSha").asText());
        assertTrue(snapshot.getFrozenBrief().contains("Frozen scope")); assertEquals("Frozen project", snapshot.getFrozenLabel());
        assertEquals(2,assignments.findAllBySnapshotId(snapshot.getId()).size());
        jdbc.update("DELETE FROM party_members WHERE party_id=?",party);
        jdbc.update("UPDATE users SET first_name='Changed',github_username='changed' WHERE id=?",member);
        jdbc.update("UPDATE projects SET title='Changed',scope='Changed' WHERE id=?",project);
        var assignment=assignments.findAllBySnapshotId(snapshot.getId()).stream().filter(a->a.getStudentId().equals(member)).findFirst().orElseThrow();
        assertEquals("frozen-member",assignment.getGithubLogin());
        assertEquals("Frozen project",owners.get(assignment.getId(),member).projectTitle());
        assertEquals("Frozen project",owners.list(member).get(0).projectTitle());
        verify(queue).enqueue(any(),eq(0),eq(now));
    }

    @Test void revalidatesDeadlineAndDraftAfterResolutionWithoutAcceptingPartialEnrollment() {
        when(github.resolveRepository(anyString(),any(),any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            jdbc.update("UPDATE projects SET deadline=? WHERE id=?", LocalDateTime.ofInstant(now,ZoneOffset.UTC),project); return resolution;
        });
        assertThrows(RuntimeException.class, ()->finals.finalSubmit(party,leader,0L));
        assertEquals("DRAFT",jdbc.queryForObject("SELECT status FROM submissions WHERE id=?",String.class,submission));
        assertTrue(snapshots.findBySubmissionIdAndAssessmentVersion(submission,2).isEmpty()); verifyNoInteractions(queue);
    }

    @Test void durableDispatchGapIsVisibleAndCoordinatorRetryDoesNotRepin() {
        doThrow(new AssessmentTaskQueue.DispatchException("DISPATCH_UNAVAILABLE")).when(queue).enqueue(any(),anyInt(),any());
        finals.finalSubmit(party,leader,0L);
        var review=worker.review(submission); assertEquals("DISPATCH_UNAVAILABLE",review.jobs().get(0).errorCode());
        reset(queue); worker.retry(submission);
        verify(github,times(1)).resolveRepository(anyString(),any(),any());
        assertEquals(1,worker.review(submission).jobs().get(0).dispatchGeneration());
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 101, 255})
    void omittedDefaultBranchPersistsExactlyAndRecoveryNeverRepins(int length) {
        jdbc.update("DELETE FROM submissions WHERE id=?",submission);
        var saved=drafts.saveDraft(party,leader,resolution.repository().url(),null,null,"Default branch");
        submission=saved.getId();
        assertNull(saved.getRepoBranch());
        String branch="a".repeat(length);
        var resolved=new GitHubEvidence.Resolution(resolution.repository(),null,branch,resolution.commitSha(),resolution.treeSha());
        when(github.resolveRepository(eq(resolution.repository().url()),isNull(),any())).thenReturn(resolved);
        var accepted=finals.finalSubmit(party,leader,saved.getVersion());
        assertEquals(SubmissionStatus.SUBMITTED,accepted.getStatus());
        assertEquals(2,accepted.getAssessmentVersion());
        var frozen=snapshot().getFrozenResolution().deepCopy();
        assertTrue(frozen.path("requestedBranch").isNull());
        assertEquals(branch,frozen.path("resolvedBranch").asText());
        assertEquals(resolved.commitSha(),frozen.path("commitSha").asText());
        assertEquals(resolved.treeSha(),frozen.path("treeSha").asText());
        UUID evidenceJob=activeJob();
        var captured=new GitHubEvidence(resolved,now,List.of(),List.of(),
                new GitHubEvidence.ContributorSnapshot(now,false,false,List.of()),List.of(),
                new GitHubEvidence.Bounds(16,32000,0,4000,8,1000,128,8192,65536,262144,2097152,100));
        when(github.capture(eq(resolved),anyString(),any()))
                .thenThrow(new IllegalStateException("Synthetic evidence outage")).thenReturn(captured);
        worker.handle(evidenceJob);
        var failed=jobs.findById(evidenceJob).orElseThrow();
        assertEquals(QuestionnaireJobStatus.FAILED,failed.getStatus());
        assertEquals("EVIDENCE_UNAVAILABLE",failed.getLastErrorCode());
        assertEquals(frozen,snapshot().getFrozenResolution());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM questionnaire_evidence WHERE snapshot_id=?",Integer.class,snapshot().getId()));
        worker.retry(submission);
        UUID previous=evidenceJob;
        evidenceJob=activeJob();
        assertEquals(previous,jobs.findById(evidenceJob).orElseThrow().getRetryOf());
        worker.handle(evidenceJob);
        assertEquals(QuestionnaireJobStatus.SUCCEEDED,jobs.findById(evidenceJob).orElseThrow().getStatus(),
                "Accepted default branch of length "+length+" must survive evidence persistence");
        assertEquals(branch,jdbc.queryForObject("SELECT repository_branch FROM questionnaire_evidence WHERE snapshot_id=?",String.class,snapshot().getId()));
        assertEquals(resolved.commitSha(),jdbc.queryForObject("SELECT commit_sha FROM questionnaire_evidence WHERE snapshot_id=?",String.class,snapshot().getId()));
        assertTrue(jdbc.queryForObject("SELECT evidence->'resolution' = ?::jsonb FROM questionnaire_evidence WHERE snapshot_id=?",
                Boolean.class,frozen.toString(),snapshot().getId()));
        UUID next=activeJob();
        assertEquals(QuestionnaireJobKind.GENERATE_MEMBER,jobs.findById(next).orElseThrow().getKind());
        assertEquals(QuestionnaireJobStatus.DISPATCHED,jobs.findById(next).orElseThrow().getStatus());
        verify(queue).enqueue(next,0,now);
        worker.retry(submission);
        assertEquals(next,activeJob());
        assertEquals(frozen,snapshot().getFrozenResolution());
        assertEquals(branch,jdbc.queryForObject("SELECT repository_branch FROM questionnaire_evidence WHERE snapshot_id=?",String.class,snapshot().getId()));
        verify(github,times(1)).resolveRepository(eq(resolution.repository().url()),isNull(),any());
        verify(github,times(2)).capture(eq(resolved),anyString(),any());
        verifyNoMoreInteractions(github);
        verifyNoInteractions(ai);
    }

    @Test void busyLeaseReturnsRetryableStatusAndExpiredCrashLeaseCanBeRecovered() {
        finals.finalSubmit(party,leader,0L); UUID job=worker.review(submission).jobs().get(0).id();
        jdbc.update("UPDATE questionnaire_jobs SET status='RUNNING',lease_token=?,lease_until=?::timestamptz WHERE id=?",UUID.randomUUID(),now.plusSeconds(110).toString(),job);
        assertEquals(503,assertThrows(ResponseStatusException.class,()->worker.handle(job)).getStatusCode().value());
        clock.set(now.plusSeconds(111));
        when(github.capture(eq(resolution),anyString(),any())).thenThrow(new IllegalStateException("private provider details"));
        worker.handle(job);
        assertEquals("EVIDENCE_UNAVAILABLE",worker.review(submission).jobs().get(0).errorCode());
        assertNull(snapshot().getOpensAt());
    }

    @Test void acceptsFullLegitimateApprovedProjectBriefWithoutTruncation() throws Exception {
        String text="x".repeat(2000);
        jdbc.update("UPDATE projects SET description=?,scope=?,deliverables=?,evaluation_criteria=? WHERE id=?",text,text,text,text,project);
        finals.finalSubmit(party,leader,0L);
        assertTrue(snapshot().getFrozenBrief().length()>8000);
        assertEquals(text,new ObjectMapper().readTree(snapshot().getFrozenBrief()).path("description").asText());
    }

    @Test void staleWorkerCannotAcknowledgeTheOnlyTaskWhileReplacementWorkerMayCrash() throws Exception {
        finals.finalSubmit(party,leader,0L); UUID job=worker.review(submission).jobs().get(0).id();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(github.capture(eq(resolution),anyString(),any())).thenAnswer(call -> { entered.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS)); throw new IllegalStateException("synthetic"); });
        var executor=Executors.newSingleThreadExecutor();
        try {
            var old=executor.submit(() -> worker.handle(job)); assertTrue(entered.await(10,TimeUnit.SECONDS));
            jdbc.update("UPDATE questionnaire_jobs SET lease_token=?,lease_until=?::timestamptz WHERE id=?",UUID.randomUUID(),now.plusSeconds(110).toString(),job);
            release.countDown();
            var failure=assertThrows(ExecutionException.class,()->old.get(10,TimeUnit.SECONDS));
            assertEquals(503,((ResponseStatusException)failure.getCause()).getStatusCode().value());
            assertEquals(QuestionnaireJobStatus.RUNNING,worker.review(submission).jobs().get(0).status());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void allReadyPublishesOnceWithCommon48HoursAndFrozenMemberNotifications() {
        evidenceReady();
        var first=activeJob(); successfulGeneration(); worker.handle(first);
        assertNull(snapshot().getOpensAt()); assertEquals(0,notificationCount());
        var second=activeJob(); worker.handle(second);
        assertEquals(now,snapshot().getOpensAt()); assertEquals(now.plus(Duration.ofHours(48)),snapshot().getClosesAt());
        assertEquals(2,notificationCount());
        var ids=jdbc.queryForList("SELECT q.id FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id=? ORDER BY q.id",UUID.class,snapshot().getId());
        clock.set(now.plusSeconds(3600)); worker.handle(first); worker.handle(second); worker.retry(submission);
        assertEquals(now,snapshot().getOpensAt()); assertEquals(2,notificationCount());
        assertEquals(ids,jdbc.queryForList("SELECT q.id FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id=? ORDER BY q.id",UUID.class,snapshot().getId()));
        verify(ai,times(2)).generate(any(),any(Runnable.class));
    }

    @Test void failuresCountActualAttemptsAndManualRecoveryCreatesLinkedJobWithoutErasingHistory() {
        evidenceReady(); UUID job=activeJob();
        when(ai.generate(any(),any(Runnable.class))).thenAnswer(call -> { ((Runnable)call.getArgument(1)).run(); throw new IllegalStateException("raw private provider details"); });
        for (int i=1;i<=3;i++) { worker.handle(job); clock.set(clock.instant().plusSeconds(30)); }
        worker.handle(job);
        assertEquals(3,jobs.findById(job).orElseThrow().getAttempts());
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM questionnaire_job_attempts WHERE job_id=?",Integer.class,job));
        assertEquals(QuestionnaireJobStatus.FAILED,jobs.findById(job).orElseThrow().getStatus()); assertNull(snapshot().getOpensAt());
        var recovery=worker.retry(submission); var replacement=recovery.jobs().stream().filter(j->job.equals(j.retryOf())).findFirst().orElseThrow();
        assertEquals(0,replacement.attempts()); assertEquals(3,jobs.findById(job).orElseThrow().getAttempts());
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM questionnaire_job_attempts WHERE job_id=?",Integer.class,job));
        verify(ai,times(3)).generate(any(),any(Runnable.class));
    }

    @Test void quotaDefersUntilExactKarachiMidnightAndForcedEarlyDeliverySchedulesANewGeneration() {
        evidenceReady(); UUID job=activeJob(); Instant reset=Instant.parse("2026-11-21T19:00:00Z");
        when(ai.generate(any(),any(Runnable.class))).thenThrow(new AssessmentQuotaService.QuotaExceeded(reset));
        worker.handle(job);
        var deferred=jobs.findById(job).orElseThrow(); assertEquals(QuestionnaireJobStatus.DEFERRED,deferred.getStatus());
        assertEquals(0,deferred.getAttempts()); assertEquals(reset,deferred.getNextAttemptAt()); assertEquals(1,deferred.getDispatchGeneration());
        worker.handle(job); assertEquals(2,jobs.findById(job).orElseThrow().getDispatchGeneration());
        verify(queue).enqueue(job,2,reset); verify(ai,times(1)).generate(any(),any(Runnable.class));
        assertNull(snapshot().getOpensAt());
    }

    @Test void concurrentDuplicateCannotMakeAnotherActualAttemptAndNextMemberSeesAllPriorPrompts() throws Exception {
        evidenceReady(); UUID job=activeJob(); successfulGeneration();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        when(ai.generate(any(),any(Runnable.class))).thenAnswer(call -> {
            ((Runnable)call.getArgument(1)).run(); entered.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS));
            var result=new ArrayList<AssessmentAIService.Question>();
            for(int i=0;i<5;i++) result.add(new AssessmentAIService.Question(generatedIds.get(i),"Synthetic distinct prompt for concurrency "+i,"Private rubric",List.of(new GitHubEvidence.SourceReference("src/Engine.java",1,2))));
            return new AssessmentAIService.GeneratedQuestions(generatedMember,result);
        });
        var executor=Executors.newSingleThreadExecutor();
        try {
            var first=executor.submit(()->worker.handle(job)); assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertEquals(503,assertThrows(ResponseStatusException.class,()->worker.handle(job)).getStatusCode().value());
            assertEquals(1,jobs.findById(job).orElseThrow().getAttempts());
            release.countDown(); first.get(10,TimeUnit.SECONDS);
            successfulGeneration(); worker.handle(activeJob()); assertEquals(5,capturedPrior.size());
            verify(github,times(1)).capture(eq(resolution),anyString(),any());
            assertEquals(2,notificationCount());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void retryRetainsPublishedAnswersIdsAndCommonWindow() {
        evidenceReady(); successfulGeneration(); worker.handle(activeJob()); worker.handle(activeJob());
        var mine=owners.list(member).get(0); var detail=owners.get(mine.id(),member);
        var answerEntries=detail.questions().stream().map(q->new com.talentbridge.dto.request.QuestionnaireAnswerRequest.Answer(q.id(),"Saved private answer")).toList();
        owners.save(mine.id(),member,new com.talentbridge.dto.request.QuestionnaireAnswerRequest(0L,answerEntries));
        worker.retry(submission);
        var reloaded=owners.get(mine.id(),member); assertEquals(1,reloaded.revision());
        assertEquals(detail.questions().stream().map(q->q.id()).toList(),reloaded.questions().stream().map(q->q.id()).toList());
        assertTrue(reloaded.questions().stream().allMatch(q->q.answer().equals("Saved private answer")));
        assertEquals(detail.opensAt(),reloaded.opensAt()); assertEquals(detail.closesAt(),reloaded.closesAt());
    }

    @Test void draftLeaderAndAssignedProjectAreRevalidatedAfterResolutionAndMissingEvidenceNeverPublishes() {
        when(github.resolveRepository(anyString(),any(),any())).thenAnswer(call -> {
            jdbc.update("UPDATE submissions SET version=version+1,notes='New saved draft' WHERE id=?",submission); return resolution;
        });
        assertEquals(409,assertThrows(ResponseStatusException.class,()->finals.finalSubmit(party,leader,0L)).getStatusCode().value());
        assertTrue(snapshots.findBySubmissionIdAndAssessmentVersion(submission,2).isEmpty());
        doReturn(resolution).when(github).resolveRepository(anyString(),any(),any());
        finals.finalSubmit(party,leader,1L); var job=activeJob();
        when(github.capture(eq(resolution),anyString(),any())).thenThrow(new GitHubEvidence.EvidenceException(GitHubEvidence.Failure.NOT_FOUND,404));
        worker.handle(job); assertEquals(QuestionnaireState.FAILED,snapshot().getState()); assertNull(snapshot().getClosesAt());
        verifyNoInteractions(ai);
    }

    @Test void retryWaitingForPublicationLockReadsTheCommittedWindowNotItsStalePreLockSnapshot() throws Exception {
        finals.finalSubmit(party,leader,0L); UUID id=snapshot().getId();
        var executor=Executors.newSingleThreadExecutor();
        try (var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement=connection.prepareStatement("SELECT id FROM questionnaire_snapshots WHERE id=? FOR UPDATE")) {
                statement.setObject(1,id); statement.executeQuery().close();
            }
            var waiting=executor.submit(()->worker.retry(submission));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            int blocked=0;
            while (blocked==0 && System.nanoTime()<deadline) blocked=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename=current_user AND wait_event_type='Lock'",Integer.class);
            assertTrue(blocked>0,"Recovery must be waiting on the held snapshot lock");
            try (var publish=connection.prepareStatement("UPDATE questionnaire_snapshots SET state='OPEN',opens_at=?::timestamptz,closes_at=?::timestamptz WHERE id=?")) {
                publish.setString(1,now.toString()); publish.setString(2,now.plus(Duration.ofHours(48)).toString()); publish.setObject(3,id); publish.executeUpdate();
            }
            try (var done=connection.prepareStatement("UPDATE questionnaire_jobs SET status='SUCCEEDED' WHERE snapshot_id=?")) { done.setObject(1,id); done.executeUpdate(); }
            connection.commit();
            assertEquals(QuestionnaireState.OPEN,waiting.get(5,TimeUnit.SECONDS).state());
            assertEquals(now,snapshot().getOpensAt());
        } finally { executor.shutdownNow(); }
    }

    @Test void acceptanceWaitingForPartyLockRevalidatesTheNewLeaderWithoutRestoringTheOldOne() throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        try (var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT id FROM parties WHERE id=? FOR UPDATE")) { lock.setObject(1,party); lock.executeQuery().close(); }
            var waiting=executor.submit(()->finals.finalSubmit(party,leader,0L));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1); int blocked=0;
            while(blocked==0 && System.nanoTime()<deadline) blocked=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename=current_user AND wait_event_type='Lock'",Integer.class);
            assertTrue(blocked>0);
            try(var change=connection.prepareStatement("UPDATE parties SET leader_id=? WHERE id=?")) { change.setObject(1,member); change.setObject(2,party); change.executeUpdate(); }
            connection.commit();
            var failure=assertThrows(ExecutionException.class,()->waiting.get(5,TimeUnit.SECONDS));
            assertInstanceOf(com.talentbridge.exception.ForbiddenException.class,failure.getCause());
            assertEquals(member,jdbc.queryForObject("SELECT leader_id FROM parties WHERE id=?",UUID.class,party));
            assertEquals("DRAFT",jdbc.queryForObject("SELECT status FROM submissions WHERE id=?",String.class,submission));
        } finally { executor.shutdownNow(); }
    }

    @Test void notificationPersistenceFailureRollsBackTheWholePublicationAndNeverStartsTheClock() {
        evidenceReady(); successfulGeneration(); worker.handle(activeJob()); UUID last=activeJob();
        String trigger="task23_notification_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION "+trigger+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.recipient_id='"+leader+"'::uuid THEN RAISE EXCEPTION 'SYNTHETIC_NOTIFICATION_FAILURE'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION "+trigger+"()");
        try {
            worker.handle(last);
            assertNull(snapshot().getOpensAt()); assertNull(snapshot().getClosesAt()); assertEquals(0,notificationCount());
            assertEquals(QuestionnaireJobStatus.DISPATCHED,jobs.findById(last).orElseThrow().getStatus());
            assertEquals(1,jobs.findById(last).orElseThrow().getAttempts());
            assertEquals(5,jdbc.queryForObject("SELECT count(*) FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id=?",Integer.class,snapshot().getId()));
        } finally { jdbc.execute("DROP TRIGGER "+trigger+" ON notifications"); jdbc.execute("DROP FUNCTION "+trigger+"()"); }
        clock.set(now.plusSeconds(30)); worker.handle(last);
        assertEquals(clock.instant(),snapshot().getOpensAt()); assertEquals(2,notificationCount());
    }

    UUID activeJob() { return worker.review(submission).jobs().stream().filter(j->j.status()!=QuestionnaireJobStatus.SUCCEEDED && j.status()!=QuestionnaireJobStatus.FAILED).findFirst().orElseThrow().id(); }
    int notificationCount() { return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id IN (?,?)",Integer.class,leader,member); }
    void evidenceReady() {
        finals.finalSubmit(party,leader,0L);
        var captured=new GitHubEvidence(resolution,now,List.of(),List.of(new GitHubEvidence.FileEvidence(GitHubEvidence.Kind.SOURCE,"src/Engine.java","c".repeat(40),"d".repeat(64),22,2,1,2,"class Engine {\n}\n",false)),
                new GitHubEvidence.ContributorSnapshot(now,false,false,List.of(new GitHubEvidence.Contributor("frozen-leader",4))),List.of(),
                new GitHubEvidence.Bounds(16,32000,17,4000,8,1000,128,8192,65536,262144,2097152,100));
        when(github.capture(eq(resolution),anyString(),any())).thenAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return captured; });
        worker.handle(activeJob());
        when(ai.prepareGeneration(anyString(),any(),any(),anyList(),anyList())).thenAnswer(call -> {
            generatedMember=call.getArgument(2); generatedIds=call.getArgument(3); capturedPrior=call.getArgument(4); return null;
        });
    }
    UUID generatedMember; List<UUID> generatedIds; List<String> capturedPrior;
    void successfulGeneration() {
        when(ai.generate(any(),any(Runnable.class))).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); ((Runnable)call.getArgument(1)).run();
            var result=new ArrayList<AssessmentAIService.Question>();
            for (int i=0;i<5;i++) result.add(new AssessmentAIService.Question(generatedIds.get(i),"Explain synthetic implementation boundary "+generatedMember+" question "+i,"Private rubric",List.of(new GitHubEvidence.SourceReference("src/Engine.java",1,2))));
            return new AssessmentAIService.GeneratedQuestions(generatedMember,result);
        });
    }

    QuestionnaireSnapshot snapshot() { return snapshots.findBySubmissionIdAndAssessmentVersion(submission,2).orElseThrow(); }

    static class MutableClock extends Clock {
        final AtomicReference<Instant> value=new AtomicReference<>(); void set(Instant time){value.set(time);}
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return value.get();}
    }
    @Configuration @Import({SubmissionService.class,QuestionnaireFinalSubmissionService.class,QuestionnaireGenerationService.class,QuestionnaireService.class})
    static class Config {
        @Bean MutableClock clock(){return new MutableClock();}
        @Bean ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
        @Bean AssessmentTaskQueue queue(){return mock(AssessmentTaskQueue.class);}
        @Bean AssessmentAIService ai(){return mock(AssessmentAIService.class);}
        @Bean GitHubEvidenceService github(){return mock(GitHubEvidenceService.class);}
        @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
    }
}
