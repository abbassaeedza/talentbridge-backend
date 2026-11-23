package com.talentbridge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.controller.*;
import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.repository.*;
import com.talentbridge.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringJUnitConfig(classes={DatabaseTestConfig.class,QuestionnaireEvaluationDatabaseIT.Config.class})
@TestPropertySource(properties={"jwt.secret=synthetic-questionnaire-integration-signing-key-not-production",
        "jwt.expiration-ms=60000","jwt.refresh-expiration-ms=120000","openai.api-key=","openai.base-url=http://127.0.0.1:18084/unused",
        "openai.chat-model=gpt-4o-mini","openai.evaluation-model=gpt-4o","openai.max-tokens=100","openai.timeout-seconds=1"})
class QuestionnaireEvaluationDatabaseIT {
    @Autowired QuestionnaireEvaluationService service;
    @Autowired AssessmentJobController delivery;
    @Autowired QuestionnaireService owners;
    @Autowired AssessmentAIService ai;
    @Autowired AssessmentTaskQueue queue;
    @Autowired QuestionnaireGenerationService generation;
    @Autowired EvaluationService legacy;
    @Autowired GitHubService legacyGithub;
    @Autowired OpenAIService legacyAi;
    @Autowired QuestionnaireAssignmentRepository assignments;
    @Autowired EvaluationReportRepository reports;
    @Autowired ObjectMapper json;
    @Autowired QuestionnaireGenerationDatabaseIT.PreparationMapper inputMapper;
    @Autowired QuestionnaireGenerationDatabaseIT.ProfileFetchProbe sqlProbe;
    @Autowired MutableClock clock;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager transactions;
    JdbcTemplate jdbc;
    UUID student,other,coordinator,project,party,submission,snapshot;
    final Instant closes=Instant.parse("2026-11-23T12:00:00Z");
    final Map<AssessmentAIService.PreparedQuestionnaireEvaluation,List<AssessmentAIService.MemberAnswers>> preparedMembers=Collections.synchronizedMap(new IdentityHashMap<>());

    @BeforeEach void fixture() throws Exception {
        inputMapper.resetProbe();
        jdbc=new JdbcTemplate(source); reset(ai,queue,legacyGithub,legacyAi); clock.set(closes);preparedMembers.clear();
        student=UUID.randomUUID(); other=UUID.randomUUID(); coordinator=UUID.randomUUID(); project=UUID.randomUUID();
        party=UUID.randomUUID(); submission=UUID.randomUUID(); snapshot=UUID.randomUUID();
        for(UUID id:List.of(student,other,coordinator)) jdbc.update("INSERT INTO users(id,email,password,first_name,last_name,role,status) VALUES (?,?,?,'Synthetic','Member',?,'APPROVED')",
                id,id+"@evaluation.invalid","synthetic-unused",id.equals(coordinator)?"COORDINATOR":"STUDENT");
        jdbc.update("INSERT INTO projects(id,title,created_by_id) VALUES (?,'Current title',?)",project,coordinator);
        jdbc.update("INSERT INTO parties(id,name,leader_id) VALUES (?,'Unassigned party',?)",party,student);
        jdbc.update("INSERT INTO submissions(id,party_id,project_id,status,assessment_version) VALUES (?,?,?,'SUBMITTED',2)",submission,party,project);
        jdbc.update("INSERT INTO questionnaire_snapshots(id,submission_id,state,frozen_brief,frozen_label,opens_at,closes_at) VALUES (?,?,'OPEN','Frozen approved brief','Frozen title',?::timestamptz,?::timestamptz)",
                snapshot,submission,closes.minus(Duration.ofHours(48)).toString(),closes.toString());
        for(UUID id:List.of(student,other)) {
            UUID assignment=UUID.randomUUID(); var answers=new LinkedHashMap<String,String>();
            jdbc.update("INSERT INTO questionnaire_assignments(id,snapshot_id,student_id,student_name,github_login) VALUES (?,?,?,'Frozen member',?)",assignment,snapshot,id,id.equals(student)?"frozen-login":"missing-login");
            for(int i=1;i<=5;i++) {
                UUID q=UUID.randomUUID(); answers.put(q.toString(),"Locked private answer "+i);
                jdbc.update("INSERT INTO questionnaire_questions(id,assignment_id,position,prompt,hidden_rubric,source_references) VALUES (?,?,?,?,?,?::jsonb)",
                        q,assignment,i,"Explain the frozen implementation boundary "+i,"PRIVATE_RUBRIC_SENTINEL",
                        "[{\"path\":\"src/Engine.java\",\"startLine\":1,\"endLine\":2}]");
            }
            jdbc.update("UPDATE questionnaire_assignments SET answers=?::jsonb,revision=1,saved_at=?::timestamptz WHERE id=?",json.writeValueAsString(answers),closes.minusSeconds(1).toString(),assignment);
        }
        var evidence=new GitHubEvidence(new GitHubEvidence.Resolution(new GitHubEvidence.Repository("synthetic","repo","https://github.com/synthetic/repo"),null,"main","a".repeat(40),"b".repeat(40)),closes,
                List.of(),List.of(new GitHubEvidence.FileEvidence(GitHubEvidence.Kind.SOURCE,"src/Engine.java","c".repeat(40),"d".repeat(64),22,2,1,2,"class Engine {\n}\n",false)),
                new GitHubEvidence.ContributorSnapshot(closes,false,true,List.of(new GitHubEvidence.Contributor("FROZEN-LOGIN",4),new GitHubEvidence.Contributor("outsider",6))),List.of(),
                new GitHubEvidence.Bounds(16,32000,17,4000,8,1000,128,8192,65536,262144,2097152,100));
        jdbc.update("INSERT INTO questionnaire_evidence(snapshot_id,repository_url,repository_branch,commit_sha,captured_at,evidence) VALUES (?,?,'main',?,?::timestamptz,?::jsonb)",
                snapshot,evidence.resolution().repository().url(),evidence.resolution().commitSha(),closes.toString(),json.writeValueAsString(evidence));
        when(ai.prepareQuestionnaireBatches(anyString(),any(),anyList())).thenAnswer(c->List.of(List.copyOf(c.getArgument(2))));
        when(ai.prepareQuestionnaireBatch(anyString(),any(),anyList())).thenAnswer(c->{
            var prepared=mock(AssessmentAIService.PreparedQuestionnaireEvaluation.class);preparedMembers.put(prepared,List.copyOf(c.getArgument(2)));return prepared;
        });
        when(ai.evaluateRepository(any(),any(Runnable.class))).thenAnswer(c->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); ((Runnable)c.getArgument(1)).run(); return repository(84);});
        when(ai.evaluateQuestionnaire(any(),any(Runnable.class))).thenAnswer(c->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); ((Runnable)c.getArgument(1)).run(); return marks(c.getArgument(0),14,16);});
        doAnswer(c->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return null;}).when(queue).enqueue(any(),anyInt(),any());
    }

    @AfterEach void cleanup() {
        inputMapper.resetProbe();
        jdbc.update("DELETE FROM notifications WHERE recipient_id IN (?,?,?)",student,other,coordinator);
        jdbc.update("DELETE FROM scorecard_entries WHERE project_id=?",project);
        jdbc.update("DELETE FROM scorecards WHERE student_id IN (?,?)",student,other);
        jdbc.update("DELETE FROM student_evaluation_scores WHERE evaluation_report_id IN (SELECT id FROM evaluation_reports WHERE submission_id=?)",submission);
        jdbc.update("DELETE FROM evaluation_reports WHERE submission_id=?",submission);
        jdbc.update("DELETE FROM questionnaire_evaluation_steps WHERE run_id IN (SELECT id FROM questionnaire_evaluation_runs WHERE snapshot_id=?)",snapshot);
        jdbc.update("DELETE FROM questionnaire_evaluation_runs WHERE snapshot_id=?",snapshot);
        jdbc.update("DELETE FROM questionnaire_job_attempts WHERE job_id IN (SELECT id FROM questionnaire_jobs WHERE snapshot_id=?)",snapshot);
        jdbc.update("DELETE FROM questionnaire_jobs WHERE snapshot_id=?",snapshot);
        jdbc.update("DELETE FROM questionnaire_mark_adjustments WHERE question_id IN (SELECT q.id FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id=?)",snapshot);
        jdbc.update("DELETE FROM questionnaire_questions WHERE assignment_id IN (SELECT id FROM questionnaire_assignments WHERE snapshot_id=?)",snapshot);
        jdbc.update("DELETE FROM questionnaire_assignments WHERE snapshot_id=?",snapshot);
        jdbc.update("DELETE FROM questionnaire_evidence WHERE snapshot_id=?",snapshot);
        jdbc.update("DELETE FROM questionnaire_snapshots WHERE id=?",snapshot);
        jdbc.update("DELETE FROM submissions WHERE id=?",submission);
        jdbc.update("DELETE FROM parties WHERE id=?",party); jdbc.update("DELETE FROM projects WHERE id=?",project);
        jdbc.update("DELETE FROM users WHERE id IN (?,?,?)",student,other,coordinator);
    }

    @Test void unpublishedAndEarlyCompleteTeamsCannotStartAndExactExpiryCan() {
        clock.set(closes.minusNanos(1));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.start(submission,coordinator)).getStatusCode().value());
        clock.set(closes); jdbc.update("UPDATE questionnaire_snapshots SET state='GENERATING',opens_at=NULL,closes_at=NULL WHERE id=?",snapshot);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.start(submission,coordinator)).getStatusCode().value());
        verifyNoInteractions(ai,queue);
        jdbc.update("UPDATE questionnaire_snapshots SET state='OPEN',opens_at=?::timestamptz,closes_at=?::timestamptz WHERE id=?",closes.minus(Duration.ofHours(48)).toString(),closes.toString(),snapshot);
        assertEquals(2,service.start(submission,coordinator).steps().size());
    }

    @Test void realInternalControllerGradesPublishedSnapshotOneStepAtATimeThenFinalizesOnce() throws Exception {
        var run=service.start(submission,coordinator);
        assertEquals(run.id(),service.start(submission,coordinator).id());
        deliver(run.steps().get(0).jobId());
        verify(ai,times(1)).evaluateRepository(any(),any(Runnable.class)); verify(ai,never()).evaluateQuestionnaire(any(),any(Runnable.class));
        deliver(run.steps().get(0).jobId());
        deliver(run.steps().get(1).jobId());
        var completed=service.run(run.id()); assertEquals("REVIEW",completed.status()); assertNotNull(completed.reportId());
        var report=service.report(submission,coordinator).orElseThrow();
        assertEquals(84,report.getRepositoryScore()); assertEquals(72,report.getQuestionnaireScore()); assertEquals(81,report.getTotalScore());
        assertEquals(0,count("scorecard_entries")); assertEquals(0,count("notifications"));
        assertTrue(service.report(submission,student).isEmpty());
        assertEquals(1,assignments.findAllBySnapshotId(snapshot).stream().map(QuestionnaireAssignment::getGradedRevision).distinct().count());
        assertTrue(assignments.findAllBySnapshotId(snapshot).stream().allMatch(a->a.getRevision()>a.getGradedRevision()));
        clock.set(closes.minusSeconds(1));
        assertThrows(ResponseStatusException.class,()->service.privateReview(submission));
        clock.set(closes);
        service.finalizeReport(completed.reportId(),coordinator); service.finalizeReport(completed.reportId(),coordinator);
        assertEquals(2,count("scorecard_entries")); assertEquals(2,count("notifications"));
        var safe=service.report(submission,student).orElseThrow(); assertTrue(safe.isFinalized());
        String shared=json.writeValueAsString(safe);
        assertFalse(shared.contains("PRIVATE_RUBRIC_SENTINEL")); assertFalse(shared.contains("Locked private answer"));
        assertFalse(shared.contains("aiScore")); assertFalse(shared.contains("effectiveScore"));
        var individual=safe.getStudentScores().stream().filter(s->s.getStudent().getId().equals(student)).findFirst().orElseThrow();
        assertEquals(40,individual.getContributionPercentage()); assertEquals(78.6,individual.getIndividualScore());
        verifyNoInteractions(generation);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"REPOSITORY","QUESTIONNAIRE"})
    void evaluationWorkerCommitsRelayResultOverRemoteDatabase(String kind) throws Exception {
        var run=service.start(submission,coordinator);
        boolean isRepository=kind.equals("REPOSITORY");
        if (!isRepository) deliver(run.steps().get(0).jobId());
        UUID target=run.steps().get(isRepository?0:1).jobId();
        if (isRepository) {
            when(ai.evaluateRepository(any(),any(Runnable.class))).thenAnswer(c->{
                ((Runnable)c.getArgument(1)).run();
                sqlProbe.delayNextResultWrite.set(true);
                return repository(84);
            });
        } else {
            when(ai.evaluateQuestionnaire(any(),any(Runnable.class))).thenAnswer(c->{
                ((Runnable)c.getArgument(1)).run();
                sqlProbe.delayNextResultWrite.set(true);
                return marks(c.getArgument(0),14,16);
            });
        }
        sqlProbe.resultWriteStatements.clear();
        try(var http=new QuestionnaireGenerationDatabaseIT.WorkerHttp(generation,service)) {
            assertEquals(204,http.post(target));
            var read=service.run(run.id());
            var step=read.steps().get(isRepository?0:1);
            assertFalse(sqlProbe.resultWriteStatements.isEmpty());
            assertEquals("SUCCEEDED",step.status().toString(),
                    ()->kind+" result: "+step.errorCode()+"; delayed SQL statements: "+sqlProbe.resultWriteStatements);
            assertEquals(1,step.attempts());
            if (isRepository) {
                assertEquals("DISPATCHED",read.steps().get(1).status().toString());
                assertNull(read.reportId());
            } else {
                assertEquals("REVIEW",read.status());
                assertEquals(1,sqlProbe.resultWriteStatements.stream()
                        .filter(sql->sql.toLowerCase(Locale.ROOT).startsWith("update questionnaire_questions")).count());
                assertEquals(81,service.report(submission,coordinator).orElseThrow().getTotalScore());
                assertTrue(service.report(submission,student).isEmpty());
            }
            assertEquals(0,count("scorecard_entries")); assertEquals(0,count("notifications"));
        }
    }

    @Test void slowFrozenPreparationCannotExpireEvaluationClaimCommitThroughRealHttp() throws Exception {
        var run=service.start(submission,coordinator);UUID job=run.steps().get(0).jobId();
        var frozen=jdbc.queryForList("SELECT id,position,members FROM questionnaire_evaluation_steps WHERE run_id=? ORDER BY position",run.id());
        var quota=jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1");
        clearInvocations(ai,queue);
        inputMapper.type=GitHubEvidence.class;
        inputMapper.afterRead=()->{try {Thread.sleep(2100);} catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Synthetic preparation interrupted");}};
        try(var http=new QuestionnaireGenerationDatabaseIT.WorkerHttp(generation,service)) {
            int status=http.post(job);
            if(status==500) {
                http.assertCommitTimeout();
                var state=jdbc.queryForMap("SELECT status,attempts,lease_token,lease_until,last_error_code FROM questionnaire_jobs WHERE id=?",job);
                assertEquals("DISPATCHED",state.get("status"));assertEquals(0,state.get("attempts"));
                assertNull(state.get("lease_token"));assertNull(state.get("lease_until"));assertNull(state.get("last_error_code"));
                verifyNoInteractions(ai,queue);
            }
            assertEquals(frozen,jdbc.queryForList("SELECT id,position,members FROM questionnaire_evaluation_steps WHERE run_id=? ORDER BY position",run.id()));
            assertEquals(quota,jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1"));
            assertEquals(0,count("scorecard_entries"));assertEquals(0,count("notifications"));
            System.out.println("SECOND_CLAIM_REPRO kind=EVALUATE http="+status+" preparationInTransaction="+inputMapper.inTransaction+" injectedMs=2100");
            assertEquals(204,status);assertFalse(inputMapper.inTransaction);
            assertEquals(QuestionnaireJobStatus.SUCCEEDED,service.run(run.id()).steps().get(0).status());
        }
    }

    @Test void frozenPreparationFailureIsDurableAndRecoverableWithoutFakeGrades() throws Exception {
        var run=service.start(submission,coordinator);UUID job=run.steps().get(0).jobId();
        var frozen=jdbc.queryForList("SELECT position,members FROM questionnaire_evaluation_steps WHERE run_id=? ORDER BY position",run.id());
        var quota=jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1");
        clearInvocations(ai,queue);
        inputMapper.type=GitHubEvidence.class;inputMapper.afterRead=()->{throw new IllegalStateException("Synthetic frozen input failure");};
        try(var http=new QuestionnaireGenerationDatabaseIT.WorkerHttp(generation,service)) {
            assertEquals(204,http.post(job));
            var failed=service.run(run.id());assertEquals("FAILED",failed.status());
            assertEquals(QuestionnaireJobStatus.FAILED,failed.steps().get(0).status());assertEquals(0,failed.steps().get(0).attempts());
            var state=jdbc.queryForMap("SELECT lease_token,lease_until,last_error_code FROM questionnaire_jobs WHERE id=?",job);
            assertNull(state.get("lease_token"));assertNull(state.get("lease_until"));assertEquals("EVALUATION_UNAVAILABLE",state.get("last_error_code"));
            assertEquals(0,count("questionnaire_job_attempts"));assertEquals(0,count("scorecard_entries"));assertEquals(0,count("notifications"));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id=? AND q.ai_score IS NOT NULL",Integer.class,snapshot));
            assertEquals(quota,jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1"));verifyNoInteractions(ai,queue);
            inputMapper.resetProbe();service.retry(run.id());
            assertEquals(frozen,jdbc.queryForList("SELECT position,members FROM questionnaire_evaluation_steps WHERE run_id=? ORDER BY position",run.id()));
            var replacement=service.run(run.id()).steps().get(0).jobId();assertNotEquals(job,replacement);
            assertEquals(204,http.post(replacement));assertEquals(QuestionnaireJobStatus.SUCCEEDED,service.run(run.id()).steps().get(0).status());
        }
    }

    @Test void expiredOrReplacedLeaseDuringFrozenPreparationCannotInvokeProviderOrSave() throws Exception {
        var run=service.start(submission,coordinator);UUID job=run.steps().get(0).jobId();
        var frozen=jdbc.queryForList("SELECT id,members FROM questionnaire_evaluation_steps WHERE run_id=? ORDER BY position",run.id());
        var quota=jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1");clearInvocations(ai,queue);
        try(var http=new QuestionnaireGenerationDatabaseIT.WorkerHttp(generation,service)) {
            for(boolean replace:List.of(false,true)) {
                clock.set(closes);UUID replacement=UUID.randomUUID();
                inputMapper.type=GitHubEvidence.class;inputMapper.afterRead=()->{
                    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    try {assertEquals(503,http.post(job));assertEquals(503,http.post(run.steps().get(1).jobId()));} catch(Exception e){throw new IllegalStateException(e);}
                    if(replace) jdbc.update("UPDATE questionnaire_jobs SET lease_token=? WHERE id=?",replacement,job);
                    else clock.set(closes.plusSeconds(110));
                };
                assertEquals(503,http.post(job));
                var pending=jdbc.queryForMap("SELECT status,attempts,lease_token,last_error_code FROM questionnaire_jobs WHERE id=?",job);
                assertEquals("RUNNING",pending.get("status"));assertEquals(0,pending.get("attempts"));assertNull(pending.get("last_error_code"));
                if(replace) assertEquals(replacement,pending.get("lease_token"));
                assertEquals(frozen,jdbc.queryForList("SELECT id,members FROM questionnaire_evaluation_steps WHERE run_id=? ORDER BY position",run.id()));
                assertEquals(quota,jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1"));
                assertEquals(0,count("questionnaire_job_attempts"));assertEquals(0,count("scorecard_entries"));assertEquals(0,count("notifications"));
                verify(ai,never()).evaluateRepository(any(),any(Runnable.class));verify(ai,never()).evaluateQuestionnaire(any(),any(Runnable.class));verifyNoInteractions(queue);
                jdbc.update("UPDATE questionnaire_jobs SET lease_until=?::timestamptz WHERE id=?",closes.minusSeconds(1).toString(),job);
            }
        }
    }

    @Test void preflightFailureCannotChargeOrDispatchRepositoryStep() {
        when(ai.prepareQuestionnaireBatches(anyString(),any(),anyList())).thenThrow(new AssessmentAIService.AssessmentException("INVALID_INPUT"));
        assertEquals(422,assertThrows(ResponseStatusException.class,()->service.start(submission,coordinator)).getStatusCode().value());
        verifyNoInteractions(queue); verify(ai,never()).evaluateRepository(any(),any(Runnable.class));
        assertEquals(0,count("questionnaire_job_attempts")); assertEquals(0,count("questionnaire_evaluation_runs"));
    }

    @Test void coordinatorDiscoveryFindsPersistedFrozenSubmissionWithoutCurrentAssignmentAndNeverDispatches() {
        var before=jdbc.queryForMap("SELECT state,version,updated_at FROM questionnaire_snapshots WHERE id=?",snapshot);
        var row=service.coordinatorSubmissions().stream().filter(s->s.id().equals(submission)).findFirst().orElseThrow();
        assertEquals("Frozen title",row.projectTitle());assertEquals(project,row.projectId());assertEquals(party,row.partyId());
        assertEquals(2,row.assessmentVersion());assertEquals(closes,row.closesAt());assertNull(row.runId());
        assertEquals(before,jdbc.queryForMap("SELECT state,version,updated_at FROM questionnaire_snapshots WHERE id=?",snapshot));
        assertEquals(0,count("questionnaire_evaluation_runs"));verifyNoInteractions(ai,queue);
    }

    void incomplete(UUID studentId) {
        jdbc.update("UPDATE questionnaire_assignments SET answers='{}'::jsonb WHERE snapshot_id=? AND student_id=?",snapshot,studentId);
    }
    QuestionnaireEvaluationService.RunView grade() {
        var run=service.start(submission,coordinator);for(var step:run.steps()) deliver(step.jobId());return service.run(run.id());
    }

    @Test void allIncompleteUsesPolicyZeroAndOnlyRepositoryRequestEightyFourZeroIsSixtyThree() {
        incomplete(student);incomplete(other);
        var run=grade(); assertEquals(1,run.steps().size());
        var report=service.report(submission,coordinator).orElseThrow();assertEquals(84,report.getRepositoryScore());assertEquals(0,report.getQuestionnaireScore());assertEquals(63,report.getTotalScore());
        verify(ai,never()).prepareQuestionnaireBatch(any(),any(),any());verify(ai,never()).evaluateQuestionnaire(any(),any(Runnable.class));
        assertTrue(service.privateReview(submission).members().stream().allMatch(m->m.gradeState()==QuestionnaireGradeState.INCOMPLETE && m.effectiveScore()==0));
    }

    @Test void postExpiryIncompletePolicyIsZeroEvenBeforeGradingWhileCompleteMemberIsTechnicalPending() {
        incomplete(other);
        var before=jdbc.queryForList("SELECT id,revision,grade_state,ai_score,effective_score FROM questionnaire_assignments WHERE snapshot_id=? ORDER BY id",snapshot);
        var view=service.privateReview(submission);
        var incomplete=view.members().stream().filter(m->m.studentId().equals(other)).findFirst().orElseThrow();
        var complete=view.members().stream().filter(m->m.studentId().equals(student)).findFirst().orElseThrow();
        assertEquals(QuestionnaireGradeState.INCOMPLETE,incomplete.gradeState());assertEquals(0,incomplete.effectiveScore());
        assertEquals(QuestionnaireGradeState.PENDING,complete.gradeState());assertNull(complete.effectiveScore());
        assertEquals(before,jdbc.queryForList("SELECT id,revision,grade_state,ai_score,effective_score FROM questionnaire_assignments WHERE snapshot_id=? ORDER BY id",snapshot));
        verifyNoInteractions(ai,queue);
    }

    @Test void mixedTeamSendsOnlyCompleteMemberButMeanKeepsIncompleteZeroAndTechnicalFailureStaysPending() {
        incomplete(other);
        when(ai.evaluateQuestionnaire(any(),any(Runnable.class))).thenAnswer(c->{((Runnable)c.getArgument(1)).run();throw new AssessmentAIService.AssessmentException("INVALID_RESPONSE");});
        var run=service.start(submission,coordinator);deliver(run.steps().get(0).jobId());deliver(run.steps().get(1).jobId());
        assertEquals("RUNNING",service.run(run.id()).status());assertTrue(service.report(submission,coordinator).isEmpty());
        assertTrue(assignments.findAllBySnapshotId(snapshot).stream().allMatch(a->a.getStudentId().equals(other)
                ? a.getGradeState()==QuestionnaireGradeState.INCOMPLETE && a.getEffectiveScore()==0 : a.getEffectiveScore()==null));
        when(ai.evaluateQuestionnaire(any(),any(Runnable.class))).thenAnswer(c->{((Runnable)c.getArgument(1)).run();assertEquals(1,preparedMembers.get(c.getArgument(0)).size());return marks(c.getArgument(0),14,16);});
        clock.set(closes.plusSeconds(30));deliver(run.steps().get(1).jobId());
        assertEquals(36,service.report(submission,coordinator).orElseThrow().getQuestionnaireScore());
        assertEquals(72,service.report(submission,coordinator).orElseThrow().getTotalScore());
        verify(ai,times(1)).evaluateRepository(any(),any(Runnable.class));
    }

    @Test void eightySixtyIsSeventyFiveAndIntermediateValuesAreNotRounded() {
        when(ai.evaluateRepository(any(),any(Runnable.class))).thenAnswer(c->{((Runnable)c.getArgument(1)).run();return repository(80);});
        when(ai.evaluateQuestionnaire(any(),any(Runnable.class))).thenAnswer(c->{((Runnable)c.getArgument(1)).run();return marks(c.getArgument(0),12,12);});
        grade();var report=service.report(submission,coordinator).orElseThrow();assertEquals(80,report.getRepositoryScore());assertEquals(60,report.getQuestionnaireScore());assertEquals(75,report.getTotalScore());
        assertEquals(72.75,jdbc.queryForObject("SELECT s.individual_score FROM student_evaluation_scores s JOIN evaluation_reports e ON e.id=s.evaluation_report_id WHERE e.submission_id=? AND s.student_id=?",Double.class,submission,student),1e-10);
    }

    @Test void distinctRepositoryWeightsAndUnroundedIndividualSurviveFinalization() {
        doAnswer(c->{((Runnable)c.getArgument(1)).run();var categories=new LinkedHashMap<String,AssessmentAIService.CategoryScore>();
            String[] names={"authenticity","quality","functionality","scope","collaboration"};int[] values={100,80,60,40,20};
            for(int i=0;i<names.length;i++) categories.put(names[i],new AssessmentAIService.CategoryScore(values[i],"Distinct bounded repository evidence"));
            return new AssessmentAIService.RepositoryEvaluation(categories);
        }).when(ai).evaluateRepository(any(),any(Runnable.class));
        var run=grade();var draft=service.report(submission,coordinator).orElseThrow();
        assertEquals(65,draft.getRepositoryScore());assertEquals(72,draft.getQuestionnaireScore());assertEquals(66.8,draft.getTotalScore());
        assertEquals(65,jdbc.queryForObject("SELECT repository_score FROM evaluation_reports WHERE submission_id=?",Double.class,submission));
        assertEquals(66.75,jdbc.queryForObject("SELECT total_score FROM evaluation_reports WHERE submission_id=?",Double.class,submission),1e-10);
        assertEquals(64.7475,jdbc.queryForObject("SELECT s.individual_score FROM student_evaluation_scores s JOIN evaluation_reports e ON e.id=s.evaluation_report_id WHERE e.submission_id=? AND s.student_id=?",Double.class,submission,student),1e-10);
        assertEquals(64.7,draft.getStudentScores().stream().filter(s->s.getStudent().getId().equals(student)).findFirst().orElseThrow().getIndividualScore());
        var finalized=service.finalizeReport(run.reportId(),coordinator);assertTrue(finalized.isFinalized());assertEquals(66.8,finalized.getTotalScore());assertEquals(65,finalized.getRepositoryScore());
        assertEquals(66.75,jdbc.queryForObject("SELECT total_score FROM evaluation_reports WHERE submission_id=?",Double.class,submission),1e-10);
        assertEquals(64.7475,jdbc.queryForObject("SELECT e.score FROM scorecard_entries e JOIN scorecards c ON c.id=e.scorecard_id WHERE e.project_id=? AND c.student_id=?",Double.class,project,student),1e-10);
        assertEquals(2,count("notifications"));assertEquals(2,count("scorecard_entries"));
    }

    @Test void foreignAndIncompleteQuestionPutRejectWithoutChangingEitherSubmissionsMarksTotalsOrAudits() throws Exception {
        incomplete(other);grade();
        var ownQuestion=service.privateReview(submission).members().stream().filter(m->m.studentId().equals(student)).findFirst().orElseThrow().questions().get(0).id();
        service.adjust(submission,ownQuestion,coordinator,15,"Existing reviewed evidence");
        UUID foreignParty=UUID.randomUUID(),foreignSubmission=UUID.randomUUID(),foreignSnapshot=UUID.randomUUID(),foreignAssignment=UUID.randomUUID();
        jdbc.update("INSERT INTO parties(id,name,leader_id) VALUES (?,'Other frozen team',?)",foreignParty,student);
        jdbc.update("INSERT INTO submissions(id,party_id,project_id,status,assessment_version) VALUES (?,?,?,'UNDER_EVALUATION',2)",foreignSubmission,foreignParty,project);
        jdbc.update("INSERT INTO questionnaire_snapshots(id,submission_id,state,frozen_brief,opens_at,closes_at) VALUES (?,?,'OPEN','Other frozen brief',?::timestamptz,?::timestamptz)",foreignSnapshot,foreignSubmission,closes.minus(Duration.ofHours(48)).toString(),closes.toString());
        jdbc.update("INSERT INTO questionnaire_assignments(id,snapshot_id,student_id,student_name) VALUES (?,?,?,'Other frozen member')",foreignAssignment,foreignSnapshot,student);
        jdbc.update("INSERT INTO questionnaire_questions(assignment_id,position,prompt,hidden_rubric,source_references) SELECT ?,position,prompt,hidden_rubric,source_references FROM questionnaire_questions WHERE assignment_id=(SELECT id FROM questionnaire_assignments WHERE snapshot_id=? AND student_id=?)",foreignAssignment,snapshot,student);
        jdbc.update("UPDATE questionnaire_assignments SET answers=(SELECT jsonb_object_agg(id::text,'Other locked answer') FROM questionnaire_questions WHERE assignment_id=?) WHERE id=?",foreignAssignment,foreignAssignment);
        jdbc.update("INSERT INTO questionnaire_evidence(snapshot_id,repository_url,repository_branch,commit_sha,captured_at,evidence) SELECT ?,repository_url,repository_branch,commit_sha,captured_at,evidence FROM questionnaire_evidence WHERE snapshot_id=?",foreignSnapshot,snapshot);
        try {
            var foreignRun=service.start(foreignSubmission,coordinator);for(var step:foreignRun.steps()) httpDeliver(step.jobId());
            assertEquals("REVIEW",service.run(foreignRun.id()).status());
            UUID foreign=jdbc.queryForObject("SELECT id FROM questionnaire_questions WHERE assignment_id=? AND position=1",UUID.class,foreignAssignment);
            UUID incomplete=jdbc.queryForObject("SELECT q.id FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id=? AND a.student_id=? AND q.position=1",UUID.class,snapshot,other);
            var beforeMarks=jdbc.queryForList("SELECT q.id,q.ai_score,q.effective_score,a.revision,a.ai_score AS member_ai,a.effective_score AS member_effective FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id IN (?,?) ORDER BY q.id",snapshot,foreignSnapshot);
            var beforeTotals=jdbc.queryForList("SELECT id,repository_score,questionnaire_score,total_score,finalized,updated_at FROM evaluation_reports WHERE submission_id IN (?,?) ORDER BY id",submission,foreignSubmission);
            var beforeAudits=jdbc.queryForList("SELECT m.* FROM questionnaire_mark_adjustments m JOIN questionnaire_questions q ON q.id=m.question_id JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id IN (?,?) ORDER BY m.id",snapshot,foreignSnapshot);
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new QuestionnaireEvaluationController(service)).setControllerAdvice(new com.talentbridge.exception.GlobalExceptionHandler()).build();
            for(var entry:Map.of(foreign,404,incomplete,409).entrySet()) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/evaluations/submission/{id}/questions/{question}/mark",submission,entry.getKey())
                        .contentType("application/json").content("{\"mark\":20,\"reason\":\"Must not be applied\"}"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(entry.getValue()));
                assertEquals(beforeMarks,jdbc.queryForList("SELECT q.id,q.ai_score,q.effective_score,a.revision,a.ai_score AS member_ai,a.effective_score AS member_effective FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id IN (?,?) ORDER BY q.id",snapshot,foreignSnapshot));
                assertEquals(beforeTotals,jdbc.queryForList("SELECT id,repository_score,questionnaire_score,total_score,finalized,updated_at FROM evaluation_reports WHERE submission_id IN (?,?) ORDER BY id",submission,foreignSubmission));
                assertEquals(beforeAudits,jdbc.queryForList("SELECT m.* FROM questionnaire_mark_adjustments m JOIN questionnaire_questions q ON q.id=m.question_id JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id IN (?,?) ORDER BY m.id",snapshot,foreignSnapshot));
            }
        } finally {
            jdbc.update("DELETE FROM student_evaluation_scores WHERE evaluation_report_id IN (SELECT id FROM evaluation_reports WHERE submission_id=?)",foreignSubmission);
            jdbc.update("DELETE FROM questionnaire_evaluation_steps WHERE run_id IN (SELECT id FROM questionnaire_evaluation_runs WHERE snapshot_id=?)",foreignSnapshot);
            jdbc.update("DELETE FROM questionnaire_evaluation_runs WHERE snapshot_id=?",foreignSnapshot);
            jdbc.update("DELETE FROM questionnaire_job_attempts WHERE job_id IN (SELECT id FROM questionnaire_jobs WHERE snapshot_id=?)",foreignSnapshot);
            jdbc.update("DELETE FROM questionnaire_jobs WHERE snapshot_id=?",foreignSnapshot);
            jdbc.update("DELETE FROM questionnaire_mark_adjustments WHERE question_id IN (SELECT id FROM questionnaire_questions WHERE assignment_id=?)",foreignAssignment);
            jdbc.update("DELETE FROM questionnaire_questions WHERE assignment_id=?",foreignAssignment);jdbc.update("DELETE FROM questionnaire_assignments WHERE id=?",foreignAssignment);
            jdbc.update("DELETE FROM questionnaire_evidence WHERE snapshot_id=?",foreignSnapshot);
            jdbc.update("DELETE FROM questionnaire_snapshots WHERE id=?",foreignSnapshot);jdbc.update("DELETE FROM evaluation_reports WHERE submission_id=?",foreignSubmission);
            jdbc.update("DELETE FROM submissions WHERE id=?",foreignSubmission);jdbc.update("DELETE FROM parties WHERE id=?",foreignParty);
        }
    }

    @Test void reasonedOverrideRetainsAiAuditAndReevaluationPreservesLockedAnswerIdentityAndEffectiveOverride() {
        var run=grade();var member=service.privateReview(submission).members().get(0);var q=member.questions().get(0);
        var adjusted=service.adjust(submission,q.id(),coordinator,20,"Reviewed actual code اردو 🧪\t\n\r");
        var question=adjusted.members().stream().flatMap(m->m.questions().stream()).filter(x->x.id().equals(q.id())).findFirst().orElseThrow();
        assertEquals(14,question.aiScore());assertEquals(20,question.effectiveScore());assertEquals(1,question.adjustments().size());
        var audit=question.adjustments().get(0);assertEquals(14,audit.previousScore());assertEquals(20,audit.adjustedScore());assertEquals(coordinator,audit.actorId());assertEquals(closes,audit.adjustedAt());
        assertEquals(81.75,jdbc.queryForObject("SELECT total_score FROM evaluation_reports WHERE submission_id=?",Double.class,submission),1e-10);
        var locked=jdbc.queryForList("SELECT id,answers,locked_answer_revision FROM questionnaire_assignments WHERE snapshot_id=? ORDER BY id",snapshot);
        var next=service.reevaluate(run.reportId(),coordinator);assertNotEquals(run.id(),next.id());
        when(ai.evaluateQuestionnaire(any(),any(Runnable.class))).thenAnswer(c->{((Runnable)c.getArgument(1)).run();return marks(c.getArgument(0),10,10);});
        for(var step:next.steps()) deliver(step.jobId());
        assertEquals(locked,jdbc.queryForList("SELECT id,answers,locked_answer_revision FROM questionnaire_assignments WHERE snapshot_id=? ORDER BY id",snapshot));
        var after=service.privateReview(submission).members().stream().flatMap(m->m.questions().stream()).filter(x->x.id().equals(q.id())).findFirst().orElseThrow();
        assertEquals(14,after.aiScore());assertEquals(20,after.effectiveScore());assertEquals(1,after.adjustments().size());
        assertEquals(55,service.report(submission,coordinator).orElseThrow().getQuestionnaireScore());
        service.finalizeReport(run.reportId(),coordinator);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.adjust(submission,q.id(),coordinator,1,"Too late")).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.reevaluate(run.reportId(),coordinator)).getStatusCode().value());
    }

    @Test void invalidOverrideReasonsNeverMutateOriginalMarksOrAudit() {
        grade();var q=service.privateReview(submission).members().get(0).questions().get(0);
        for(String reason:List.of(""," \t\r\n","x".repeat(2001),"bad\u0001","bad\uD800"))
            assertEquals(400,assertThrows(ResponseStatusException.class,()->service.adjust(submission,q.id(),coordinator,20,reason)).getStatusCode().value());
        var unchanged=service.privateReview(submission).members().get(0).questions().get(0);
        assertEquals(14,unchanged.effectiveScore());assertTrue(unchanged.adjustments().isEmpty());
    }

    @Test void quotaDefersWithoutAttemptAndForcedEarlyDeliveryCreatesDistinctFutureDispatchGeneration() {
        var run=service.start(submission,coordinator);var repo=run.steps().get(0);var reset=Instant.parse("2026-11-23T19:00:00Z");
        when(ai.evaluateRepository(any(),any(Runnable.class))).thenThrow(new AssessmentQuotaService.QuotaExceeded(reset));
        deliver(repo.jobId());var deferred=service.run(run.id()).steps().get(0);
        assertEquals(QuestionnaireJobStatus.DEFERRED,deferred.status());assertEquals(0,deferred.attempts());assertEquals(reset,deferred.nextAttemptAt());
        deliver(repo.jobId());assertEquals(2,service.run(run.id()).steps().get(0).dispatchGeneration());verify(queue).enqueue(repo.jobId(),2,reset);
        assertEquals(0,count("questionnaire_job_attempts"));verify(ai,times(1)).evaluateRepository(any(),any(Runnable.class));
    }

    @Test void concurrentDuplicateDeliveryCannotMakeSecondAttemptOrAcknowledgeAnActiveLease() throws Exception {
        var run=service.start(submission,coordinator);var job=run.steps().get(0).jobId();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        when(ai.evaluateRepository(any(),any(Runnable.class))).thenAnswer(c->{((Runnable)c.getArgument(1)).run();entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));return repository(84);});
        try {
            var first=pool.submit(()->deliver(job));assertTrue(entered.await(10,TimeUnit.SECONDS));
            assertEquals(503,assertThrows(ResponseStatusException.class,()->delivery.handle(job,new AssessmentJobController.JobReference(job))).getStatusCode().value());
            assertEquals(1,count("questionnaire_job_attempts"));release.countDown();first.get(10,TimeUnit.SECONDS);
            deliver(job);verify(ai,times(1)).evaluateRepository(any(),any(Runnable.class));
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void threeActualPrivateFailuresAndRecoveryRetainSuccessfulRepositoryAndImmutablePartition() {
        var run=service.start(submission,coordinator);deliver(run.steps().get(0).jobId());
        var partition=jdbc.queryForList("SELECT id,members FROM questionnaire_evaluation_steps WHERE run_id=? ORDER BY position",run.id());
        var failed=run.steps().get(1).jobId();
        when(ai.evaluateQuestionnaire(any(),any(Runnable.class))).thenAnswer(c->{((Runnable)c.getArgument(1)).run();throw new AssessmentAIService.AssessmentException("RELAY_FAILURE");});
        for(int i=0;i<3;i++){deliver(failed);clock.set(clock.instant().plusSeconds(30));}
        assertEquals("FAILED",service.run(run.id()).status());assertEquals(4,count("questionnaire_job_attempts"));
        var recovered=service.retry(run.id());var replacement=recovered.steps().get(1).jobId();assertNotEquals(failed,replacement);
        assertEquals(failed,jdbc.queryForObject("SELECT retry_of FROM questionnaire_jobs WHERE id=?",UUID.class,replacement));
        when(ai.evaluateQuestionnaire(any(),any(Runnable.class))).thenAnswer(c->{((Runnable)c.getArgument(1)).run();return marks(c.getArgument(0),14,16);});
        deliver(replacement);assertEquals("REVIEW",service.run(run.id()).status());
        assertEquals(partition,jdbc.queryForList("SELECT id,members FROM questionnaire_evaluation_steps WHERE run_id=? ORDER BY position",run.id()));
        verify(ai,times(1)).evaluateRepository(any(),any(Runnable.class));verify(ai,times(1)).prepareQuestionnaireBatches(anyString(),any(),anyList());
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"prepare","beforeCallback"})
    void preparationFailureAfterEarlierActualAttemptTerminatesThroughInternalHttpWithoutFurtherChargesOrDispatch(String stage) throws Exception {
        when(ai.prepareQuestionnaireBatches(anyString(),any(),anyList())).thenAnswer(c->{
            List<AssessmentAIService.MemberAnswers> all=c.getArgument(2);return all.stream().map(List::of).toList();
        });
        var run=service.start(submission,coordinator);httpDeliver(run.steps().get(0).jobId());httpDeliver(run.steps().get(1).jobId());
        UUID target=run.steps().get(2).jobId();
        var partition=jdbc.queryForList("SELECT id,members FROM questionnaire_evaluation_steps WHERE run_id=? ORDER BY position",run.id());
        var completed=jdbc.queryForList("SELECT id,result FROM questionnaire_evaluation_steps WHERE run_id=? AND position<2 ORDER BY position",run.id());
        var identity=jdbc.queryForList("SELECT id,answers,locked_answer_revision FROM questionnaire_assignments WHERE snapshot_id=? ORDER BY id",snapshot);
        LocalDate stored=jdbc.queryForObject("SELECT quota_day FROM assessment_ai_quota WHERE id=1",java.sql.Date.class).toLocalDate();
        clock.set(clock.instant().isAfter(stored.plusDays(1).atStartOfDay(ZoneId.of("Asia/Karachi")).toInstant())?clock.instant():stored.plusDays(1).atStartOfDay(ZoneId.of("Asia/Karachi")).toInstant());
        var quota=new AssessmentQuotaService(jdbc,transactions,clock,50);
        doAnswer(c->{quota.reserve(c.getArgument(1));throw new AssessmentAIService.AssessmentException("RELAY_FAILURE");})
                .when(ai).evaluateQuestionnaire(any(),any(Runnable.class));
        httpDeliver(target);assertEquals(1,service.run(run.id()).steps().get(2).attempts());
        var charged=jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1");
        var history=jdbc.queryForList("SELECT attempt,outcome,started_at,finished_at FROM questionnaire_job_attempts WHERE job_id=? ORDER BY attempt",target);
        int generation=service.run(run.id()).steps().get(2).dispatchGeneration();clearInvocations(ai,queue);
        if(stage.equals("prepare")) doThrow(new AssessmentAIService.AssessmentException("INVALID_RELAY_CONFIGURATION")).when(ai).prepareQuestionnaireBatch(anyString(),any(),anyList());
        else doThrow(new AssessmentAIService.AssessmentException("QUOTA_UNAVAILABLE")).when(ai).evaluateQuestionnaire(any(),any(Runnable.class));
        clock.set(clock.instant().plusSeconds(30));httpDeliver(target);clock.set(clock.instant().plusSeconds(30));httpDeliver(target);
        var stopped=service.run(run.id());assertEquals("FAILED",stopped.status());assertEquals(QuestionnaireJobStatus.FAILED,stopped.steps().get(2).status());
        assertEquals(1,stopped.steps().get(2).attempts());assertEquals(generation,stopped.steps().get(2).dispatchGeneration());
        assertEquals(charged,jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1"));
        assertEquals(history,jdbc.queryForList("SELECT attempt,outcome,started_at,finished_at FROM questionnaire_job_attempts WHERE job_id=? ORDER BY attempt",target));
        assertEquals(completed,jdbc.queryForList("SELECT id,result FROM questionnaire_evaluation_steps WHERE run_id=? AND position<2 ORDER BY position",run.id()));
        assertEquals(identity,jdbc.queryForList("SELECT id,answers,locked_answer_revision FROM questionnaire_assignments WHERE snapshot_id=? ORDER BY id",snapshot));
        verifyNoInteractions(queue);verify(ai,never()).evaluateRepository(any(),any(Runnable.class));verify(ai,times(stage.equals("prepare")?0:1)).evaluateQuestionnaire(any(),any(Runnable.class));
        verify(ai,times(1)).prepareRepositoryEvaluation(anyString(),any());
        clearInvocations(ai);
        doAnswer(c->{var prepared=mock(AssessmentAIService.PreparedQuestionnaireEvaluation.class);preparedMembers.put(prepared,List.copyOf(c.getArgument(2)));return prepared;})
                .when(ai).prepareQuestionnaireBatch(anyString(),any(),anyList());
        doAnswer(c->{quota.reserve(c.getArgument(1));return marks(c.getArgument(0),14,16);}).when(ai).evaluateQuestionnaire(any(),any(Runnable.class));
        var recovered=service.retry(run.id());UUID replacement=recovered.steps().get(2).jobId();assertNotEquals(target,replacement);
        assertEquals(target,jdbc.queryForObject("SELECT retry_of FROM questionnaire_jobs WHERE id=?",UUID.class,replacement));
        httpDeliver(replacement);assertEquals("REVIEW",service.run(run.id()).status());
        assertEquals(partition,jdbc.queryForList("SELECT id,members FROM questionnaire_evaluation_steps WHERE run_id=? ORDER BY position",run.id()));
        assertEquals(completed,jdbc.queryForList("SELECT id,result FROM questionnaire_evaluation_steps WHERE run_id=? AND position<2 ORDER BY position",run.id()));
        assertEquals(history,jdbc.queryForList("SELECT attempt,outcome,started_at,finished_at FROM questionnaire_job_attempts WHERE job_id=? ORDER BY attempt",target));
        verify(ai,never()).evaluateRepository(any(),any(Runnable.class));verify(ai,times(1)).evaluateQuestionnaire(any(),any(Runnable.class));
    }

    void httpDeliver(UUID id) throws Exception {
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(delivery).setControllerAdvice(new com.talentbridge.exception.GlobalExceptionHandler()).build()
                .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/assessment-jobs/{id}",id)
                        .contentType("application/json").content("{\"jobId\":\""+id+"\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
    }

    @Test void quotaAfterEarlierActualFailureStillDefersRatherThanTerminatingOrChargingAnotherAttempt() throws Exception {
        var run=service.start(submission,coordinator);httpDeliver(run.steps().get(0).jobId());UUID target=run.steps().get(1).jobId();
        doAnswer(c->{((Runnable)c.getArgument(1)).run();throw new AssessmentAIService.AssessmentException("RELAY_FAILURE");}).when(ai).evaluateQuestionnaire(any(),any(Runnable.class));
        httpDeliver(target);var history=jdbc.queryForList("SELECT attempt,outcome,started_at,finished_at FROM questionnaire_job_attempts WHERE job_id=?",target);
        var quota=jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1");clearInvocations(ai);
        Instant reset=clock.instant().atZone(ZoneId.of("Asia/Karachi")).toLocalDate().plusDays(1).atStartOfDay(ZoneId.of("Asia/Karachi")).toInstant();
        doThrow(new AssessmentQuotaService.QuotaExceeded(reset)).when(ai).evaluateQuestionnaire(any(),any(Runnable.class));
        clock.set(clock.instant().plusSeconds(30));httpDeliver(target);var deferred=service.run(run.id()).steps().get(1);
        assertEquals("RUNNING",service.run(run.id()).status());assertEquals(QuestionnaireJobStatus.DEFERRED,deferred.status());assertEquals(reset,deferred.nextAttemptAt());assertEquals(1,deferred.attempts());
        httpDeliver(target);assertEquals(deferred.dispatchGeneration()+1,service.run(run.id()).steps().get(1).dispatchGeneration());
        assertEquals(history,jdbc.queryForList("SELECT attempt,outcome,started_at,finished_at FROM questionnaire_job_attempts WHERE job_id=?",target));
        assertEquals(quota,jdbc.queryForMap("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1"));
        verify(ai,times(1)).evaluateQuestionnaire(any(),any(Runnable.class));verify(ai,never()).evaluateRepository(any(),any(Runnable.class));
    }

    @RepeatedTest(3) void concurrentOverrideAndFinalizationAreLinearizableAndDuplicateFinalizersPublishOnce() throws Exception {
        var run=grade();var q=service.privateReview(submission).members().get(0).questions().get(0);
        var pool=Executors.newFixedThreadPool(3);var start=new CountDownLatch(1);
        try {
            var override=pool.submit(()->{start.await();try{service.adjust(submission,q.id(),coordinator,20,"Concurrent evidence review");return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}});
            var first=pool.submit(()->{start.await();return service.finalizeReport(run.reportId(),coordinator);});
            var second=pool.submit(()->{start.await();return service.finalizeReport(run.reportId(),coordinator);});start.countDown();
            int adjusted=override.get(10,TimeUnit.SECONDS);assertTrue(adjusted==200 || adjusted==409);
            var finalized=first.get(10,TimeUnit.SECONDS);assertEquals(finalized.getTotalScore(),second.get(10,TimeUnit.SECONDS).getTotalScore());
            assertEquals(adjusted==200?81.8:81,finalized.getTotalScore());
            assertEquals(2,count("notifications"));assertEquals(2,count("scorecard_entries"));
        } finally{pool.shutdownNow();}
    }

    @Test void persistedQuestionSubsetsRetainPartialResultsAcrossRecoveryWithoutPublishingPartialTotals() {
        when(ai.prepareQuestionnaireBatches(anyString(),any(),anyList())).thenAnswer(c->{
            List<AssessmentAIService.MemberAnswers> all=c.getArgument(2);var a=all.get(0);var b=all.get(1);
            return List.of(List.of(new AssessmentAIService.MemberAnswers(a.memberId(),a.questions().subList(0,3))),
                    List.of(new AssessmentAIService.MemberAnswers(a.memberId(),a.questions().subList(3,5)),new AssessmentAIService.MemberAnswers(b.memberId(),b.questions().subList(0,2))),
                    List.of(new AssessmentAIService.MemberAnswers(b.memberId(),b.questions().subList(2,5))));
        });
        var run=service.start(submission,coordinator);assertEquals(4,run.steps().size());
        var partition=jdbc.queryForList("SELECT id,members FROM questionnaire_evaluation_steps WHERE run_id=? ORDER BY position",run.id());
        deliver(run.steps().get(0).jobId());deliver(run.steps().get(1).jobId());
        assertTrue(service.report(submission,coordinator).isEmpty());assertTrue(assignments.findAllBySnapshotId(snapshot).stream().allMatch(a->a.getEffectiveScore()==null));
        service.retry(run.id());deliver(run.steps().get(2).jobId());assertTrue(service.report(submission,coordinator).isEmpty());
        deliver(run.steps().get(3).jobId());assertEquals("REVIEW",service.run(run.id()).status());assertEquals(72,service.report(submission,coordinator).orElseThrow().getQuestionnaireScore());
        assertEquals(partition,jdbc.queryForList("SELECT id,members FROM questionnaire_evaluation_steps WHERE run_id=? ORDER BY position",run.id()));
        verify(ai,times(1)).prepareQuestionnaireBatches(anyString(),any(),anyList());verify(ai,times(1)).evaluateRepository(any(),any(Runnable.class));verify(ai,times(3)).evaluateQuestionnaire(any(),any(Runnable.class));
    }

    @Test void expiredCrashAttemptBecomesUnknownAndStaleWorkerCannotAcknowledgeReplacement() throws Exception {
        var run=service.start(submission,coordinator);UUID job=run.steps().get(0).jobId();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(ai.evaluateRepository(any(),any(Runnable.class))).thenAnswer(c->{((Runnable)c.getArgument(1)).run();
            if(calls.incrementAndGet()==1){entered.countDown();assertTrue(release.await(10,TimeUnit.SECONDS));}return repository(84);});
        try {
            var stale=pool.submit(()->deliver(job));assertTrue(entered.await(10,TimeUnit.SECONDS));clock.set(closes.plusSeconds(111));
            deliver(job);release.countDown();var failure=assertThrows(ExecutionException.class,()->stale.get(10,TimeUnit.SECONDS));
            assertEquals(503,((ResponseStatusException)failure.getCause()).getStatusCode().value());
            assertEquals(List.of("UNKNOWN","SUCCEEDED"),jdbc.queryForList("SELECT outcome FROM questionnaire_job_attempts WHERE job_id=? ORDER BY attempt",String.class,job));
            assertEquals(2,service.run(run.id()).steps().get(0).attempts());
            deliver(run.steps().get(1).jobId());assertEquals("REVIEW",service.run(run.id()).status());
        } finally{release.countDown();pool.shutdownNow();}
    }

    @Test void dispatchFailureAfterResultCommitIsRetryableAndCannotRerunCompletedRepository() {
        var run=service.start(submission,coordinator);
        doThrow(new AssessmentTaskQueue.DispatchException("DISPATCH_UNAVAILABLE")).when(queue).enqueue(any(),anyInt(),any());
        var repo=run.steps().get(0).jobId();assertEquals(503,assertThrows(ResponseStatusException.class,()->deliver(repo)).getStatusCode().value());
        assertEquals(QuestionnaireJobStatus.SUCCEEDED,service.run(run.id()).steps().get(0).status());
        assertEquals("DISPATCH_UNAVAILABLE",service.run(run.id()).steps().get(1).errorCode());
        reset(queue);service.retry(run.id());deliver(run.steps().get(1).jobId());
        assertEquals("REVIEW",service.run(run.id()).status());verify(ai,times(1)).evaluateRepository(any(),any(Runnable.class));
    }

    @Test void slowRelayResultCommitsBeforeDeferringSuccessorDispatchToRedelivery() throws Exception {
        var run=service.start(submission,coordinator);
        UUID repo=run.steps().get(0).jobId(), next=run.steps().get(1).jobId();
        when(ai.evaluateRepository(any(),any(Runnable.class))).thenAnswer(c->{
            ((Runnable)c.getArgument(1)).run();
            clock.set(closes.plusSeconds(96));
            return repository(84);
        });
        try(var http=new QuestionnaireGenerationDatabaseIT.WorkerHttp(generation,service)) {
            assertEquals(503,http.post(repo));
            var committed=service.run(run.id());
            assertEquals(QuestionnaireJobStatus.SUCCEEDED,committed.steps().get(0).status());
            assertEquals(1,committed.steps().get(0).attempts());
            assertEquals(QuestionnaireJobStatus.PENDING,committed.steps().get(1).status());
            verify(queue,never()).enqueue(eq(next),anyInt(),any());
            assertEquals(204,http.post(repo));
            assertEquals(QuestionnaireJobStatus.DISPATCHED,service.run(run.id()).steps().get(1).status());
            verify(queue).enqueue(next,0,closes);
            verify(ai,times(1)).evaluateRepository(any(),any(Runnable.class));
        }
    }

    @Test void finalizationNotificationFailureRollsBackReportScorecardsAndAllFrozenNotifications() {
        var run=grade();String trigger="task4_notification_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION "+trigger+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.recipient_id='"+student+"'::uuid THEN RAISE EXCEPTION 'SYNTHETIC_FINALIZATION_FAILURE'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION "+trigger+"()");
        try {
            assertThrows(RuntimeException.class,()->service.finalizeReport(run.reportId(),coordinator));
            assertFalse(service.report(submission,coordinator).orElseThrow().isFinalized());assertEquals("REVIEW",service.run(run.id()).status());
            assertEquals(0,count("scorecard_entries"));assertEquals(0,count("notifications"));
        } finally {jdbc.execute("DROP TRIGGER "+trigger+" ON notifications");jdbc.execute("DROP FUNCTION "+trigger+"()");}
        service.finalizeReport(run.reportId(),coordinator);assertEquals(2,count("scorecard_entries"));assertEquals(2,count("notifications"));
    }

    @Test void reportInsertFailureRollsBackBatchedQuestionMarksAndPrivateResult() throws Exception {
        var run=service.start(submission,coordinator);
        UUID repo=run.steps().get(0).jobId(), privateStep=run.steps().get(1).jobId();
        deliver(repo);
        String trigger="task4_report_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE FUNCTION "+trigger+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.submission_id='"+submission+"'::uuid THEN RAISE EXCEPTION 'SYNTHETIC_REPORT_FAILURE'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON evaluation_reports FOR EACH ROW EXECUTE FUNCTION "+trigger+"()");
        try(var http=new QuestionnaireGenerationDatabaseIT.WorkerHttp(generation,service)) {
            assertEquals(204,http.post(privateStep));
            assertTrue(service.report(submission,coordinator).isEmpty());
            assertEquals(0,jdbc.queryForObject("""
                    SELECT count(*) FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id
                    WHERE a.snapshot_id=? AND (q.ai_score IS NOT NULL OR q.effective_score IS NOT NULL OR q.grading_notes IS NOT NULL)
                    """,Integer.class,snapshot));
            assertTrue(assignments.findAllBySnapshotId(snapshot).stream().allMatch(a->a.getEffectiveScore()==null));
            assertEquals(QuestionnaireJobStatus.SUCCEEDED,service.run(run.id()).steps().get(0).status());
            assertEquals(List.of("FAILED"),jdbc.queryForList("SELECT outcome FROM questionnaire_job_attempts WHERE job_id=?",String.class,privateStep));
            assertEquals(0,count("scorecard_entries")); assertEquals(0,count("notifications"));
        } finally {
            jdbc.execute("DROP TRIGGER "+trigger+" ON evaluation_reports"); jdbc.execute("DROP FUNCTION "+trigger+"()");
        }
    }

    @Test void actualFinalizeHttpCannotOverwriteAnExistingLegacyScorecardForTheSameProject() throws Exception {
        UUID oldParty=UUID.randomUUID(),oldSubmission=UUID.randomUUID(),oldReport=UUID.randomUUID(),card=UUID.randomUUID(),entry=UUID.randomUUID();
        jdbc.update("INSERT INTO parties(id,name,leader_id) VALUES (?,'Historical frozen party',?)",oldParty,student);
        jdbc.update("INSERT INTO submissions(id,party_id,project_id,status) VALUES (?,?,?,'EVALUATED')",oldSubmission,oldParty,project);
        jdbc.update("INSERT INTO evaluation_reports(id,submission_id,total_score,finalized) VALUES (?,?,77.3,true)",oldReport,oldSubmission);
        jdbc.update("INSERT INTO scorecards(id,student_id,average_score,total_projects) VALUES (?,?,77.3,1)",card,student);
        jdbc.update("INSERT INTO scorecard_entries(id,scorecard_id,project_id,evaluation_report_id,score) VALUES (?,?,?,?,77.3)",entry,card,project,oldReport);
        try {
            var run=grade();var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new EvaluationController(mock(EvaluationService.class),service))
                    .setControllerAdvice(new com.talentbridge.exception.GlobalExceptionHandler()).build();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/evaluations/{id}/finalize",run.reportId()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            assertEquals(oldReport,jdbc.queryForObject("SELECT evaluation_report_id FROM scorecard_entries WHERE id=?",UUID.class,entry));
            assertEquals(77.3,jdbc.queryForObject("SELECT score FROM scorecard_entries WHERE id=?",Double.class,entry));
            assertEquals(77.3,jdbc.queryForObject("SELECT total_score FROM evaluation_reports WHERE id=?",Double.class,oldReport));
            assertEquals(3,count("scorecard_entries"));
        } finally {
            jdbc.update("DELETE FROM scorecard_entries WHERE id=?",entry);jdbc.update("DELETE FROM evaluation_reports WHERE id=?",oldReport);
            jdbc.update("DELETE FROM submissions WHERE id=?",oldSubmission);jdbc.update("DELETE FROM parties WHERE id=?",oldParty);
        }
    }

    @Test void mixedLegacyReevaluationAndVersionTwoFinalizeSerializeBeforeScorecardLoadAndKeepCorrectRollup() throws Exception {
        UUID oldProject=UUID.randomUUID(),oldParty=UUID.randomUUID(),oldSubmission=UUID.randomUUID(),oldReport=UUID.randomUUID(),card=UUID.randomUUID();
        jdbc.update("UPDATE users SET github_username='legacy-login' WHERE id=?",student);
        jdbc.update("INSERT INTO projects(id,title,created_by_id) VALUES (?,'Separate legacy project',?)",oldProject,coordinator);
        jdbc.update("INSERT INTO parties(id,name,leader_id) VALUES (?,'Legacy concurrent party',?)",oldParty,student);
        jdbc.update("INSERT INTO party_members(party_id,user_id) VALUES (?,?)",oldParty,student);
        jdbc.update("INSERT INTO submissions(id,party_id,project_id,status,repo_url) VALUES (?,?,?,'EVALUATED','https://github.com/synthetic/legacy')",oldSubmission,oldParty,oldProject);
        jdbc.update("INSERT INTO evaluation_reports(id,submission_id,total_score,finalized) VALUES (?,?,60,false)",oldReport,oldSubmission);
        jdbc.update("INSERT INTO student_evaluation_scores(evaluation_report_id,student_id,individual_score) VALUES (?,?,40)",oldReport,student);
        jdbc.update("INSERT INTO scorecards(id,student_id,average_score,total_projects) VALUES (?,?,40,1)",card,student);
        jdbc.update("INSERT INTO scorecard_entries(scorecard_id,project_id,evaluation_report_id,score) VALUES (?,?,?,40)",card,oldProject,oldReport);
        when(legacyGithub.fetchContributorData(anyString(),any())).thenReturn(new GitHubService.ContributorData(List.of("Synthetic contributor"),Map.of("legacy-login",4)));
        var pool=Executors.newFixedThreadPool(2);
        try(var connection=source.getConnection()) {
            var run=grade();
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new EvaluationController(legacy,service))
                    .setControllerAdvice(new com.talentbridge.exception.GlobalExceptionHandler()).build();
            connection.setAutoCommit(false);
            try(var hold=connection.prepareStatement("SELECT id FROM scorecards WHERE id=? FOR UPDATE")){hold.setObject(1,card);hold.executeQuery().close();}
            var finalized=pool.submit(()->mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/evaluations/{id}/finalize",run.reportId())).andReturn().getResponse().getStatus());
            awaitLock(false);
            var refreshed=pool.submit(()->mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/evaluations/{id}/reevaluate",oldReport)).andReturn().getResponse().getStatus());
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);int blocked=0;
            while(blocked<2 && System.nanoTime()<end) blocked=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename=current_user AND wait_event_type='Lock'",Integer.class);
            assertEquals(2,blocked,"Both real HTTP transactions must reach the controlled lock schedule");
            var waits=jdbc.queryForList("SELECT query FROM pg_stat_activity WHERE usename=current_user AND wait_event_type='Lock'",String.class);
            connection.commit();assertEquals(200,refreshed.get(10,TimeUnit.SECONDS));assertEquals(200,finalized.get(10,TimeUnit.SECONDS));
            var actual=jdbc.queryForMap("SELECT count(*) AS projects,round(avg(score),1) AS average FROM scorecard_entries WHERE scorecard_id=?",card);
            assertEquals(((Number)actual.get("projects")).intValue(),jdbc.queryForObject("SELECT total_projects FROM scorecards WHERE id=?",Integer.class,card));
            assertEquals(((Number)actual.get("average")).doubleValue(),jdbc.queryForObject("SELECT average_score FROM scorecards WHERE id=?",Double.class,card));
            assertEquals(2,((Number)actual.get("projects")).intValue());assertEquals(69.3,((Number)actual.get("average")).doubleValue());
            assertTrue(waits.stream().anyMatch(query->query.toLowerCase(Locale.ROOT).contains("users")),"The second request must wait on the student before loading its scorecard");
            assertEquals(60,jdbc.queryForObject("SELECT total_score FROM evaluation_reports WHERE id=?",Double.class,oldReport));verifyNoInteractions(legacyAi);
        } finally {
            pool.shutdownNow();
            jdbc.update("DELETE FROM scorecard_entries WHERE project_id=?",oldProject);
            jdbc.update("DELETE FROM student_evaluation_scores WHERE evaluation_report_id=?",oldReport);jdbc.update("DELETE FROM evaluation_reports WHERE id=?",oldReport);
            jdbc.update("DELETE FROM submissions WHERE id=?",oldSubmission);jdbc.update("DELETE FROM party_members WHERE party_id=?",oldParty);
            jdbc.update("DELETE FROM parties WHERE id=?",oldParty);jdbc.update("DELETE FROM projects WHERE id=?",oldProject);
        }
    }

    @Test void triggerReadsExpiryOnlyAfterWaitingForAllAnswerLocks() throws Exception {
        var pool=Executors.newSingleThreadExecutor();clock.set(closes.minusSeconds(1));
        try(var connection=source.getConnection()) {
            connection.setAutoCommit(false);
            try(var lock=connection.prepareStatement("SELECT id FROM questionnaire_assignments WHERE snapshot_id=? ORDER BY id FOR UPDATE")){lock.setObject(1,snapshot);lock.executeQuery().close();}
            var waiting=pool.submit(()->service.start(submission,coordinator));awaitLock(false);
            clock.set(closes);connection.commit();assertEquals(2,waiting.get(5,TimeUnit.SECONDS).steps().size());
        } finally{pool.shutdownNow();}
    }

    @Test void finalizerWaitingBehindActualOverrideReadsTheCommittedNewTotalNotPreLockReport() throws Exception {
        var run=grade();var q=service.privateReview(submission).members().get(0).questions().get(0);
        String trigger="task4_report_"+UUID.randomUUID().toString().replace("-","");long key=Math.abs(new Random().nextInt())+1L;
        jdbc.execute("CREATE FUNCTION "+trigger+"() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id='"+run.reportId()+"'::uuid AND NEW.total_score IS DISTINCT FROM OLD.total_score THEN PERFORM pg_advisory_lock("+key+"); PERFORM pg_advisory_unlock("+key+"); END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON evaluation_reports FOR EACH ROW EXECUTE FUNCTION "+trigger+"()");
        var pool=Executors.newFixedThreadPool(2);
        try(var connection=source.getConnection()) {
            try(var hold=connection.prepareStatement("SELECT pg_advisory_lock(?)")){hold.setLong(1,key);hold.executeQuery().close();}
            var override=pool.submit(()->service.adjust(submission,q.id(),coordinator,20,"Serialized evidence correction"));awaitLock(true);
            var finalize=pool.submit(()->service.finalizeReport(run.reportId(),coordinator));awaitLock(false);
            try(var release=connection.prepareStatement("SELECT pg_advisory_unlock(?)")){release.setLong(1,key);release.executeQuery().close();}
            assertEquals(20,override.get(5,TimeUnit.SECONDS).members().get(0).questions().get(0).effectiveScore());
            assertEquals(81.8,finalize.get(5,TimeUnit.SECONDS).getTotalScore());
        } finally {pool.shutdownNow();jdbc.execute("DROP TRIGGER "+trigger+" ON evaluation_reports");jdbc.execute("DROP FUNCTION "+trigger+"()");}
    }

    void awaitLock(boolean advisory) {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);int blocked=0;
        while(blocked==0 && System.nanoTime()<end) blocked=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename=current_user AND wait_event_type='Lock' AND "+(advisory?"wait_event='advisory'":"wait_event<>'advisory'"),Integer.class);
        assertTrue(blocked>0,"Expected the real PostgreSQL lock wait");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"REPOSITORY,expired","REPOSITORY,callbackFailure","REPOSITORY,commitFailure","REPOSITORY,denied","REPOSITORY,success","REPOSITORY,providerFailure",
            "QUESTIONNAIRE,expired","QUESTIONNAIRE,callbackFailure","QUESTIONNAIRE,commitFailure","QUESTIONNAIRE,denied","QUESTIONNAIRE,success","QUESTIONNAIRE,providerFailure"})
    void atomicQuotaAndEvaluationAttemptThroughRealHttp(String kind,String mode) throws Exception {
        var run=service.start(submission,coordinator);
        if(kind.equals("QUESTIONNAIRE")) deliver(run.steps().get(0).jobId());
        UUID target=run.steps().get(kind.equals("REPOSITORY")?0:1).jobId();
        try(var probe=new QuestionnaireGenerationDatabaseIT.AtomicQuotaProbe(source,transactions,clock,clock::set,ai,mode);
            var http=new QuestionnaireGenerationDatabaseIT.WorkerHttp(generation,service)) {
            int status=http.post(target);
            System.out.println("ATOMIC_QUOTA evaluation kind="+kind+" mode="+mode+" http="+status+" used="+probe.used()+" attempts="+jdbc.queryForObject("SELECT attempts FROM questionnaire_jobs WHERE id=?",Integer.class,target)+" provider="+probe.calls.get());
            assertEquals(mode.equals("expired")?503:204,status);probe.assertAccounting(target);
            if(mode.equals("success")) assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM questionnaire_jobs WHERE id=?",String.class,target));
            assertEquals(0,count("scorecard_entries")); assertEquals(0,count("notifications"));
        }
    }

    void deliver(UUID id) { assertEquals(204,delivery.handle(id,new AssessmentJobController.JobReference(id)).getStatusCode().value()); }
    int count(String table) {
        return switch(table) {
            case "scorecard_entries"->jdbc.queryForObject("SELECT count(*) FROM scorecard_entries WHERE project_id=?",Integer.class,project);
            case "notifications"->jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id IN (?,?)",Integer.class,student,other);
            case "questionnaire_job_attempts"->jdbc.queryForObject("SELECT count(*) FROM questionnaire_job_attempts WHERE job_id IN (SELECT id FROM questionnaire_jobs WHERE snapshot_id=?)",Integer.class,snapshot);
            case "questionnaire_evaluation_runs"->jdbc.queryForObject("SELECT count(*) FROM questionnaire_evaluation_runs WHERE snapshot_id=?",Integer.class,snapshot);
            default->throw new IllegalArgumentException("Unsupported fixture count");
        };
    }
    AssessmentAIService.RepositoryEvaluation repository(double score) {
        var categories=new LinkedHashMap<String,AssessmentAIService.CategoryScore>();
        for(String key:List.of("authenticity","quality","functionality","scope","collaboration")) categories.put(key,new AssessmentAIService.CategoryScore(score,"Bounded repository-only evidence"));
        return new AssessmentAIService.RepositoryEvaluation(categories);
    }
    AssessmentAIService.QuestionnaireEvaluation marks(AssessmentAIService.PreparedQuestionnaireEvaluation prepared,int normal,int last) {
        var members=new ArrayList<AssessmentAIService.MemberMarks>();
        for(var member:preparedMembers.get(prepared)) {
            var marks=new ArrayList<AssessmentAIService.QuestionMark>();
            for(var answer:member.questions()) marks.add(new AssessmentAIService.QuestionMark(answer.id(),answer.prompt().endsWith(" 5")?last:normal,"Private grading feedback"));
            members.add(new AssessmentAIService.MemberMarks(member.memberId(),marks));
        }
        return new AssessmentAIService.QuestionnaireEvaluation(members);
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> value=new AtomicReference<>(); void set(Instant time){value.set(time);}
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return value.get();}
    }
    @Configuration @Import({QuestionnaireEvaluationService.class,AssessmentJobController.class,QuestionnaireService.class,ScorecardService.class,EvaluationService.class})
    static class Config {
        @Bean static QuestionnaireGenerationDatabaseIT.ProfileFetchProbe sqlProbe(){return new QuestionnaireGenerationDatabaseIT.ProfileFetchProbe();}
        @Bean MutableClock clock(){return new MutableClock();}
        @Bean QuestionnaireGenerationDatabaseIT.PreparationMapper mapper(){return new QuestionnaireGenerationDatabaseIT.PreparationMapper();}
        @Bean AssessmentAIService ai(){return mock(AssessmentAIService.class);}
        @Bean AssessmentTaskQueue queue(){return mock(AssessmentTaskQueue.class);}
        @Bean QuestionnaireGenerationService generation(){return mock(QuestionnaireGenerationService.class);}
        @Bean GitHubService legacyGithub(){return mock(GitHubService.class);}
        @Bean OpenAIService legacyAi(){return mock(OpenAIService.class);}
        @Bean JdbcTemplate jdbc(DataSource source){return new JdbcTemplate(source);}
    }
}
