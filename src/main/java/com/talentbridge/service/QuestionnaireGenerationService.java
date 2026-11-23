package com.talentbridge.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.IntStream;

@Service @RequiredArgsConstructor @Slf4j
public class QuestionnaireGenerationService {
    private static final int TRANSACTION_TIMEOUT_SECONDS = 2;
    private static final int RETRY_TRANSACTION_TIMEOUT_SECONDS = 5;
    private static final int RESULT_TRANSACTION_TIMEOUT_SECONDS = 5;
    private static final Set<String> SAFE_GENERATION_CATEGORIES=Set.of("INPUT_TOO_LARGE","INSUFFICIENT_EVIDENCE",
            "INVALID_INPUT","INVALID_RELAY_CONFIGURATION","QUOTA_CLOCK_REGRESSION","QUOTA_UNAVAILABLE",
            "RELAY_HTTP_FAILURE","RELAY_INTERRUPTED","RELAY_FAILURE","INVALID_RESPONSE");
    private static final String JAVA_WHITESPACE = IntStream.rangeClosed(0,Character.MAX_CODE_POINT)
            .filter(Character::isWhitespace).collect(StringBuilder::new,StringBuilder::appendCodePoint,StringBuilder::append).toString();
    private final QuestionnaireSnapshotRepository snapshots;
    private final QuestionnaireAssignmentRepository assignments;
    private final QuestionnaireEvidenceRepository evidence;
    private final QuestionnaireJobRepository jobs;
    private final UserRepository users;
    private final GitHubEvidenceService github;
    private final AssessmentAIService ai;
    private final AssessmentTaskQueue queue;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final Clock clock;

    public void dispatch(UUID id) {
        dispatch(id,TRANSACTION_TIMEOUT_SECONDS);
    }

    private void dispatch(UUID id,int timeoutSeconds) {
        nontransactional();
        Dispatch plan=transaction(timeoutSeconds, () -> {
            QuestionnaireJob job=lockedJob(id);
            if (terminal(job) || job.getStatus()==QuestionnaireJobStatus.RUNNING) return null;
            return new Dispatch(job.getDispatchGeneration(),job.getNextAttemptAt()==null?clock.instant():job.getNextAttemptAt());
        });
        if (plan==null) return;
        try { queue.enqueue(id,plan.generation(),plan.due()); }
        catch (AssessmentTaskQueue.DispatchException failure) {
            transaction(timeoutSeconds, () -> {
                QuestionnaireJob job=lockedJob(id);
                if (!terminal(job) && job.getDispatchGeneration()==plan.generation()) job.setLastErrorCode(failure.getMessage());
                return null;
            });
            throw failure;
        }
        transaction(timeoutSeconds, () -> {
            QuestionnaireJob job=lockedJob(id);
            if (!terminal(job) && job.getDispatchGeneration()==plan.generation()
                    && job.getStatus()!=QuestionnaireJobStatus.RUNNING) {
                if (job.getStatus()!=QuestionnaireJobStatus.DEFERRED) job.setStatus(QuestionnaireJobStatus.DISPATCHED);
                if (job.getLastErrorCode()!=null && job.getLastErrorCode().startsWith("DISPATCH_")) job.setLastErrorCode(null);
            }
            return null;
        });
    }

    public void handle(UUID id) {
        nontransactional();
        UUID snapshotId=transaction(() -> jobs.snapshotId(id).orElseThrow(QuestionnaireGenerationService::notFound));
        Work work=transaction(() -> claim(id));
        if (work!=null) {
            Inputs inputs=null;
            try { inputs=prepare(work); }
            catch (RuntimeException failure) {
                transaction(() -> { failed(leased(work),"FROZEN_INPUT_UNAVAILABLE"); return null; });
            }
            if (inputs!=null) {
                GenerationPhase phase=GenerationPhase.PREPARE;
                try {
                    if (work.kind==QuestionnaireJobKind.EVIDENCE) {
                        transaction(() -> { leased(work); return null; });
                        GitHubEvidence captured=github.capture(inputs.resolution,work.brief,inputs.token);
                        var resolution=inputs.resolution;
                        transaction(RESULT_TRANSACTION_TIMEOUT_SECONDS,() -> { finishEvidence(work,resolution,captured); return null; });
                    } else {
                        var prepared=ai.prepareGeneration(work.brief,inputs.evidence,work.studentId,work.questionIds,inputs.prior);
                        transaction(() -> { leased(work); return null; });
                        phase=GenerationPhase.MODEL;
                        var result=ai.generate(prepared,() -> transaction(() -> { startAttempt(work); return null; }));
                        phase=GenerationPhase.RESULT_WRITE;
                        transaction(RESULT_TRANSACTION_TIMEOUT_SECONDS,() -> { finishGeneration(work,result); return null; });
                    }
                } catch (AssessmentQuotaService.QuotaExceeded quota) {
                    transaction(() -> { defer(work,quota.resetAt()); return null; });
                } catch (RuntimeException failure) {
                    if (work.kind==QuestionnaireJobKind.GENERATE_MEMBER) {
                        String code=failure instanceof AssessmentAIService.AssessmentException?failure.getMessage():null;
                        // Never attach a throwable, payload or identifier to this diagnostic.
                        log.warn("Questionnaire generation failed phase={} category={}",phase,
                                code!=null && SAFE_GENERATION_CATEGORIES.contains(code)?code:"UNCLASSIFIED");
                    }
                    transaction(() -> { fail(work,work.kind==QuestionnaireJobKind.EVIDENCE?"EVIDENCE_UNAVAILABLE":"GENERATION_UNAVAILABLE"); return null; });
                }
            }
        }
        // Exactly one next job exists per frozen team, bounding post-evidence enqueue time.
        UUID next=transaction(() -> jobs.findAllBySnapshotIdOrderByCreatedAtAsc(snapshotId).stream()
                .filter(j -> !terminal(j) && j.getStatus()!=QuestionnaireJobStatus.RUNNING).map(QuestionnaireJob::getId).findFirst().orElse(null));
        if (next!=null) {
            try { dispatch(next); }
            catch (AssessmentTaskQueue.DispatchException failure) { throw busy(); }
        }
    }

    private Work claim(UUID id) {
        QuestionnaireJob job=lockedJob(id); QuestionnaireSnapshot snapshot=job.getSnapshot(); Instant now=clock.instant();
        if (terminal(job)) return null;
        if (job.getLeaseUntil()!=null && now.isBefore(job.getLeaseUntil())) throw busy();
        if (snapshot.getOpensAt()!=null) { succeeded(job); return null; }
        if (job.getNextAttemptAt()!=null && now.isBefore(job.getNextAttemptAt())) {
            // Forced early delivery must not delete the sole future task with the same retained name.
            job.setDispatchGeneration(job.getDispatchGeneration()+1); return null;
        }
        if (job.getKind()==QuestionnaireJobKind.EVALUATE) throw new ResponseStatusException(HttpStatus.CONFLICT,"Evaluation worker is not available");
        for (QuestionnaireJob sibling : jobs.findAllBySnapshotIdOrderByCreatedAtAsc(snapshot.getId()))
            if (!sibling.getId().equals(id) && sibling.getLeaseUntil()!=null && now.isBefore(sibling.getLeaseUntil())) throw busy();
        jdbc.update("UPDATE questionnaire_job_attempts SET outcome='UNKNOWN',finished_at=?,error_code='LEASE_EXPIRED' WHERE job_id=? AND outcome='STARTED'",java.sql.Timestamp.from(now),id);
        clearLease(job);
        if (job.getAttempts()>=3) { failed(job,"ATTEMPT_LIMIT"); return null; }
        job.setStatus(QuestionnaireJobStatus.RUNNING); job.setLeaseToken(UUID.randomUUID()); job.setLeaseUntil(now.plusSeconds(110));
        if (job.getKind()==QuestionnaireJobKind.GENERATE_MEMBER && job.getQuestionIds().isEmpty())
            job.setQuestionIds(java.util.stream.IntStream.range(0,5).mapToObj(i -> UUID.randomUUID()).toList());
        return new Work(id,snapshot.getId(),job.getLeaseToken(),job.getAttempts(),job.getKind(),snapshot.getFrozenBrief(),
                job.getAssignment()==null?null:job.getAssignment().getStudentId(),List.copyOf(job.getQuestionIds()),
                snapshot.getFrozenResolution(),snapshot.getFrozenLeaderId());
    }

    private Inputs prepare(Work work) {
        try {
            if (work.kind==QuestionnaireJobKind.EVIDENCE) {
                var resolution=json.treeToValue(work.frozenResolution,GitHubEvidence.Resolution.class);
                String token=transaction(() -> users.findGithubAccessTokenById(work.leaderId).orElse(null));
                return new Inputs(resolution,token,null,List.of());
            } else {
                var frozen=transaction(() -> evidence.findBySnapshotId(work.snapshotId).orElseThrow().getEvidence());
                var captured=json.treeToValue(frozen,GitHubEvidence.class);
                var prior=transaction(() -> jdbc.queryForList("SELECT q.prompt FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id=? ORDER BY q.id",String.class,work.snapshotId));
                return new Inputs(null,null,captured,List.copyOf(prior));
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("FROZEN_INPUT_UNAVAILABLE"); }
    }

    private void startAttempt(Work work) {
        QuestionnaireJob job=leased(work);
        if (job.getAttempts()>=3) throw new IllegalStateException("ATTEMPT_LIMIT");
        job.setAttempts(job.getAttempts()+1);
        // A crash after this commit is conservatively UNKNOWN, never refunded or silently retried for free.
        jdbc.update("INSERT INTO questionnaire_job_attempts(job_id,attempt,started_at,outcome) VALUES (?,?,?,'STARTED')",job.getId(),job.getAttempts(),java.sql.Timestamp.from(clock.instant()));
    }

    private void finishEvidence(Work work,GitHubEvidence.Resolution resolution,GitHubEvidence captured) {
        QuestionnaireJob job=leased(work); QuestionnaireSnapshot snapshot=job.getSnapshot();
        if (captured==null || !resolution.equals(captured.resolution())) throw new IllegalStateException("INVALID_EVIDENCE");
        if (!evidence.existsBySnapshotId(snapshot.getId())) evidence.save(QuestionnaireEvidence.builder()
                .snapshot(snapshot).repositoryUrl(captured.resolution().repository().url())
                .repositoryBranch(captured.resolution().resolvedBranch()).commitSha(captured.resolution().commitSha())
                .capturedAt(captured.capturedAt()).evidence(json.valueToTree(captured)).build());
        succeeded(job); nextMemberOrPublish(snapshot);
    }

    private void finishGeneration(Work work,AssessmentAIService.GeneratedQuestions result) {
        QuestionnaireJob job=leased(work); UUID assignmentId=job.getAssignment().getId();
        if (jdbc.queryForObject("SELECT count(*) FROM questionnaire_questions WHERE assignment_id=?",Integer.class,assignmentId)==0) {
            if (result==null || !work.studentId.equals(result.memberId()) || result.questions().size()!=5
                    || !new HashSet<>(work.questionIds).equals(result.questions().stream().map(AssessmentAIService.Question::id).collect(java.util.stream.Collectors.toSet())))
                throw new IllegalStateException("INVALID_GENERATION_RESULT");
            int position=0;
            List<Object[]> rows=new ArrayList<>();
            for (var q : result.questions()) {
                try {
                    rows.add(new Object[]{q.id(),assignmentId,++position,q.prompt(),q.rubric(),json.writeValueAsString(q.sourceReferences())});
                } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("INVALID_GENERATION_RESULT"); }
            }
            jdbc.batchUpdate("INSERT INTO questionnaire_questions(id,assignment_id,position,prompt,hidden_rubric,source_references) VALUES (?,?,?,?,?,?::jsonb)",rows);
        }
        finishAttempt(job,"SUCCEEDED",null); succeeded(job); nextMemberOrPublish(job.getSnapshot());
    }

    private void nextMemberOrPublish(QuestionnaireSnapshot snapshot) {
        var roster=jdbc.query("""
                SELECT a.id, count(q.id) AS question_count
                FROM questionnaire_assignments a LEFT JOIN questionnaire_questions q ON q.assignment_id=a.id
                WHERE a.snapshot_id=? GROUP BY a.id
                """,(rs,row)->new MemberProgress(rs.getObject("id",UUID.class),
                rs.getInt("question_count")),snapshot.getId()).stream().sorted(Comparator.comparing(MemberProgress::id)).toList();
        if (roster.isEmpty()) throw new IllegalStateException("FROZEN_ROSTER_UNAVAILABLE");
        for (var member : roster) {
            int count=member.questionCount();
            if (count!=0 && count!=5) throw new IllegalStateException("PARTIAL_QUESTION_SET");
            if (count==0) {
                boolean pending=jobs.findAllBySnapshotIdOrderByCreatedAtAsc(snapshot.getId()).stream()
                        .anyMatch(j -> !terminal(j) && j.getAssignment()!=null && j.getAssignment().getId().equals(member.id()));
                if (!pending) jobs.save(QuestionnaireJob.builder().snapshot(snapshot).assignment(assignments.getReferenceById(member.id()))
                        .kind(QuestionnaireJobKind.GENERATE_MEMBER).jobKey(snapshot.getId()+":member:"+member.id())
                        .nextAttemptAt(clock.instant()).build());
                return;
            }
        }
        if (snapshot.getOpensAt()!=null) return;
        Instant opens=clock.instant(); snapshot.setOpensAt(opens); snapshot.setClosesAt(opens.plus(Duration.ofHours(48)));
        snapshot.setState(QuestionnaireState.OPEN);
        // Publication and in-app notifications commit together; no synchronous email/provider call.
        jdbc.update("""
                INSERT INTO notifications(recipient_id,type,title,message,reference_id,reference_type)
                SELECT student_id,?,?,?,CAST(id AS text),'QUESTIONNAIRE'
                FROM questionnaire_assignments WHERE snapshot_id=?
                """,NotificationType.GENERAL.name(),"Project questionnaire ready",
                "Your project questionnaire is open for 48 hours.",snapshot.getId());
    }

    private void defer(Work work,Instant reset) {
        QuestionnaireJob job=leased(work); clearLease(job); job.setStatus(QuestionnaireJobStatus.DEFERRED);
        job.setLastErrorCode("ASSESSMENT_DAILY_LIMIT"); job.setNextAttemptAt(reset);
        job.setDispatchGeneration(job.getDispatchGeneration()+1);
    }
    private void fail(Work work,String code) {
        QuestionnaireJob job=leased(work);
        finishAttempt(job,"FAILED",code); clearLease(job); job.setLastErrorCode(code);
        if (work.kind==QuestionnaireJobKind.EVIDENCE || job.getAttempts()<=work.attemptsAtClaim || job.getAttempts()>=3) failed(job,code);
        else { job.setStatus(QuestionnaireJobStatus.PENDING); job.setNextAttemptAt(clock.instant().plusSeconds(30));
            job.setDispatchGeneration(job.getDispatchGeneration()+1); }
    }
    private void failed(QuestionnaireJob job,String code) {
        clearLease(job); job.setStatus(QuestionnaireJobStatus.FAILED); job.setLastErrorCode(code);
        job.getSnapshot().setState(QuestionnaireState.FAILED);
    }
    private void succeeded(QuestionnaireJob job) {
        clearLease(job); job.setStatus(QuestionnaireJobStatus.SUCCEEDED); job.setLastErrorCode(null); job.setNextAttemptAt(null);
    }
    private void finishAttempt(QuestionnaireJob job,String outcome,String code) {
        jdbc.update("UPDATE questionnaire_job_attempts SET outcome=?,finished_at=?,error_code=? WHERE job_id=? AND outcome='STARTED'",outcome,java.sql.Timestamp.from(clock.instant()),code,job.getId());
    }
    private void clearLease(QuestionnaireJob job) { job.setLeaseToken(null); job.setLeaseUntil(null); }
    private boolean terminal(QuestionnaireJob job) { return job.getStatus()==QuestionnaireJobStatus.SUCCEEDED || job.getStatus()==QuestionnaireJobStatus.FAILED; }
    private QuestionnaireJob lockedJob(UUID id) {
        UUID snapshot=jobs.snapshotId(id).orElseThrow(QuestionnaireGenerationService::notFound);
        lockSnapshot(snapshot);
        return jobs.findById(id).orElseThrow(QuestionnaireGenerationService::notFound);
    }
    private void lockSnapshot(UUID snapshot) {
        // Lock before Hibernate can hydrate stale state; the job's snapshot ID is immutable.
        jdbc.queryForObject("SELECT id FROM questionnaire_snapshots WHERE id=? FOR UPDATE",UUID.class,snapshot);
    }
    private QuestionnaireJob leased(Work work) {
        lockSnapshot(work.snapshotId);
        QuestionnaireJob job=jobs.findWithSnapshotById(work.id).orElseThrow(QuestionnaireGenerationService::notFound);
        if (!work.lease.equals(job.getLeaseToken()) || job.getStatus()!=QuestionnaireJobStatus.RUNNING
                || !clock.instant().isBefore(job.getLeaseUntil())) throw busy();
        return job;
    }

    public Review retry(UUID submissionId) {
        nontransactional();
        UUID next=transaction(RETRY_TRANSACTION_TIMEOUT_SECONDS, () -> {
            // Lock the V2 snapshot before Hibernate can hydrate stale state.
            UUID id=jdbc.query("SELECT id FROM questionnaire_snapshots WHERE submission_id=? AND assessment_version=2 FOR UPDATE",
                    rs -> { if (!rs.next()) throw notFound(); return rs.getObject(1,UUID.class); },submissionId);
            var snapshot=snapshots.findById(id).orElseThrow(QuestionnaireGenerationService::notFound);
            if (snapshot.getOpensAt()!=null) return null;
            var history=jobs.findAllBySnapshotIdOrderByCreatedAtAsc(id);
            for (var job : history) if (job.getLeaseUntil()!=null && clock.instant().isBefore(job.getLeaseUntil()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Generation is already running");
            var active=history.stream().filter(j -> !terminal(j)).findFirst().orElse(null);
            snapshot.setState(QuestionnaireState.GENERATING);
            if (active!=null) {
                if (active.getAttempts()>0) finishAttempt(active,"UNKNOWN","LEASE_EXPIRED");
                clearLease(active);
                if (active.getAttempts()>=3) { failed(active,"ATTEMPT_LIMIT"); active=null; }
            }
            if (active==null) {
                var previous=history.stream().filter(j -> j.getStatus()==QuestionnaireJobStatus.FAILED).reduce((a,b)->b).orElseThrow(QuestionnaireGenerationService::notFound);
                active=jobs.save(QuestionnaireJob.builder().snapshot(snapshot).kind(previous.getKind()).assignment(previous.getAssignment())
                        .jobKey(id+":recovery:"+UUID.randomUUID()).retryOf(previous.getId()).questionIds(List.copyOf(previous.getQuestionIds())).nextAttemptAt(clock.instant()).build());
                snapshot.setState(QuestionnaireState.GENERATING);
            } else {
                active.setDispatchGeneration(active.getDispatchGeneration()+1);
                if (active.getStatus()!=QuestionnaireJobStatus.DEFERRED) { active.setStatus(QuestionnaireJobStatus.PENDING); active.setNextAttemptAt(clock.instant()); }
            }
            return active.getId();
        });
        if (next!=null) try { dispatch(next,RETRY_TRANSACTION_TIMEOUT_SECONDS); }
        catch (AssessmentTaskQueue.DispatchException failure) { /* Review exposes recoverable dispatch state. */ }
        return review(submissionId);
    }

    public Review review(UUID submissionId) {
        return transaction(() -> jdbc.query("""
                SELECT s.frozen_label, s.state, s.opens_at, s.closes_at,
                    (SELECT coalesce(jsonb_agg(jsonb_build_object(
                        'assignmentId', a.id, 'studentId', a.student_id, 'studentName', a.student_name,
                        'questionCount', (SELECT count(*) FROM questionnaire_questions q WHERE q.assignment_id = a.id),
                        'answeredCount', (SELECT count(*) FROM jsonb_each_text(a.answers) answer
                                          WHERE length(translate(answer.value, ?, '')) > 0)
                    )), '[]') FROM questionnaire_assignments a WHERE a.snapshot_id = s.id) AS members,
                    (SELECT coalesce(jsonb_agg(jsonb_build_object(
                        'id', j.id, 'retryOf', j.retry_of, 'kind', j.kind, 'status', j.status,
                        'attempts', j.attempts, 'dispatchGeneration', j.dispatch_generation,
                        'nextAttemptAt', j.next_attempt_at, 'leaseUntil', j.lease_until, 'errorCode', j.last_error_code
                    ) ORDER BY j.created_at), '[]') FROM questionnaire_jobs j WHERE j.snapshot_id = s.id) AS jobs
                FROM questionnaire_snapshots s
                WHERE s.submission_id = ? AND s.assessment_version = 2
                """, rs -> {
            if (!rs.next()) throw notFound();
            var opensAt=rs.getObject("opens_at",OffsetDateTime.class);
            var closesAt=rs.getObject("closes_at",OffsetDateTime.class);
            try {
                List<Member> members=json.readValue(rs.getString("members"),new TypeReference<>() {});
                List<Job> history=json.readValue(rs.getString("jobs"),new TypeReference<>() {});
                return new Review(submissionId,rs.getString("frozen_label"),QuestionnaireState.valueOf(rs.getString("state")),
                        opensAt==null?null:opensAt.toInstant(),closesAt==null?null:closesAt.toInstant(),clock.instant(),members,history);
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException("Could not read questionnaire review metadata",failure);
            }
        },JAVA_WHITESPACE,submissionId));
    }
    public record Review(UUID submissionId,String projectTitle,QuestionnaireState state,Instant opensAt,Instant closesAt,Instant serverNow,List<Member> members,List<Job> jobs) {}
    public record Member(UUID assignmentId,UUID studentId,String studentName,int questionCount,int answeredCount) {}
    public record Job(UUID id,UUID retryOf,QuestionnaireJobKind kind,QuestionnaireJobStatus status,int attempts,int dispatchGeneration,Instant nextAttemptAt,Instant leaseUntil,String errorCode) {}
    private record Dispatch(int generation,Instant due) {}
    private enum GenerationPhase { PREPARE, MODEL, RESULT_WRITE }
    private record MemberProgress(UUID id,int questionCount) {}
    private <T> T transaction(Supplier<T> operation) {
        return transaction(TRANSACTION_TIMEOUT_SECONDS,operation);
    }
    private <T> T transaction(int timeoutSeconds,Supplier<T> operation) {
        var tx=new TransactionTemplate(manager); tx.setTimeout(timeoutSeconds); return tx.execute(status -> operation.get());
    }
    private static ResponseStatusException busy() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Generation is busy; retry delivery"); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Questionnaire job not found"); }
    private static void nontransactional() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Generation requires a nontransactional caller");
    }
    private record Work(UUID id,UUID snapshotId,UUID lease,int attemptsAtClaim,QuestionnaireJobKind kind,String brief,
                        UUID studentId,List<UUID> questionIds,com.fasterxml.jackson.databind.JsonNode frozenResolution,UUID leaderId) {}
    private record Inputs(GitHubEvidence.Resolution resolution,String token,GitHubEvidence evidence,List<String> prior) {}
}
