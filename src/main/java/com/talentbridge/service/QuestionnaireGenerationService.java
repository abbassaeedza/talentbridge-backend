package com.talentbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

@Service @RequiredArgsConstructor
public class QuestionnaireGenerationService {
    private final QuestionnaireSnapshotRepository snapshots;
    private final QuestionnaireAssignmentRepository assignments;
    private final QuestionnaireEvidenceRepository evidence;
    private final QuestionnaireJobRepository jobs;
    private final UserRepository users;
    private final NotificationRepository notifications;
    private final GitHubEvidenceService github;
    private final AssessmentAIService ai;
    private final AssessmentTaskQueue queue;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final Clock clock;

    public void dispatch(UUID id) {
        nontransactional();
        Dispatch plan=transaction(() -> {
            QuestionnaireJob job=lockedJob(id);
            if (terminal(job) || job.getStatus()==QuestionnaireJobStatus.RUNNING) return null;
            return new Dispatch(job.getDispatchGeneration(),job.getNextAttemptAt()==null?clock.instant():job.getNextAttemptAt());
        });
        if (plan==null) return;
        try { queue.enqueue(id,plan.generation(),plan.due()); }
        catch (AssessmentTaskQueue.DispatchException failure) {
            transaction(() -> {
                QuestionnaireJob job=lockedJob(id);
                if (!terminal(job) && job.getDispatchGeneration()==plan.generation()) job.setLastErrorCode(failure.getMessage());
                return null;
            });
            throw failure;
        }
        transaction(() -> {
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
            try {
                if (work.kind==QuestionnaireJobKind.EVIDENCE) {
                    GitHubEvidence captured=github.capture(work.resolution,work.brief,work.token);
                    transaction(() -> { finishEvidence(work,captured); return null; });
                } else {
                    var prepared=ai.prepareGeneration(work.brief,work.evidence,work.studentId,work.questionIds,work.prior);
                    var result=ai.generate(prepared,() -> transaction(() -> { startAttempt(work); return null; }));
                    transaction(() -> { finishGeneration(work,result); return null; });
                }
            } catch (AssessmentQuotaService.QuotaExceeded quota) {
                transaction(() -> { defer(work,quota.resetAt()); return null; });
            } catch (RuntimeException failure) {
                transaction(() -> { fail(work,work.kind==QuestionnaireJobKind.EVIDENCE?"EVIDENCE_UNAVAILABLE":"GENERATION_UNAVAILABLE"); return null; });
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
        Work work=new Work(); work.id=id; work.snapshotId=snapshot.getId(); work.lease=job.getLeaseToken();
        work.attemptsAtClaim=job.getAttempts();
        work.kind=job.getKind(); work.brief=snapshot.getFrozenBrief(); work.questionIds=List.copyOf(job.getQuestionIds());
        try {
            if (work.kind==QuestionnaireJobKind.EVIDENCE) {
                work.resolution=json.treeToValue(snapshot.getFrozenResolution(),GitHubEvidence.Resolution.class);
                work.token=users.findGithubAccessTokenById(snapshot.getFrozenLeaderId()).orElse(null);
            } else {
                work.studentId=job.getAssignment().getStudentId();
                work.evidence=json.treeToValue(evidence.findBySnapshotId(snapshot.getId()).orElseThrow().getEvidence(),GitHubEvidence.class);
                work.prior=jdbc.queryForList("SELECT q.prompt FROM questionnaire_questions q JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id=? ORDER BY q.id",String.class,snapshot.getId());
            }
        } catch (Exception failure) { failed(job,"FROZEN_INPUT_UNAVAILABLE"); return null; }
        return work;
    }

    private void startAttempt(Work work) {
        QuestionnaireJob job=leased(work);
        if (job.getAttempts()>=3) throw new IllegalStateException("ATTEMPT_LIMIT");
        job.setAttempts(job.getAttempts()+1);
        // A crash after this commit is conservatively UNKNOWN, never refunded or silently retried for free.
        jdbc.update("INSERT INTO questionnaire_job_attempts(job_id,attempt,started_at,outcome) VALUES (?,?,?,'STARTED')",job.getId(),job.getAttempts(),java.sql.Timestamp.from(clock.instant()));
    }

    private void finishEvidence(Work work,GitHubEvidence captured) {
        QuestionnaireJob job=leased(work); QuestionnaireSnapshot snapshot=job.getSnapshot();
        if (captured==null || !work.resolution.equals(captured.resolution())) throw new IllegalStateException("INVALID_EVIDENCE");
        if (evidence.findBySnapshotId(snapshot.getId()).isEmpty()) evidence.save(QuestionnaireEvidence.builder()
                .snapshot(snapshot).repositoryUrl(captured.resolution().repository().url())
                .repositoryBranch(captured.resolution().resolvedBranch()).commitSha(captured.resolution().commitSha())
                .capturedAt(captured.capturedAt()).evidence(json.valueToTree(captured)).build());
        succeeded(job); nextMemberOrPublish(snapshot);
    }

    private void finishGeneration(Work work,AssessmentAIService.GeneratedQuestions result) {
        QuestionnaireJob job=leased(work); QuestionnaireAssignment assignment=job.getAssignment();
        if (questionCount(assignment.getId())==0) {
            if (result==null || !work.studentId.equals(result.memberId()) || result.questions().size()!=5
                    || !new HashSet<>(work.questionIds).equals(result.questions().stream().map(AssessmentAIService.Question::id).collect(java.util.stream.Collectors.toSet())))
                throw new IllegalStateException("INVALID_GENERATION_RESULT");
            int position=0;
            for (var q : result.questions()) {
                try {
                    jdbc.update("INSERT INTO questionnaire_questions(id,assignment_id,position,prompt,hidden_rubric,source_references) VALUES (?,?,?,?,?,?::jsonb)",
                            q.id(),assignment.getId(),++position,q.prompt(),q.rubric(),json.writeValueAsString(q.sourceReferences()));
                } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("INVALID_GENERATION_RESULT"); }
            }
        }
        finishAttempt(job,"SUCCEEDED",null); succeeded(job); nextMemberOrPublish(job.getSnapshot());
    }

    private void nextMemberOrPublish(QuestionnaireSnapshot snapshot) {
        var roster=assignments.findAllBySnapshotId(snapshot.getId()).stream().sorted(Comparator.comparing(QuestionnaireAssignment::getId)).toList();
        if (roster.isEmpty()) throw new IllegalStateException("FROZEN_ROSTER_UNAVAILABLE");
        for (QuestionnaireAssignment member : roster) {
            int count=questionCount(member.getId());
            if (count!=0 && count!=5) throw new IllegalStateException("PARTIAL_QUESTION_SET");
            if (count==0) {
                boolean pending=jobs.findAllBySnapshotIdOrderByCreatedAtAsc(snapshot.getId()).stream()
                        .anyMatch(j -> !terminal(j) && j.getAssignment()!=null && j.getAssignment().getId().equals(member.getId()));
                if (!pending) jobs.save(QuestionnaireJob.builder().snapshot(snapshot).assignment(member)
                        .kind(QuestionnaireJobKind.GENERATE_MEMBER).jobKey(snapshot.getId()+":member:"+member.getId())
                        .nextAttemptAt(clock.instant()).build());
                return;
            }
        }
        if (snapshot.getOpensAt()!=null) return;
        Instant opens=clock.instant(); snapshot.setOpensAt(opens); snapshot.setClosesAt(opens.plus(Duration.ofHours(48)));
        snapshot.setState(QuestionnaireState.OPEN);
        for (QuestionnaireAssignment member : roster) {
            // Publication and in-app notifications commit together; no synchronous email/provider call.
            notifications.save(Notification.builder().recipient(users.getReferenceById(member.getStudentId()))
                    .type(NotificationType.GENERAL).title("Project questionnaire ready")
                    .message("Your project questionnaire is open for 48 hours.")
                    .referenceId(member.getId().toString()).referenceType("QUESTIONNAIRE").build());
        }
    }

    private void defer(Work work,Instant reset) {
        QuestionnaireJob job=leased(work); clearLease(job); job.setStatus(QuestionnaireJobStatus.DEFERRED);
        job.setLastErrorCode("ASSESSMENT_DAILY_LIMIT"); job.setNextAttemptAt(reset);
        job.setDispatchGeneration(job.getDispatchGeneration()+1);
    }
    private void fail(Work work,String code) {
        QuestionnaireJob job=lockedJob(work.id);
        if (!work.lease.equals(job.getLeaseToken())) throw busy();
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
    private int questionCount(UUID assignment) { return jdbc.queryForObject("SELECT count(*) FROM questionnaire_questions WHERE assignment_id=?",Integer.class,assignment); }
    private QuestionnaireJob lockedJob(UUID id) {
        UUID snapshot=jobs.snapshotId(id).orElseThrow(QuestionnaireGenerationService::notFound);
        lockedSnapshot(snapshot);
        return jobs.findById(id).orElseThrow(QuestionnaireGenerationService::notFound);
    }
    private QuestionnaireJob leased(Work work) {
        QuestionnaireJob job=lockedJob(work.id);
        if (!work.lease.equals(job.getLeaseToken()) || job.getStatus()!=QuestionnaireJobStatus.RUNNING
                || !clock.instant().isBefore(job.getLeaseUntil())) throw busy();
        return job;
    }

    private QuestionnaireSnapshot lockedSnapshot(UUID id) {
        // Lock before loading any entity; Hibernate follow-on locks can otherwise retain a stale pre-lock snapshot.
        jdbc.queryForObject("SELECT id FROM questionnaire_snapshots WHERE id=? FOR UPDATE",UUID.class,id);
        return snapshots.findById(id).orElseThrow(QuestionnaireGenerationService::notFound);
    }

    public Review retry(UUID submissionId) {
        nontransactional();
        UUID next=transaction(() -> {
            UUID id=snapshots.idBySubmission(submissionId).orElseThrow(QuestionnaireGenerationService::notFound);
            var snapshot=lockedSnapshot(id);
            if (snapshot.getOpensAt()!=null) return null;
            var history=jobs.findAllBySnapshotIdOrderByCreatedAtAsc(id);
            for (var job : history) if (job.getLeaseUntil()!=null && clock.instant().isBefore(job.getLeaseUntil()))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Generation is already running");
            var active=history.stream().filter(j -> !terminal(j)).findFirst().orElse(null);
            snapshot.setState(QuestionnaireState.GENERATING);
            if (active!=null) {
                finishAttempt(active,"UNKNOWN","LEASE_EXPIRED"); clearLease(active);
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
        if (next!=null) try { dispatch(next); } catch (AssessmentTaskQueue.DispatchException failure) { /* Review exposes recoverable dispatch state. */ }
        return review(submissionId);
    }

    public Review review(UUID submissionId) {
        return transaction(() -> {
            var snapshot=snapshots.findBySubmissionIdAndAssessmentVersion(submissionId,2).orElseThrow(QuestionnaireGenerationService::notFound);
            var members=assignments.findAllBySnapshotId(snapshot.getId()).stream().map(a -> new Member(a.getId(),a.getStudentId(),a.getStudentName(),questionCount(a.getId()),
                    (int)a.getAnswers().values().stream().filter(s -> !s.isBlank()).count())).toList();
            var history=jobs.findAllBySnapshotIdOrderByCreatedAtAsc(snapshot.getId()).stream().map(j -> new Job(j.getId(),j.getRetryOf(),j.getKind(),j.getStatus(),j.getAttempts(),
                    j.getDispatchGeneration(),j.getNextAttemptAt(),j.getLeaseUntil(),j.getLastErrorCode())).toList();
            return new Review(submissionId,snapshot.getFrozenLabel(),snapshot.getState(),snapshot.getOpensAt(),snapshot.getClosesAt(),clock.instant(),members,history);
        });
    }
    public record Review(UUID submissionId,String projectTitle,QuestionnaireState state,Instant opensAt,Instant closesAt,Instant serverNow,List<Member> members,List<Job> jobs) {}
    public record Member(UUID assignmentId,UUID studentId,String studentName,int questionCount,int answeredCount) {}
    public record Job(UUID id,UUID retryOf,QuestionnaireJobKind kind,QuestionnaireJobStatus status,int attempts,int dispatchGeneration,Instant nextAttemptAt,Instant leaseUntil,String errorCode) {}
    private record Dispatch(int generation,Instant due) {}
    private <T> T transaction(Supplier<T> operation) {
        var tx=new TransactionTemplate(manager); tx.setTimeout(2); return tx.execute(status -> operation.get());
    }
    private static ResponseStatusException busy() { return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Generation is busy; retry delivery"); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Questionnaire job not found"); }
    private static void nontransactional() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Generation requires a nontransactional caller");
    }
    private static final class Work {
        UUID id,snapshotId,lease,studentId;
        int attemptsAtClaim;
        QuestionnaireJobKind kind; String brief,token;
        GitHubEvidence.Resolution resolution; GitHubEvidence evidence;
        List<UUID> questionIds; List<String> prior;
    }
}
