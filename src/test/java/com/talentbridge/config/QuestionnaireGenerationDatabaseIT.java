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
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
    private static final long REVIEW_READ_DELAY_MS=750;
    private static final long RETRY_SQL_DELAY_MS=130;
    private static final long RETRY_HISTORY_DELAY_MS=1200;
    private static final long RETRY_ATTEMPT_DELAY_MS=430;
    private static final long RETRY_DISPATCH_SQL_DELAY_MS=550;
    @Autowired QuestionnaireGenerationService worker;
    @Autowired QuestionnaireEvaluationService evaluation;
    @Autowired ProfileFetchProbe profileFetchProbe;
    @Autowired PreparationMapper inputMapper;
    @Autowired QuestionnaireFinalSubmissionService finals;
    @Autowired SubmissionService drafts;
    @Autowired QuestionnaireService owners;
    @Autowired AssessmentTaskQueue queue;
    @Autowired GitHubEvidenceService github;
    @Autowired AssessmentAIService ai;
    @Autowired NotificationService legacyNotifications;
    @Autowired QuestionnaireSnapshotRepository snapshots;
    @Autowired QuestionnaireAssignmentRepository assignments;
    @Autowired QuestionnaireJobRepository jobs;
    @Autowired MutableClock clock;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager transactions;
    JdbcTemplate jdbc;
    UUID leader, member, party, project, submission;
    UUID universitySupervisor, projectSupervisor;
    final Instant now = Instant.parse("2026-11-21T12:00:00Z");
    final GitHubEvidence.Resolution resolution = new GitHubEvidence.Resolution(
            new GitHubEvidence.Repository("example", "synthetic", "https://github.com/example/synthetic"),
            "main", "main", "a".repeat(40), "b".repeat(40));

    @BeforeEach void fixture() {
        inputMapper.resetProbe();
        reset(queue, github, ai, legacyNotifications); jdbc = new JdbcTemplate(source); clock.set(now);
        universitySupervisor=null; projectSupervisor=null;
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
        inputMapper.resetProbe();
        jdbc.update("DELETE FROM notifications WHERE recipient_id IN (?,?)",leader,member);
        if(universitySupervisor!=null) jdbc.update("DELETE FROM notifications WHERE recipient_id IN (?,?)",universitySupervisor,projectSupervisor);
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
        if(universitySupervisor!=null) jdbc.update("DELETE FROM users WHERE id IN (?,?)",universitySupervisor,projectSupervisor);
    }

    @Test void enabledAcceptanceHttpPersistsBothSupervisorNoticesOnceAndDuplicateCannotRepeatThem() throws Exception {
        attachSupervisors();
        assertEquals(200,submitHttp(leader,0)); assertSupervisorNotices();
        assertEquals("SUBMITTED",jdbc.queryForObject("SELECT status FROM submissions WHERE id=?",String.class,submission));
        assertEquals(2,assignments.findAllBySnapshotId(snapshot().getId()).size());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM questionnaire_jobs WHERE snapshot_id=?",Integer.class,snapshot().getId()));
        assertEquals(409,submitHttp(leader,0)); assertSupervisorNotices();
        verify(github,times(1)).resolveRepository(anyString(),any(),any()); verify(queue,times(1)).enqueue(any(),eq(0),eq(now));
        verifyNoInteractions(ai,legacyNotifications);
    }

    @Test void reviewHttpStaysWithinBudgetWithoutSerialEntityReads() throws Exception {
        try(var http=new WorkerHttp(worker,evaluation,drafts,finals)) {
            assertEquals(404,http.review(submission).statusCode());
            assertEquals(404,http.review(UUID.randomUUID()).statusCode());
            finals.finalSubmit(party,leader,0L);
            UUID snapshotId=snapshot().getId();
            UUID assignment=assignments.findAllBySnapshotId(snapshotId).stream()
                    .filter(a->a.getStudentId().equals(leader)).findFirst().orElseThrow().getId();
            jdbc.update("UPDATE questionnaire_assignments SET answers=CAST(? AS jsonb) WHERE id=?",
                    inputMapper.writeValueAsString(Map.of("empty","","blank"," \t\n\u001c\u2003",
                            "nbsp","\u00a0","figureSpace","\u2007","answer","private answer")),assignment);
            jdbc.update("""
                    INSERT INTO questionnaire_questions(assignment_id,position,prompt,source_references,hidden_rubric)
                    VALUES (?,1,'private prompt','[]','private rubric')
                    """,assignment);
            UUID first=jobs.findAllBySnapshotIdOrderByCreatedAtAsc(snapshotId).get(0).getId();
            UUID retry=UUID.randomUUID();
            jdbc.update("UPDATE questionnaire_jobs SET created_at='2026-11-20 00:00:00' WHERE id=?",first);
            jdbc.update("""
                    INSERT INTO questionnaire_jobs(id,snapshot_id,job_key,kind,status,attempts,dispatch_generation,
                                                   next_attempt_at,lease_token,lease_until,last_error_code,retry_of,created_at)
                    VALUES (?,?,?,'EVIDENCE','RUNNING',2,3,'2026-11-21T12:00:00Z',?,
                            '2026-11-21T12:00:30Z','SYNTHETIC',?,'2026-11-21 00:00:00')
                    """,retry,snapshotId,retry.toString(),UUID.randomUUID(),first);
            java.net.http.HttpResponse<String> response;
            long start=System.nanoTime();
            profileFetchProbe.reviewReads.clear();
            profileFetchProbe.reviewLatency=true;
            try { response=http.review(submission); }
            finally { profileFetchProbe.reviewLatency=false; }
            long elapsedMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            System.out.println("REVIEW_HTTP status="+response.statusCode()+" elapsedMs="+elapsedMs+
                    " delayedReads="+profileFetchProbe.reviewReads.size()+
                    " failure="+http.logs.list.stream().map(e->e.getThrowableProxy()).filter(Objects::nonNull)
                            .map(e->e.getClassName()+": "+e.getMessage()).toList());
            assertEquals(200,response.statusCode());
            assertEquals(1,profileFetchProbe.reviewReads.size(),"Review must execute exactly one delayed SQL read");
            var read=profileFetchProbe.reviewReads.element();
            assertTrue(read.sql().stripLeading().startsWith("SELECT"));
            assertTrue(read.inTransaction() && read.timeoutSeconds()>0 && read.timeoutSeconds()<=2,
                    "Review SELECT must run within the two-second transaction budget");
            assertTrue(elapsedMs>=REVIEW_READ_DELAY_MS,"Review did not incur the injected SQL delay");
            assertTrue(elapsedMs<2000,"Review HTTP request took "+elapsedMs+" ms");
            assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
            var review=inputMapper.readValue(response.body(),QuestionnaireGenerationService.Review.class);
            assertEquals(submission,review.submissionId());
            assertEquals("Frozen project",review.projectTitle());
            assertEquals(QuestionnaireState.GENERATING,review.state());
            assertNull(review.opensAt()); assertNull(review.closesAt()); assertEquals(now,review.serverNow());
            assertEquals(2,review.members().size());
            assertTrue(review.members().stream().anyMatch(m->m.assignmentId().equals(assignment) &&
                    m.studentId().equals(leader) && m.questionCount()==1 && m.answeredCount()==3));
            assertTrue(review.members().stream().anyMatch(m->m.studentId().equals(member) && m.questionCount()==0 && m.answeredCount()==0));
            assertEquals(List.of(first,retry),review.jobs().stream().map(QuestionnaireGenerationService.Job::id).toList());
            assertEquals(new QuestionnaireGenerationService.Job(retry,first,QuestionnaireJobKind.EVIDENCE,
                    QuestionnaireJobStatus.RUNNING,2,3,now,now.plusSeconds(30),"SYNTHETIC"),review.jobs().get(1));
            assertFalse(response.body().contains("private"));
            jdbc.update("UPDATE questionnaire_snapshots SET assessment_version=1 WHERE id=?",snapshotId);
            assertEquals(404,http.review(submission).statusCode());
        }
    }

    @ParameterizedTest @ValueSource(strings={"stale","nonLeader","expired"})
    void rejectedAcceptanceHttpNeverPersistsNoticesOrEnrollment(String rejection) throws Exception {
        attachSupervisors();
        if(rejection.equals("expired")) jdbc.update("UPDATE projects SET deadline=? WHERE id=?",LocalDateTime.ofInstant(now,ZoneOffset.UTC),project);
        int status=submitHttp(rejection.equals("nonLeader")?member:leader,rejection.equals("stale")?1:0);
        assertEquals(rejection.equals("stale")?409:rejection.equals("nonLeader")?403:400,status);
        assertAcceptanceUnchanged(); verifyNoInteractions(github,queue,ai,legacyNotifications);
    }

    @Test void supervisorNoticeInsertFailureRollsBackAcceptanceAndBothNoticesBeforeDispatch() throws Exception {
        attachSupervisors(); String trigger="task5_notice_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION "+trigger+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.recipient_id='"+projectSupervisor+"'::uuid AND NEW.type='SUBMISSION_RECEIVED' THEN RAISE EXCEPTION 'SYNTHETIC_ACCEPTANCE_NOTICE_FAILURE'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION "+trigger+"()");
        try {
            assertEquals(500,submitHttp(leader,0)); assertAcceptanceUnchanged(); verifyNoInteractions(queue,ai,legacyNotifications);
        } finally { jdbc.execute("DROP TRIGGER "+trigger+" ON notifications"); jdbc.execute("DROP FUNCTION "+trigger+"()"); }
        assertEquals(200,submitHttp(leader,0)); assertSupervisorNotices();
        verify(queue,times(1)).enqueue(any(),eq(0),eq(now));
    }

    @Test void acceptanceWaitingForProjectLockRejectsNewlyExpiredDeadlineWithoutEnrollment() throws Exception {
        attachSupervisors(); var executor=Executors.newSingleThreadExecutor();
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT id FROM projects WHERE id=? FOR UPDATE")) { lock.setObject(1,project); lock.executeQuery().close(); }
            var waiting=executor.submit(()->submitHttp(leader,0));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1); int blocked=0;
            while(blocked==0 && System.nanoTime()<deadline) blocked=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename=current_user AND wait_event_type='Lock' AND query LIKE '%SELECT id FROM projects WHERE id=%FOR UPDATE%'",Integer.class);
            assertTrue(blocked>0,"Acceptance must wait specifically on the held project row lock");
            try(var change=connection.prepareStatement("UPDATE projects SET deadline=? WHERE id=?")) { change.setObject(1,LocalDateTime.ofInstant(now,ZoneOffset.UTC)); change.setObject(2,project); change.executeUpdate(); }
            connection.commit();
            assertEquals(400,waiting.get(5,TimeUnit.SECONDS)); assertAcceptanceUnchanged();
            assertEquals(LocalDateTime.ofInstant(now,ZoneOffset.UTC),jdbc.queryForObject("SELECT deadline FROM projects WHERE id=?",LocalDateTime.class,project));
            verify(github,times(1)).resolveRepository(anyString(),any(),any()); verifyNoInteractions(queue,ai,legacyNotifications);
        } finally { executor.shutdownNow(); }
    }

    void attachSupervisors() {
        universitySupervisor=UUID.randomUUID(); projectSupervisor=UUID.randomUUID();
        for(UUID id:List.of(universitySupervisor,projectSupervisor)) jdbc.update("INSERT INTO users(id,email,password,first_name,last_name,role,status) VALUES (?,?,?,'Synthetic','Supervisor',?,'APPROVED')",
                id,id+"@example.test","synthetic",id.equals(universitySupervisor)?"PARTY_SUPERVISOR":"PROJECT_SUPERVISOR");
        jdbc.update("UPDATE parties SET supervisor_id=? WHERE id=?",universitySupervisor,party);
        jdbc.update("UPDATE projects SET project_supervisor_id=? WHERE id=?",projectSupervisor,project);
    }

    int submitHttp(UUID caller,long version) throws Exception {
        var security=org.springframework.security.core.context.SecurityContextHolder.getContext();
        security.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(caller,null,List.of()));
        try {
            return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new com.talentbridge.controller.SubmissionController(drafts,finals,worker))
                    .setControllerAdvice(new com.talentbridge.exception.GlobalExceptionHandler())
                    .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver()).build()
                    .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/submissions/"+party+"/submit")
                            .contentType("application/json").content("{\"expectedVersion\":"+version+"}"))
                    .andReturn().getResponse().getStatus();
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    }

    void assertSupervisorNotices() {
        for(UUID id:List.of(universitySupervisor,projectSupervisor)) {
            var notices=jdbc.queryForList("SELECT type,title,message,reference_id,reference_type,read FROM notifications WHERE recipient_id=?",id);
            assertEquals(1,notices.size()); var notice=notices.get(0);
            assertEquals("SUBMISSION_RECEIVED",notice.get("type")); assertEquals("Party Submitted",notice.get("title"));
            assertEquals("Synthetic team has submitted their final project.",notice.get("message"));
            assertEquals(party.toString(),notice.get("reference_id")); assertEquals("PARTY",notice.get("reference_type")); assertEquals(false,notice.get("read"));
        }
        assertEquals(0,notificationCount());
    }

    void assertAcceptanceUnchanged() {
        assertEquals("DRAFT",jdbc.queryForObject("SELECT status FROM submissions WHERE id=?",String.class,submission));
        assertEquals(0,jdbc.queryForObject("SELECT version FROM submissions WHERE id=?",Long.class,submission));
        assertEquals(1,jdbc.queryForObject("SELECT assessment_version FROM submissions WHERE id=?",Integer.class,submission));
        assertEquals("ASSIGNED",jdbc.queryForObject("SELECT status FROM parties WHERE id=?",String.class,party));
        assertTrue(snapshots.findBySubmissionIdAndAssessmentVersion(submission,2).isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id IN (?,?,?,?)",Integer.class,leader,member,universitySupervisor,projectSupervisor));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM questionnaire_jobs j JOIN questionnaire_snapshots s ON s.id=j.snapshot_id WHERE s.submission_id=?",Integer.class,submission));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM questionnaire_assignments a JOIN questionnaire_snapshots s ON s.id=a.snapshot_id WHERE s.submission_id=?",Integer.class,submission));
    }

    @Test void finalRequiresSavedVersionAndFlushReturnsThePostUpdateVersion() {
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> finals.finalSubmit(party,leader,null)).getStatusCode().value());
        var saved=drafts.saveDraft(party,leader,null,"release",null,"Saved note");
        assertEquals(1,saved.getVersion());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> finals.finalSubmit(party,leader,0L)).getStatusCode().value());
        verifyNoInteractions(github);
    }

    @ParameterizedTest @ValueSource(strings={"present","null","missing"})
    void evidenceHttpClaimAvoidsProfileHydrationAndPreservesCredentialSemantics(String credential) throws Exception {
        String token=credential.equals("present")?"synthetic-evidence-credential":null;
        jdbc.update("UPDATE users SET github_access_token=? WHERE id=?",token,leader);
        finals.finalSubmit(party,leader,0L); UUID job=activeJob();
        if(credential.equals("missing")) jdbc.update("UPDATE questionnaire_snapshots SET frozen_leader_id=? WHERE id=?",UUID.randomUUID(),snapshot().getId());
        var frozen=snapshot().getFrozenResolution().deepCopy();
        var assignmentIds=assignments.findAllBySnapshotId(snapshot().getId()).stream().map(QuestionnaireAssignment::getId).sorted().toList();
        var quota=jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1");
        var captured=new GitHubEvidence(resolution,now,List.of(),List.of(),new GitHubEvidence.ContributorSnapshot(now,false,false,List.of()),List.of(),
                new GitHubEvidence.Bounds(16,32000,0,4000,8,1000,128,8192,65536,262144,2097152,100));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(github.capture(eq(resolution),anyString(),any())).thenAnswer(call->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(Objects.equals(token,call.getArgument(2)),"Credential read changed null/present semantics");
            calls.incrementAndGet(); return captured;
        });
        int status;
        profileFetchProbe.start();
        try {
            status=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    new com.talentbridge.controller.AssessmentJobController(worker,evaluation))
                    .setControllerAdvice(new com.talentbridge.exception.GlobalExceptionHandler()).build()
                    .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/assessment-jobs/{id}",job)
                            .contentType("application/json").content("{\"jobId\":\""+job+"\"}"))
                    .andReturn().getResponse().getStatus();
        } finally { profileFetchProbe.active=false; }
        var persisted=jobs.findById(job).orElseThrow();
        System.out.println("EVIDENCE_CLAIM_REPRO credentialCase="+credential+" status="+status+" profileTables="+profileFetchProbe.tables
                +" delayed="+profileFetchProbe.delayed.get()+" jobState="+persisted.getStatus()+" attempts="+persisted.getAttempts());
        if(status==500) {
            assertEquals(QuestionnaireJobStatus.DISPATCHED,persisted.getStatus());
            assertNull(persisted.getLeaseToken()); assertNull(persisted.getLeaseUntil()); assertNull(persisted.getLastErrorCode());
            assertEquals(0,calls.get());
        }
        assertEquals(0,persisted.getAttempts());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM questionnaire_job_attempts WHERE job_id=?",Integer.class,job));
        assertTrue(quota.equals(jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1")),"Evidence delivery changed quota");
        assertTrue(frozen.equals(snapshot().getFrozenResolution()),"Evidence delivery changed the frozen resolution");
        assertEquals(assignmentIds,assignments.findAllBySnapshotId(snapshot().getId()).stream().map(QuestionnaireAssignment::getId).sorted().toList());
        verifyNoInteractions(ai);
        assertEquals(204,status);
        assertTrue(profileFetchProbe.tables.isEmpty(),"A scalar credential read must not hydrate profile associations");
        assertEquals(1,calls.get()); assertEquals(QuestionnaireJobStatus.SUCCEEDED,persisted.getStatus());
        assertNull(persisted.getLeaseToken()); assertNull(persisted.getLeaseUntil());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM questionnaire_evidence WHERE snapshot_id=?",Integer.class,snapshot().getId()));
    }

    @ParameterizedTest @ValueSource(strings={"EVIDENCE","GENERATE_MEMBER"})
    void slowFrozenPreparationCannotExpireClaimCommitThroughRealHttp(String kind) throws Exception {
        if(kind.equals("EVIDENCE")) {
            finals.finalSubmit(party,leader,0L);
            when(github.capture(eq(resolution),anyString(),any())).thenReturn(new GitHubEvidence(resolution,now,List.of(),List.of(),
                    new GitHubEvidence.ContributorSnapshot(now,false,false,List.of()),List.of(),
                    new GitHubEvidence.Bounds(16,32000,0,4000,8,1000,128,8192,65536,262144,2097152,100)));
        } else { evidenceReady(); successfulGeneration(); }
        UUID target=activeJob();
        var frozen=snapshot().getFrozenResolution().deepCopy();
        var roster=assignments.findAllBySnapshotId(snapshot().getId()).stream().map(QuestionnaireAssignment::getId).sorted().toList();
        var quota=jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1");
        clearInvocations(github,ai);
        inputMapper.type=kind.equals("EVIDENCE")?GitHubEvidence.Resolution.class:GitHubEvidence.class;
        inputMapper.afterRead=()->{try {Thread.sleep(2100);} catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Synthetic preparation interrupted");}};
        try(var http=new WorkerHttp(worker,evaluation)) {
            int status=http.post(target);
            if(status==500) {
                http.assertCommitTimeout();
                var rolled=jobs.findById(target).orElseThrow();
                assertEquals(QuestionnaireJobStatus.DISPATCHED,rolled.getStatus()); assertEquals(0,rolled.getAttempts());
                assertNull(rolled.getLeaseToken()); assertNull(rolled.getLeaseUntil()); assertNull(rolled.getLastErrorCode());
                verifyNoInteractions(github,ai);
            }
            assertEquals(frozen,snapshot().getFrozenResolution());
            assertEquals(roster,assignments.findAllBySnapshotId(snapshot().getId()).stream().map(QuestionnaireAssignment::getId).sorted().toList());
            assertEquals(quota,jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1"));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM questionnaire_job_attempts WHERE job_id=? AND outcome<>'SUCCEEDED'",Integer.class,target));
            System.out.println("SECOND_CLAIM_REPRO kind="+kind+" http="+status+" preparationInTransaction="+inputMapper.inTransaction+" injectedMs=2100");
            assertEquals(204,status); assertFalse(inputMapper.inTransaction);
            assertEquals(QuestionnaireJobStatus.SUCCEEDED,jobs.findById(target).orElseThrow().getStatus());
        }
    }

    @ParameterizedTest @ValueSource(strings={"EVIDENCE","GENERATE_MEMBER"})
    void frozenPreparationFailureIsDurableAndRecoverableWithoutProviderOrQuota(String kind) throws Exception {
        if(kind.equals("EVIDENCE")) finals.finalSubmit(party,leader,0L);
        else {evidenceReady();successfulGeneration();}
        UUID job=activeJob();var quota=jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1");
        clearInvocations(github,ai,queue);
        inputMapper.type=kind.equals("EVIDENCE")?GitHubEvidence.Resolution.class:GitHubEvidence.class;
        inputMapper.afterRead=()->{throw new IllegalStateException("Synthetic frozen input failure");};
        try(var http=new WorkerHttp(worker,evaluation)) {
            assertEquals(204,http.post(job));
            var failed=jobs.findById(job).orElseThrow();
            assertEquals(QuestionnaireJobStatus.FAILED,failed.getStatus());assertEquals("FROZEN_INPUT_UNAVAILABLE",failed.getLastErrorCode());
            assertEquals(0,failed.getAttempts());assertNull(failed.getLeaseToken());assertNull(failed.getLeaseUntil());
            assertEquals(QuestionnaireState.FAILED,snapshot().getState());assertNull(snapshot().getOpensAt());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM questionnaire_job_attempts WHERE job_id=?",Integer.class,job));
            assertEquals(quota,jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1"));
            verifyNoInteractions(github,ai,queue);
            inputMapper.resetProbe();worker.retry(submission);UUID replacement=activeJob();
            assertEquals(job,jobs.findById(replacement).orElseThrow().getRetryOf());
            assertEquals(failed.getQuestionIds(),jobs.findById(replacement).orElseThrow().getQuestionIds());
            if(kind.equals("EVIDENCE")) when(github.capture(eq(resolution),anyString(),any())).thenReturn(new GitHubEvidence(resolution,now,List.of(),List.of(),
                    new GitHubEvidence.ContributorSnapshot(now,false,false,List.of()),List.of(),
                    new GitHubEvidence.Bounds(16,32000,0,4000,8,1000,128,8192,65536,262144,2097152,100)));
            assertEquals(204,http.post(replacement));assertEquals(QuestionnaireJobStatus.SUCCEEDED,jobs.findById(replacement).orElseThrow().getStatus());
        }
    }

    @ParameterizedTest @ValueSource(strings={"EVIDENCE","GENERATE_MEMBER"})
    void expiredOrReplacedLeaseDuringFrozenPreparationCannotInvokeProviderOrSave(String kind) throws Exception {
        if(kind.equals("EVIDENCE")) finals.finalSubmit(party,leader,0L);else evidenceReady();
        UUID job=activeJob();var quota=jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1");
        clearInvocations(github,ai,queue);
        try(var http=new WorkerHttp(worker,evaluation)) {
            for(boolean replace:List.of(false,true)) {
                clock.set(now);UUID replacement=UUID.randomUUID();
                inputMapper.type=kind.equals("EVIDENCE")?GitHubEvidence.Resolution.class:GitHubEvidence.class;
                inputMapper.afterRead=()->{
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    try {assertEquals(503,http.post(job));} catch(Exception e){throw new IllegalStateException(e);}
                    if(replace) jdbc.update("UPDATE questionnaire_jobs SET lease_token=? WHERE id=?",replacement,job);
                    else clock.set(now.plusSeconds(110));
                };
                assertEquals(503,http.post(job));
                var pending=jobs.findById(job).orElseThrow();assertEquals(QuestionnaireJobStatus.RUNNING,pending.getStatus());
                if(replace) assertEquals(replacement,pending.getLeaseToken());
                assertEquals(0,pending.getAttempts());assertNull(pending.getLastErrorCode());
                assertNull(snapshot().getOpensAt());assertNull(snapshot().getClosesAt());
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id=?",Integer.class,snapshot().getId()));
                assertEquals(quota,jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1"));
                verify(github,never()).capture(any(),anyString(),any());verify(ai,never()).generate(any(),any(Runnable.class));
                verifyNoInteractions(queue);
                jdbc.update("UPDATE questionnaire_jobs SET lease_until=?::timestamptz WHERE id=?",now.minusSeconds(1).toString(),job);
            }
        }
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

    @ParameterizedTest @ValueSource(booleans={false,true})
    void retryHttpRecoversUnderJdbcLatencyWithoutChargingOrDispatchingWhileLeased(boolean expiredAttempt) throws Exception {
        if(expiredAttempt) evidenceReady(); else finals.finalSubmit(party,leader,0L);
        UUID job=activeJob();
        UUID lease=UUID.randomUUID();
        jdbc.update("UPDATE questionnaire_jobs SET dispatch_generation=1 WHERE id=?",job);
        if(expiredAttempt) {
            jdbc.update("UPDATE questionnaire_jobs SET status='RUNNING',attempts=1,lease_token=?,lease_until=?::timestamptz WHERE id=?",
                    lease,now.toString(),job);
            jdbc.update("INSERT INTO questionnaire_job_attempts(job_id,attempt,started_at,outcome) VALUES (?,1,?::timestamptz,'STARTED')",
                    job,now.minusSeconds(110).toString());
        }
        clearInvocations(github,ai,queue);
        var dispatchBoundary=new java.util.concurrent.atomic.AtomicInteger(-1);
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            dispatchBoundary.set(profileFetchProbe.retryStatements.size());
            return null;
        }).when(queue).enqueue(eq(job),eq(2),eq(now));
        try(var http=new WorkerHttp(worker,evaluation,drafts,finals)) {
            profileFetchProbe.retryStatements.clear();
            profileFetchProbe.retryLatency=true;
            java.net.http.HttpResponse<String> response;
            long start=System.nanoTime();
            try { response=http.retry(submission); }
            finally { profileFetchProbe.retryLatency=false; }
            var statements=List.copyOf(profileFetchProbe.retryStatements);
            System.out.println("RETRY_HTTP expiredAttempt="+expiredAttempt+" status="+response.statusCode()+
                    " elapsedMs="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)+
                    " enqueueAfterStatement="+dispatchBoundary.get()+" SQL="+statements.stream()
                    .map(s->s.phase()+":"+s.timeoutSeconds()+":"+s.sql().split("\\s+",2)[0]).toList()+
                    " failure="+http.logs.list.stream().map(e->e.getThrowableProxy()).filter(Objects::nonNull)
                    .map(e->e.getClassName()+": "+e.getMessage()+" / "+(e.getCause()==null?"":e.getCause().getMessage())+
                            " mutationAtCommit="+(e.getCause()!=null && Arrays.stream(e.getCause().getStackTraceElementProxyArray())
                            .anyMatch(frame->frame.toString().contains("MutationStatementPreparer")))).toList());
            assertEquals(200,response.statusCode());
            assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
            var review=inputMapper.readValue(response.body(),QuestionnaireGenerationService.Review.class);
            assertEquals(submission,review.submissionId());
            assertEquals(QuestionnaireState.GENERATING,review.state());
            assertNull(review.opensAt()); assertNull(review.closesAt());
            var recovered=review.jobs().stream().filter(j->j.id().equals(job)).findFirst().orElseThrow();
            assertEquals(expiredAttempt?QuestionnaireJobKind.GENERATE_MEMBER:QuestionnaireJobKind.EVIDENCE,recovered.kind());
            assertEquals(QuestionnaireJobStatus.DISPATCHED,recovered.status());
            assertEquals(2,recovered.dispatchGeneration()); assertEquals(expiredAttempt?1:0,recovered.attempts());
            assertNull(recovered.leaseUntil()); assertNull(recovered.retryOf());
            assertEquals(expiredAttempt?List.of("UNKNOWN"):List.of(),jdbc.queryForList(
                    "SELECT outcome FROM questionnaire_job_attempts WHERE job_id=?",String.class,job));
            assertNull(jdbc.queryForObject("SELECT lease_token FROM questionnaire_jobs WHERE id=?",UUID.class,job));
            int boundary=dispatchBoundary.get();
            assertTrue(boundary>0 && boundary<statements.size());
            assertTrue(statements.subList(0,boundary).stream().anyMatch(s->s.phase().equals("dispatch")));
            assertTrue(statements.subList(boundary,statements.size()).stream()
                    .anyMatch(s->s.phase().equals("dispatch") && s.sql().startsWith("update questionnaire_jobs")));
            assertTrue(statements.stream().allMatch(s->s.inTransaction() && s.timeoutSeconds()>0 && s.timeoutSeconds()<=5));
            assertEquals(1,statements.stream().filter(s->s.phase().equals("review") && s.timeoutSeconds()<=2).count());
            assertEquals(expiredAttempt?1:0,statements.stream().filter(s->s.sql().contains("UPDATE questionnaire_job_attempts")).count());

            jdbc.update("UPDATE questionnaire_jobs SET status='RUNNING',lease_token=?,lease_until=?::timestamptz WHERE id=?",
                    lease,now.plusSeconds(110).toString(),job);
            assertEquals(409,http.retry(submission).statusCode());
            var running=jobs.findById(job).orElseThrow();
            assertEquals(QuestionnaireJobStatus.RUNNING,running.getStatus());
            assertEquals(lease,running.getLeaseToken()); assertEquals(now.plusSeconds(110),running.getLeaseUntil());
            assertEquals(2,running.getDispatchGeneration()); assertEquals(expiredAttempt?1:0,running.getAttempts());
            if(!expiredAttempt) {
                jdbc.update("UPDATE questionnaire_snapshots SET assessment_version=1 WHERE id=?",snapshot().getId());
                assertEquals(404,http.retry(submission).statusCode());
            }
            verify(queue).enqueue(job,2,now);
            verifyNoMoreInteractions(queue);
            verifyNoInteractions(github,ai);
        }
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

    @Test void evidenceWorkerCommitsCapturedEvidenceAndDispatchesFirstMemberOverRemoteDatabase() throws Exception {
        finals.finalSubmit(party,leader,0L);
        UUID job=activeJob();
        when(github.capture(eq(resolution),anyString(),isNull())).thenAnswer(call->{
            profileFetchProbe.delayNextResultWrite.set(true);
            return capturedEvidence();
        });
        try(var http=new WorkerHttp(worker,evaluation)) {
            assertEquals(204,http.post(job));
            var review=worker.review(submission);
            assertNull(review.opensAt());
            assertEquals(QuestionnaireJobStatus.SUCCEEDED,review.jobs().get(0).status(),
                    ()->"EVIDENCE worker result: "+review.jobs().get(0).errorCode()
                            +"; delayed database operations: "+profileFetchProbe.resultWriteStatements);
            assertEquals(2,review.jobs().size());
            assertEquals(QuestionnaireJobKind.GENERATE_MEMBER,review.jobs().get(1).kind());
            assertEquals(QuestionnaireJobStatus.DISPATCHED,review.jobs().get(1).status());
            assertEquals(0,review.jobs().get(1).attempts());
            assertEquals(resolution.commitSha(),jdbc.queryForObject(
                    "SELECT commit_sha FROM questionnaire_evidence WHERE snapshot_id=?",String.class,snapshot().getId()));
            assertFalse(profileFetchProbe.resultWriteStatements.isEmpty());
        }
        verifyNoInteractions(ai);
    }

    @Test void memberWorkersPublishBothSetsAfterPersistingResultsOverRemoteDatabase() throws Exception {
        evidenceReady();
        doAnswer(call->{
            var result=successfulGenerationResult(call);
            profileFetchProbe.delayNextResultWrite.set(true);
            return result;
        }).when(ai).generate(any(),any(Runnable.class));
        try(var http=new WorkerHttp(worker,evaluation)) {
            UUID first=activeJob();
            assertEquals(204,http.post(first));
            var halfway=worker.review(submission);
            assertNull(halfway.opensAt());
            assertEquals(QuestionnaireJobStatus.SUCCEEDED,
                    halfway.jobs().stream().filter(job->job.id().equals(first)).findFirst().orElseThrow().status());
            profileFetchProbe.resultWriteStatements.clear();
            assertEquals(204,http.post(activeJob()));
            var open=worker.review(submission);
            assertEquals(QuestionnaireState.OPEN,open.state(),
                    ()->"Member job results: "+open.jobs().stream().map(QuestionnaireGenerationService.Job::errorCode).toList()
                            +"; delayed database operations: "+profileFetchProbe.resultWriteStatements);
            assertEquals(now,open.opensAt());
            assertEquals(now.plus(Duration.ofHours(48)),open.closesAt());
            assertEquals(List.of(5,5),open.members().stream().map(QuestionnaireGenerationService.Member::questionCount).toList());
            assertEquals(1,profileFetchProbe.resultWriteStatements.stream()
                    .filter(sql->sql.startsWith("INSERT INTO questionnaire_questions")).count());
            assertEquals(1,profileFetchProbe.resultWriteStatements.stream()
                    .filter(sql->sql.startsWith("INSERT INTO notifications")).count());
            assertEquals(2,notificationCount());
            assertEquals(2,open.jobs().stream().filter(job->job.kind()==QuestionnaireJobKind.GENERATE_MEMBER
                    && job.status()==QuestionnaireJobStatus.SUCCEEDED && job.attempts()==1).count());
        }
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

    @ParameterizedTest @ValueSource(strings={"prepare","beforeCallback"})
    void generationPreparationFailureAfterEarlierActualAttemptTerminatesThroughInternalHttpAndRetainsPriorMember(String stage) throws Exception {
        evidenceReady();successfulGeneration();httpDeliver(activeJob());UUID target=activeJob();
        var saved=jdbc.queryForList("SELECT q.id,q.prompt,q.hidden_rubric,q.assignment_id FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id=? ORDER BY q.id",snapshot().getId());
        LocalDate stored=jdbc.queryForObject("SELECT quota_day FROM assessment_ai_quota WHERE id=1",java.sql.Date.class).toLocalDate();
        clock.set(clock.instant().isAfter(stored.plusDays(1).atStartOfDay(ZoneId.of("Asia/Karachi")).toInstant())?clock.instant():stored.plusDays(1).atStartOfDay(ZoneId.of("Asia/Karachi")).toInstant());
        var quota=new AssessmentQuotaService(jdbc,transactions,clock,50);
        doAnswer(c->{quota.reserve(c.getArgument(1));throw new AssessmentAIService.AssessmentException("RELAY_FAILURE");}).when(ai).generate(any(),any(Runnable.class));
        httpDeliver(target);var ids=List.copyOf(jobs.findById(target).orElseThrow().getQuestionIds());assertEquals(5,ids.size());
        var charged=jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1");
        var history=jdbc.queryForList("SELECT attempt,outcome,started_at,finished_at FROM questionnaire_job_attempts WHERE job_id=? ORDER BY attempt",target);
        int generation=jobs.findById(target).orElseThrow().getDispatchGeneration();clearInvocations(ai,queue);
        if(stage.equals("prepare")) doThrow(new AssessmentAIService.AssessmentException("INVALID_RELAY_CONFIGURATION")).when(ai).prepareGeneration(anyString(),any(),any(),anyList(),anyList());
        else doThrow(new AssessmentAIService.AssessmentException("QUOTA_UNAVAILABLE")).when(ai).generate(any(),any(Runnable.class));
        clock.set(clock.instant().plusSeconds(30));httpDeliver(target);clock.set(clock.instant().plusSeconds(30));httpDeliver(target);
        var stopped=jobs.findById(target).orElseThrow();assertEquals(QuestionnaireJobStatus.FAILED,stopped.getStatus());assertEquals(QuestionnaireState.FAILED,snapshot().getState());
        assertEquals(1,stopped.getAttempts());assertEquals(generation,stopped.getDispatchGeneration());assertEquals(ids,stopped.getQuestionIds());
        assertNull(snapshot().getOpensAt());assertNull(snapshot().getClosesAt());verifyNoInteractions(queue);verify(ai,times(stage.equals("prepare")?0:1)).generate(any(),any(Runnable.class));
        verify(ai,times(1)).prepareGeneration(anyString(),any(),any(),anyList(),anyList());
        assertEquals(charged,jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1"));
        assertEquals(history,jdbc.queryForList("SELECT attempt,outcome,started_at,finished_at FROM questionnaire_job_attempts WHERE job_id=? ORDER BY attempt",target));
        assertEquals(saved,jdbc.queryForList("SELECT q.id,q.prompt,q.hidden_rubric,q.assignment_id FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id=? ORDER BY q.id",snapshot().getId()));
        doAnswer(c->{generatedMember=c.getArgument(2);generatedIds=c.getArgument(3);capturedPrior=c.getArgument(4);return null;})
                .when(ai).prepareGeneration(anyString(),any(),any(),anyList(),anyList());
        successfulGeneration();worker.retry(submission);UUID replacement=activeJob();assertNotEquals(target,replacement);
        assertEquals(target,jobs.findById(replacement).orElseThrow().getRetryOf());
        assertEquals(ids,jobs.findById(replacement).orElseThrow().getQuestionIds());httpDeliver(replacement);
        assertNotNull(snapshot().getOpensAt());assertEquals(history,jdbc.queryForList("SELECT attempt,outcome,started_at,finished_at FROM questionnaire_job_attempts WHERE job_id=? ORDER BY attempt",target));
        assertTrue(jdbc.queryForList("SELECT q.id FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id=?",UUID.class,snapshot().getId()).containsAll(saved.stream().map(row->(UUID)row.get("id")).toList()));
    }

    @ParameterizedTest @ValueSource(strings={"expired","callbackFailure","commitFailure","denied","success","providerFailure"})
    void atomicQuotaAndGenerationAttemptThroughRealHttp(String mode) throws Exception {
        evidenceReady(); UUID target=activeJob();
        try(var probe=new AtomicQuotaProbe(source,transactions,clock,clock::set,ai,mode);
            var http=new WorkerHttp(worker,evaluation)) {
            int status=http.post(target);
            System.out.println("ATOMIC_QUOTA generation mode="+mode+" http="+status+" used="+probe.used()+" attempts="+jobs.findById(target).orElseThrow().getAttempts()+" provider="+probe.calls.get());
            assertEquals(mode.equals("expired")?503:204,status);
            probe.assertAccounting(target);
            if(mode.equals("success")) {
                assertEquals(QuestionnaireJobStatus.SUCCEEDED,jobs.findById(target).orElseThrow().getStatus());
                assertEquals(5,jdbc.queryForObject("""
                        SELECT count(*) FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id
                        WHERE a.snapshot_id=? AND q.source_references='[{"path":"src/Engine.java","startLine":1,"endLine":2}]'::jsonb
                        """,Integer.class,snapshot().getId()));
            }
            assertNull(snapshot().getOpensAt()); assertNull(snapshot().getClosesAt());
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "providerFailure,MODEL,RELAY_HTTP_FAILURE,NONE",
            "invalidRelayEnvelope,MODEL,INVALID_RESPONSE,RELAY_ENVELOPE",
            "invalidModelJson,MODEL,INVALID_RESPONSE,MODEL_JSON",
            "invalidModelReply,MODEL,INVALID_RESPONSE,GENERATION_SHAPE",
            "invalidIdentity,MODEL,INVALID_RESPONSE,GENERATION_IDENTITY",
            "invalidReference,MODEL,INVALID_RESPONSE,GENERATION_REFERENCES",
            "resultWriteFailure,RESULT_WRITE,UNCLASSIFIED,NONE"
    })
    void generationFailuresIdentifySafePhaseWithoutLoggingPayload(String mode,String phase,String category,String validation) throws Exception {
        evidenceReady(); UUID target=activeJob();
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(QuestionnaireGenerationService.class);
        var captured=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start(); logger.addAppender(captured);
        try(var probe=new AtomicQuotaProbe(source,transactions,clock,clock::set,ai,mode);
            var http=new WorkerHttp(worker,evaluation)) {
            assertEquals(204,http.post(target)); probe.assertAccounting(target);
            assertEquals("GENERATION_UNAVAILABLE",jobs.findById(target).orElseThrow().getLastErrorCode());
            assertNull(snapshot().getOpensAt());
            assertEquals(List.of("Questionnaire generation failed phase="+phase+" category="+category+" validation="+validation),
                    captured.list.stream().map(e->e.getFormattedMessage()).toList());
            assertTrue(captured.list.stream().allMatch(e->e.getThrowableProxy()==null));
        } finally {logger.detachAppender(captured);captured.stop();}
    }

    void httpDeliver(UUID id) throws Exception {
        var controller=new com.talentbridge.controller.AssessmentJobController(worker,evaluation);
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new com.talentbridge.exception.GlobalExceptionHandler()).build()
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/assessment-jobs/{id}",id)
                        .contentType("application/json").content("{\"jobId\":\""+id+"\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
    }

    @Test void generationQuotaAfterEarlierActualFailureStillDefersWithoutAnotherAttempt() throws Exception {
        evidenceReady();UUID target=activeJob();
        doAnswer(c->{((Runnable)c.getArgument(1)).run();throw new AssessmentAIService.AssessmentException("RELAY_FAILURE");}).when(ai).generate(any(),any(Runnable.class));
        httpDeliver(target);var history=jdbc.queryForList("SELECT attempt,outcome,started_at,finished_at FROM questionnaire_job_attempts WHERE job_id=?",target);
        var quota=jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1");clearInvocations(ai);
        Instant reset=clock.instant().atZone(ZoneId.of("Asia/Karachi")).toLocalDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Karachi")).toInstant();
        doThrow(new AssessmentQuotaService.QuotaExceeded(reset)).when(ai).generate(any(),any(Runnable.class));
        clock.set(clock.instant().plusSeconds(30));httpDeliver(target);var deferred=jobs.findById(target).orElseThrow();
        assertEquals(QuestionnaireJobStatus.DEFERRED,deferred.getStatus());assertEquals(reset,deferred.getNextAttemptAt());assertEquals(1,deferred.getAttempts());
        httpDeliver(target);assertEquals(deferred.getDispatchGeneration()+1,jobs.findById(target).orElseThrow().getDispatchGeneration());
        assertEquals(history,jdbc.queryForList("SELECT attempt,outcome,started_at,finished_at FROM questionnaire_job_attempts WHERE job_id=?",target));
        assertEquals(quota,jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1"));
        assertNull(snapshot().getOpensAt());verify(ai,times(1)).generate(any(),any(Runnable.class));
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
    GitHubEvidence capturedEvidence() {
        return new GitHubEvidence(resolution,now,List.of(),List.of(new GitHubEvidence.FileEvidence(GitHubEvidence.Kind.SOURCE,"src/Engine.java","c".repeat(40),"d".repeat(64),22,2,1,2,"class Engine {\n}\n",false)),
                new GitHubEvidence.ContributorSnapshot(now,false,false,List.of(new GitHubEvidence.Contributor("frozen-leader",4))),List.of(),
                new GitHubEvidence.Bounds(16,32000,17,4000,8,1000,128,8192,65536,262144,2097152,100));
    }
    void evidenceReady() {
        finals.finalSubmit(party,leader,0L);
        var captured=capturedEvidence();
        when(github.capture(eq(resolution),anyString(),any())).thenAnswer(call -> { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); return captured; });
        worker.handle(activeJob());
        when(ai.prepareGeneration(anyString(),any(),any(),anyList(),anyList())).thenAnswer(call -> {
            generatedMember=call.getArgument(2); generatedIds=call.getArgument(3); capturedPrior=call.getArgument(4); return null;
        });
    }
    UUID generatedMember; List<UUID> generatedIds; List<String> capturedPrior;
    void successfulGeneration() {
        doAnswer(this::successfulGenerationResult).when(ai).generate(any(),any(Runnable.class));
    }
    AssessmentAIService.GeneratedQuestions successfulGenerationResult(org.mockito.invocation.InvocationOnMock call) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); ((Runnable)call.getArgument(1)).run();
        var result=new ArrayList<AssessmentAIService.Question>();
        for (int i=0;i<5;i++) result.add(new AssessmentAIService.Question(generatedIds.get(i),"Explain synthetic implementation boundary "+generatedMember+" question "+i,"Private rubric",List.of(new GitHubEvidence.SourceReference("src/Engine.java",1,2))));
        return new AssessmentAIService.GeneratedQuestions(generatedMember,result);
    }

    QuestionnaireSnapshot snapshot() { return snapshots.findBySubmissionIdAndAssessmentVersion(submission,2).orElseThrow(); }

    static class MutableClock extends Clock {
        final AtomicReference<Instant> value=new AtomicReference<>(); void set(Instant time){value.set(time);}
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return value.get();}
    }
    static class ProfileFetchProbe implements org.springframework.beans.factory.config.BeanPostProcessor,org.hibernate.resource.jdbc.spi.StatementInspector {
        volatile boolean active;
        volatile boolean reviewLatency;
        volatile boolean retryLatency;
        final Queue<ReviewRead> reviewReads=new ConcurrentLinkedQueue<>();
        final Queue<RetryStatement> retryStatements=new ConcurrentLinkedQueue<>();
        final Queue<String> resultWriteStatements=new ConcurrentLinkedQueue<>();
        final java.util.concurrent.atomic.AtomicBoolean delayNextResultWrite=new java.util.concurrent.atomic.AtomicBoolean();
        final Set<String> tables=ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicBoolean delayed=new java.util.concurrent.atomic.AtomicBoolean();
        void start(){tables.clear();delayed.set(false);active=true;}
        @Override public Object postProcessBeforeInitialization(Object bean,String name) {
            if(bean instanceof org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean factory)
                factory.getJpaPropertyMap().put("hibernate.session_factory.statement_inspector",this);
            if(bean instanceof DataSource dataSource) return new DelegatingDataSource(dataSource) {
                @Override public Connection getConnection() throws SQLException {
                    var connection=super.getConnection();
                    boolean resultWrite=delayNextResultWrite.compareAndSet(true,false);
                    return reviewLatency || retryLatency || resultWrite?delayedConnection(connection,retryLatency,resultWrite):connection;
                }
            };
            return bean;
        }
        private Connection delayedConnection(Connection connection,boolean retry,boolean resultWrite) {
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},
                    (proxy,method,args)->{
                        Object result=invoke(connection,method,args);
                        if(method.getName().equals("prepareStatement") && result instanceof PreparedStatement statement)
                            return delayedStatement(statement,(String)args[0],retry,resultWrite);
                        return result;
                    });
        }
        private PreparedStatement delayedStatement(PreparedStatement statement,String sql,boolean retry,boolean resultWrite) {
            return (PreparedStatement)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{PreparedStatement.class},
                    (proxy,method,args)->{
                        if(!method.getName().startsWith("execute")) return invoke(statement,method,args);
                        if(resultWrite) {
                            resultWriteStatements.add(sql);
                            Thread.sleep(440);
                            return invoke(statement,method,args);
                        }
                        if(retry) {
                            String phase=StackWalker.getInstance().walk(frames->frames
                                    .filter(f->f.getClassName().equals(QuestionnaireGenerationService.class.getName()))
                                    .map(StackWalker.StackFrame::getMethodName)
                                    .filter(name->name.equals("retry") || name.equals("dispatch") || name.equals("review"))
                                    .findFirst().orElseThrow());
                            retryStatements.add(new RetryStatement(phase,sql,
                                    TransactionSynchronizationManager.isActualTransactionActive(),statement.getQueryTimeout()));
                            // Slowly read history without timing out earlier hydration, then exercise the dirty flush.
                            long delay=phase.equals("dispatch")?RETRY_DISPATCH_SQL_DELAY_MS
                                    :phase.equals("retry") && sql.contains("from questionnaire_jobs") && sql.contains("order by")?RETRY_HISTORY_DELAY_MS
                                    :phase.equals("retry") && sql.contains("UPDATE questionnaire_job_attempts")?RETRY_ATTEMPT_DELAY_MS
                                    :RETRY_SQL_DELAY_MS;
                            Thread.sleep(delay);
                            return invoke(statement,method,args);
                        }
                        // Delay actual execution equally for Hibernate and JdbcTemplate, without replacing PostgreSQL.
                        var read=new ReviewRead(sql,TransactionSynchronizationManager.isActualTransactionActive(),statement.getQueryTimeout());
                        Thread.sleep(REVIEW_READ_DELAY_MS);
                        Object result=invoke(statement,method,args);
                        reviewReads.add(read);
                        return result;
                    });
        }
        private static Object invoke(Object target,Method method,Object[] args) throws Throwable {
            try { return method.invoke(target,args); }
            catch(InvocationTargetException failure){throw failure.getCause();}
        }
        record ReviewRead(String sql,boolean inTransaction,int timeoutSeconds) {}
        record RetryStatement(String phase,String sql,boolean inTransaction,int timeoutSeconds) {}
        @Override public String inspect(String sql) {
            if(!active || !sql.contains("_profiles") || !StackWalker.getInstance().walk(frames->frames.anyMatch(frame->
                    frame.getClassName().equals(QuestionnaireGenerationService.class.getName()) && frame.getMethodName().equals("claim")))) return sql;
            for(String table:List.of("company_profiles","student_profiles","supervisor_profiles")) if(sql.contains(table)) tables.add(table);
            // Expire the existing two-second claim budget before Hibernate prepares the unnecessary profile fetch.
            if(delayed.compareAndSet(false,true)) try { Thread.sleep(2100); }
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException("Synthetic profile latency interrupted");}
            return sql;
        }
    }

    static class PreparationMapper extends ObjectMapper {
        volatile Class<?> type;
        volatile Runnable afterRead=()->{};
        volatile boolean inTransaction;
        PreparationMapper(){findAndRegisterModules();}
        void resetProbe(){type=null;afterRead=()->{};inTransaction=false;}
        @Override public <T> T treeToValue(com.fasterxml.jackson.core.TreeNode node,Class<T> target) throws com.fasterxml.jackson.core.JsonProcessingException {
            T result=super.treeToValue(node,target);
            probe(target);return result;
        }
        @Override public <T> T convertValue(Object value,Class<T> target) {
            T result=super.convertValue(value,target);
            probe(target);return result;
        }
        void probe(Class<?> target) {
            if(target==type) {
                inTransaction=TransactionSynchronizationManager.isActualTransactionActive();
                // Defer synthetic latency to commit to isolate the newly observed mutation-preparation failure.
                if(inTransaction) TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void beforeCommit(boolean readOnly){afterRead.run();}
                });
                else afterRead.run();
            }
        }
    }

    static class AtomicQuotaProbe implements AutoCloseable {
        final JdbcTemplate jdbc;
        final Map<String,Object> previous;
        final String mode,trigger="quota_atomic_"+UUID.randomUUID().toString().replace("-","");
        final java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        final AtomicReference<Throwable> relayFailure=new AtomicReference<>();
        final AtomicReference<String> reply=new AtomicReference<>("{}");
        final com.sun.net.httpserver.HttpServer relay;
        AtomicQuotaProbe(DataSource source,PlatformTransactionManager manager,Clock clock,java.util.function.Consumer<Instant> advance,
                         AssessmentAIService ai,String mode) throws Exception {
            var json=new ObjectMapper().findAndRegisterModules();
            this.mode=mode;jdbc=new JdbcTemplate(source);
            previous=jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1");
            relay=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            try {
            jdbc.update("UPDATE assessment_ai_quota SET quota_day=?,attempt_count=? WHERE id=1",clock.instant().atZone(ZoneId.of("Asia/Karachi")).toLocalDate(),mode.equals("denied")?50:0);
            JdbcTemplate quotaJdbc=new JdbcTemplate(source) {
                @Override public <T> List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> mapper) {
                    var result=super.query(sql,mapper);
                    if(mode.equals("expired")) advance.accept(clock.instant().plusSeconds(110));
                    return result;
                }
            };
            if(mode.equals("callbackFailure") || mode.equals("commitFailure")) {
                jdbc.execute("CREATE FUNCTION "+trigger+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'SYNTHETIC_ATTEMPT_FAILURE'; END $$");
                jdbc.execute("CREATE "+(mode.equals("commitFailure")?"CONSTRAINT ":"")+"TRIGGER "+trigger+" AFTER INSERT ON questionnaire_job_attempts "
                        +(mode.equals("commitFailure")?"DEFERRABLE INITIALLY DEFERRED ":"")+"FOR EACH ROW EXECUTE FUNCTION "+trigger+"()");
            }
            if(mode.equals("resultWriteFailure")) {
                jdbc.execute("CREATE FUNCTION "+trigger+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'SYNTHETIC_PRIVATE_RELAY_SENTINEL'; END $$");
                jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON questionnaire_questions FOR EACH ROW EXECUTE FUNCTION "+trigger+"()");
            }
            relay.createContext("/relay",exchange->{
                calls.incrementAndGet();
                try {
                    var request=json.readTree(exchange.getRequestBody());
                    if(request.path("operation").asText().equals("questionnaire_generation")) {
                        var file=json.readTree(request.path("message").textValue()).path("files").get(0);
                        assertEquals("src/Engine.java",file.path("path").textValue());
                        assertEquals(1,file.path("sourceReferenceId").intValue());
                    }
                    assertEquals(1,used());
                    jdbc.queryForObject("SELECT id FROM assessment_ai_quota WHERE id=1 FOR UPDATE NOWAIT",Integer.class);
                    var started=jdbc.queryForList("SELECT j.snapshot_id,j.id FROM questionnaire_jobs j JOIN questionnaire_job_attempts a ON a.job_id=j.id WHERE a.outcome='STARTED'");
                    assertEquals(1,started.size());
                    jdbc.queryForObject("SELECT id FROM questionnaire_snapshots WHERE id=? FOR UPDATE NOWAIT",UUID.class,started.get(0).get("snapshot_id"));
                    assertEquals(1,jdbc.queryForObject("SELECT attempts FROM questionnaire_jobs WHERE id=?",Integer.class,started.get(0).get("id")));
                } catch(Throwable failure) {relayFailure.set(failure);}
                byte[] body=json.writeValueAsBytes(mode.equals("invalidRelayEnvelope")
                        ?Map.of("other","SYNTHETIC_PRIVATE_RELAY_SENTINEL"):Map.of("message",reply.get()));
                exchange.sendResponseHeaders(mode.equals("providerFailure")?502:200,body.length);
                exchange.getResponseBody().write(body);exchange.close();
            });relay.start();
            var transport=new OpenAIService(json);
            org.springframework.test.util.ReflectionTestUtils.setField(transport,"n8nWebhookUrl","http://127.0.0.1:"+relay.getAddress().getPort()+"/relay");
            org.springframework.test.util.ReflectionTestUtils.setField(transport,"n8nWebhookSecret","synthetic-local-only");
            org.springframework.test.util.ReflectionTestUtils.setField(transport,"evalModel","gpt-4o");
            var real=new AssessmentAIService(json,transport,new AssessmentQuotaService(quotaJdbc,manager,clock,50),new GitHubEvidenceService(json));
            doAnswer(c->{
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                UUID member=c.getArgument(2);List<UUID> ids=c.getArgument(3);
                var root=json.createObjectNode();root.put("memberId",member.toString());var questions=root.putArray("questions");
                if(mode.equals("invalidModelReply")) root.put("unexpected","SYNTHETIC_PRIVATE_RELAY_SENTINEL");
                var prompts=List.of("Explain how Engine validates a submitted revision before writing data.",
                        "Why does Engine acquire a row lock before reading the server clock?",
                        "Describe rollback behavior when an insert fails after updating its counter.",
                        "How would simultaneous saves for the same assignment be handled?",
                        "Which boundary values test publication deadlines and why?");
                for(int i=0;i<5;i++) {
                    var q=questions.addObject();q.put("id",ids.get(i).toString());q.put("prompt",prompts.get(i));q.put("rubric","Explain the actual Engine boundary.");
                    q.putArray("sourceReferenceIds").add(1);
                }
                if(mode.equals("invalidIdentity")) root.put("memberId","00000000-0000-4000-8000-000000000000");
                if(mode.equals("invalidReference")) ((com.fasterxml.jackson.databind.node.ObjectNode)questions.get(0))
                        .putArray("sourceReferenceIds").add(5000);
                reply.set(mode.equals("invalidModelJson")?"SYNTHETIC_PRIVATE_RELAY_SENTINEL":root.toString());
                return real.prepareGeneration(c.getArgument(0),c.getArgument(1),member,ids,c.getArgument(4));
            }).when(ai).prepareGeneration(anyString(),any(),any(),anyList(),anyList());
            doAnswer(c->real.generate(c.getArgument(0),c.getArgument(1))).when(ai).generate(any(),any(Runnable.class));
            doAnswer(c->real.prepareRepositoryEvaluation(c.getArgument(0),c.getArgument(1))).when(ai).prepareRepositoryEvaluation(anyString(),any());
            doAnswer(c->real.prepareQuestionnaireBatches(c.getArgument(0),c.getArgument(1),c.getArgument(2))).when(ai).prepareQuestionnaireBatches(anyString(),any(),anyList());
            Map<AssessmentAIService.PreparedQuestionnaireEvaluation,String> responses=new IdentityHashMap<>();
            doAnswer(c->{
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                var prepared=real.prepareQuestionnaireBatch(c.getArgument(0),c.getArgument(1),c.getArgument(2));
                List<AssessmentAIService.MemberAnswers> members=c.getArgument(2);var root=json.createObjectNode();var output=root.putArray("members");
                for(var member:members) {
                    var m=output.addObject();m.put("memberId",member.memberId().toString());var questions=m.putArray("questions");
                    for(var q:member.questions()) questions.addObject().put("id",q.id().toString()).put("mark",14).put("feedback","Explains the frozen implementation.");
                }
                responses.put(prepared,root.toString());return prepared;
            }).when(ai).prepareQuestionnaireBatch(anyString(),any(),anyList());
            doAnswer(c->{
                var root=json.createObjectNode();var repo=root.putObject("repository");
                for(String key:List.of("authenticity","quality","functionality","scope","collaboration")) repo.putObject(key).put("score",84).put("notes","Bounded frozen evidence.");
                reply.set(root.toString());return real.evaluateRepository(c.getArgument(0),c.getArgument(1));
            }).when(ai).evaluateRepository(any(),any(Runnable.class));
            doAnswer(c->{reply.set(responses.get(c.getArgument(0)));return real.evaluateQuestionnaire(c.getArgument(0),c.getArgument(1));})
                    .when(ai).evaluateQuestionnaire(any(),any(Runnable.class));
            } catch(Exception | Error failure) {close();throw failure;}
        }
        int used(){return jdbc.queryForObject("SELECT attempt_count FROM assessment_ai_quota WHERE id=1",Integer.class);}
        void assertAccounting(UUID job) {
            boolean called=Set.of("success","providerFailure","invalidRelayEnvelope","invalidModelJson","invalidModelReply",
                    "invalidIdentity","invalidReference","resultWriteFailure").contains(mode);
            assertEquals(called?1:0,calls.get());assertNull(relayFailure.get(),()->String.valueOf(relayFailure.get()));
            assertEquals(called?1:0,jdbc.queryForObject("SELECT attempts FROM questionnaire_jobs WHERE id=?",Integer.class,job));
            assertEquals(called?1:0,jdbc.queryForObject("SELECT count(*) FROM questionnaire_job_attempts WHERE job_id=?",Integer.class,job));
            assertEquals(mode.equals("denied")?50:called?1:0,used());
            if(called) assertEquals(mode.equals("success")?"SUCCEEDED":"FAILED",jdbc.queryForObject("SELECT outcome FROM questionnaire_job_attempts WHERE job_id=?",String.class,job));
        }
        @Override public void close() {
            relay.stop(0);
            if(mode.equals("callbackFailure") || mode.equals("commitFailure")) {
                jdbc.execute("DROP TRIGGER IF EXISTS "+trigger+" ON questionnaire_job_attempts");jdbc.execute("DROP FUNCTION IF EXISTS "+trigger+"()");
            }
            if(mode.equals("resultWriteFailure")) {
                jdbc.execute("DROP TRIGGER IF EXISTS "+trigger+" ON questionnaire_questions");jdbc.execute("DROP FUNCTION IF EXISTS "+trigger+"()");
            }
            jdbc.update("UPDATE assessment_ai_quota SET quota_day=?,attempt_count=? WHERE id=1",previous.get("quota_day"),previous.get("attempt_count"));
        }
    }

    @Configuration @org.springframework.web.servlet.config.annotation.EnableWebMvc
    @Import({com.talentbridge.controller.AssessmentJobController.class,com.talentbridge.exception.GlobalExceptionHandler.class})
    static class WorkerHttpConfig {}

    static class WorkerHttp implements AutoCloseable {
        final org.springframework.web.context.support.AnnotationConfigWebApplicationContext context=new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();
        final org.springframework.boot.web.server.WebServer server;
        final ch.qos.logback.classic.Logger logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(com.talentbridge.exception.GlobalExceptionHandler.class);
        final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs=new ch.qos.logback.core.read.ListAppender<>();
        final boolean additive=logger.isAdditive();
        WorkerHttp(QuestionnaireGenerationService generation,QuestionnaireEvaluationService evaluation) throws Exception {
            this(generation,evaluation,null,null);
        }
        WorkerHttp(QuestionnaireGenerationService generation,QuestionnaireEvaluationService evaluation,
                   SubmissionService drafts,QuestionnaireFinalSubmissionService finals) throws Exception {
            context.register(WorkerHttpConfig.class);
            if(drafts!=null) context.register(com.talentbridge.controller.SubmissionController.class);
            context.addBeanFactoryPostProcessor(factory->{
                factory.registerSingleton("generation",generation);factory.registerSingleton("evaluation",evaluation);
                if(drafts!=null) {factory.registerSingleton("drafts",drafts);factory.registerSingleton("finals",finals);}
            });
            var factory=new org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory(0);
            factory.setAddress(java.net.InetAddress.getByName("127.0.0.1"));
            server=factory.getWebServer(servlet->{context.setServletContext(servlet);context.refresh();
                servlet.addServlet("dispatcher",new org.springframework.web.servlet.DispatcherServlet(context)).addMapping("/");});
            logs.start();logger.addAppender(logs);logger.setAdditive(false);server.start();
        }
        java.net.http.HttpResponse<String> review(UUID submissionId) throws Exception {
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+server.getPort()
                    +"/api/submissions/"+submissionId+"/questionnaire-review")).timeout(Duration.ofSeconds(15)).GET().build();
            return java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
        }
        java.net.http.HttpResponse<String> retry(UUID submissionId) throws Exception {
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+server.getPort()
                    +"/api/submissions/"+submissionId+"/questionnaire/retry")).timeout(Duration.ofSeconds(15))
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
            return java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
        }
        int post(UUID id) throws Exception {
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+server.getPort()+"/internal/assessment-jobs/"+id))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type","application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"jobId\":\""+id+"\"}")).build();
            return java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode();
        }
        void assertCommitTimeout() {
            var failure=logs.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getThrowableProxy).filter(Objects::nonNull).findFirst().orElseThrow();
            assertEquals("org.springframework.orm.jpa.JpaSystemException",failure.getClassName());
            assertEquals("org.hibernate.TransactionException",failure.getCause().getClassName());
            assertTrue(failure.getCause().getMessage().contains("transaction timeout expired"));
            var frames=Arrays.stream(failure.getCause().getStackTraceElementProxyArray()).map(Object::toString).toList();
            assertTrue(frames.stream().anyMatch(f->f.contains("MutationStatementPreparer")));
            assertTrue(frames.stream().anyMatch(f->f.contains("EntityUpdateAction")));
            assertTrue(frames.stream().anyMatch(f->f.contains("flushBeforeTransactionCompletion")));
            System.out.println("SECOND_CLAIM_CHAIN JpaSystemException/TransactionException mutationUpdateFlushCommit timeoutExpired=true");
        }
        @Override public void close(){server.stop();context.close();logger.detachAppender(logs);logger.setAdditive(additive);logs.stop();}
    }

    @Configuration @Import({SubmissionService.class,QuestionnaireFinalSubmissionService.class,QuestionnaireGenerationService.class,QuestionnaireService.class,
            QuestionnaireEvaluationService.class,ScorecardService.class})
    static class Config {
        @Bean static ProfileFetchProbe profileFetchProbe(){return new ProfileFetchProbe();}
        @Bean MutableClock clock(){return new MutableClock();}
        @Bean PreparationMapper mapper(){return new PreparationMapper();}
        @Bean AssessmentTaskQueue queue(){return mock(AssessmentTaskQueue.class);}
        @Bean AssessmentAIService ai(){return mock(AssessmentAIService.class);}
        @Bean GitHubEvidenceService github(){return mock(GitHubEvidenceService.class);}
        @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
    }
}
