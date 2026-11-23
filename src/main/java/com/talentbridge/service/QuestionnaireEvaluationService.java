package com.talentbridge.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.talentbridge.dto.response.EvaluationReportResponse;
import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.exception.ForbiddenException;
import com.talentbridge.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.talentbridge.service.AssessmentAIService.*;

@Service @RequiredArgsConstructor
public class QuestionnaireEvaluationService {
    private final QuestionnaireSnapshotRepository snapshots;
    private final QuestionnaireAssignmentRepository assignments;
    private final QuestionnaireEvidenceRepository evidence;
    private final QuestionnaireJobRepository jobs;
    private final QuestionnaireEvaluationRunRepository runs;
    private final QuestionnaireEvaluationStepRepository steps;
    private final QuestionnaireQuestionRepository questions;
    private final QuestionnaireMarkAdjustmentRepository adjustments;
    private final EvaluationReportRepository reports;
    private final SubmissionRepository submissions;
    private final UserRepository users;
    private final NotificationRepository notifications;
    private final ScorecardService scorecards;
    private final AssessmentAIService ai;
    private final AssessmentTaskQueue queue;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final Clock clock;

    public boolean versionTwoSubmission(UUID id) {
        return transaction(()->submissions.findById(id).orElseThrow(QuestionnaireEvaluationService::notFound).getAssessmentVersion()==2);
    }
    public boolean versionTwoReport(UUID id) {
        return transaction(()->reports.findById(id).orElseThrow(QuestionnaireEvaluationService::notFound).getSubmission().getAssessmentVersion()==2);
    }

    public RunView start(UUID submissionId,UUID actor) { return start(submissionId,actor,false); }

    public RunView reevaluate(UUID reportId,UUID actor) {
        UUID submission=transaction(()->reports.findById(reportId).orElseThrow(QuestionnaireEvaluationService::notFound).getSubmission().getId());
        return start(submission,actor,true);
    }

    private RunView start(UUID submissionId,UUID actor,boolean repeat) {
        nontransactional();
        UUID id=transaction(()->{
            var snapshot=lockSubmission(submissionId);
            var roster=lockRoster(snapshot.getId());
            requireExpired(snapshot);
            if (snapshot.getState()==QuestionnaireState.FINALIZED) throw conflict("Finalized assessment is immutable");
            if (snapshot.getSubmission().getStatus()==SubmissionStatus.DRAFT) throw conflict("Only final submissions can be evaluated");
            var prior=runs.findFirstBySnapshotIdOrderBySequenceDesc(snapshot.getId()).orElse(null);
            if (prior!=null && (!repeat || !"REVIEW".equals(prior.getStatus()))) return prior.getId();
            List<MemberAnswers> complete=new ArrayList<>(); List<UUID> incomplete=new ArrayList<>();
            if (prior!=null) {
                complete=members(prior.getLockedMembers());
                incomplete=json.convertValue(prior.getIncompleteMembers(),new TypeReference<List<UUID>>(){});
            } else for(var member:roster) {
                if (member.getQuestions().size()!=5) throw conflict("Published question set is not ready");
                var saved=member.getQuestions().stream().sorted(Comparator.comparingInt(QuestionnaireQuestion::getPosition)).map(q->new Answer(q.getId(),q.getPrompt(),q.getHiddenRubric(),
                        json.convertValue(q.getSourceReferences(),new TypeReference<List<GitHubEvidence.SourceReference>>(){}),member.getAnswers().getOrDefault(q.getId().toString(),""))).toList();
                if (saved.stream().allMatch(q->!q.answer().isBlank())) complete.add(new MemberAnswers(member.getStudentId(),saved));
                else incomplete.add(member.getStudentId());
            }
            var captured=captured(snapshot.getId());
            List<List<MemberAnswers>> partition;
            try {
                ai.prepareRepositoryEvaluation(snapshot.getFrozenBrief(),captured);
                partition=complete.isEmpty()?List.of():ai.prepareQuestionnaireBatches(snapshot.getFrozenBrief(),captured,complete);
                // Validate every immutable request before the first dispatch or quota reservation.
                for(var batch:partition) ai.prepareQuestionnaireBatch(snapshot.getFrozenBrief(),captured,batch);
            } catch (AssessmentException failure) { throw error(HttpStatus.UNPROCESSABLE_ENTITY,"Evaluation input could not be prepared"); }
            if (prior!=null) {prior.setStatus("SUPERSEDED"); runs.flush();}
            var run=runs.saveAndFlush(QuestionnaireEvaluationRun.builder().snapshot(snapshot).sequence(prior==null?1:prior.getSequence()+1)
                    .triggeredById(actor).status("RUNNING").lockedMembers(json.valueToTree(complete)).incompleteMembers(json.valueToTree(incomplete)).build());
            addStep(run,0,"REPOSITORY",List.of());
            for(int i=0;i<partition.size();i++) addStep(run,i+1,"QUESTIONNAIRE",partition.get(i));
            for(var member:roster) {
                if(member.getLockedAnswerRevision()==null) member.setLockedAnswerRevision(member.getRevision());
                if(incomplete.contains(member.getStudentId())) {
                    member.setGradeState(QuestionnaireGradeState.INCOMPLETE);member.setAiScore(0);member.setEffectiveScore(0);
                    member.setGradedRevision(member.getLockedAnswerRevision());
                }
            }
            snapshot.setState(QuestionnaireState.GRADING); snapshot.getSubmission().setStatus(SubmissionStatus.UNDER_EVALUATION);
            return run.getId();
        });
        dispatchNext(id);
        return run(id);
    }

    private void addStep(QuestionnaireEvaluationRun run,int position,String kind,List<MemberAnswers> members) {
        var job=jobs.saveAndFlush(QuestionnaireJob.builder().snapshot(run.getSnapshot()).kind(QuestionnaireJobKind.EVALUATE)
                .jobKey(run.getId()+":evaluate:"+position).nextAttemptAt(clock.instant()).build());
        steps.save(QuestionnaireEvaluationStep.builder().run(run).position(position).kind(kind).members(json.valueToTree(members)).job(job).build());
    }

    public boolean handles(UUID jobId) {
        return transaction(()->jobs.findById(jobId).orElseThrow(QuestionnaireEvaluationService::notFound).getKind()==QuestionnaireJobKind.EVALUATE);
    }

    public void handle(UUID jobId) {
        nontransactional();
        Work work=transaction(()->claim(jobId));
        if(work!=null) {
            try {
                var inputs=prepare(work);
                // Reprepare the persisted partition, never replan it on recovery.
                var repository=ai.prepareRepositoryEvaluation(work.brief(),inputs.evidence());
                var prepared=new ArrayList<PreparedQuestionnaireEvaluation>();
                for(var batch:inputs.partition()) prepared.add(ai.prepareQuestionnaireBatch(work.brief(),inputs.evidence(),batch));
                transaction(()->{leased(work);return null;});
                Runnable attempt=()->transaction(()->{startAttempt(work);return null;});
                JsonNode result="REPOSITORY".equals(work.kind())?json.valueToTree(ai.evaluateRepository(repository,attempt))
                        :json.valueToTree(ai.evaluateQuestionnaire(prepared.get(work.position()-1),attempt));
                transaction(()->{finish(work,result);return null;});
            } catch (AssessmentQuotaService.QuotaExceeded quota) {
                transaction(()->{var job=leased(work); clearLease(job); job.setStatus(QuestionnaireJobStatus.DEFERRED);
                    job.setNextAttemptAt(quota.resetAt()); job.setLastErrorCode("ASSESSMENT_DAILY_LIMIT"); bump(job); return null;});
            } catch (RuntimeException failure) {
                transaction(()->{fail(work);return null;});
            }
            if (!dispatchNext(work.runId())) throw busy();
        } else {
            UUID runId=transaction(()->steps.findByJobId(jobId).map(s->s.getRun().getId()).orElse(null));
            if(runId!=null && !dispatchNext(runId)) throw busy();
        }
    }

    private Work claim(UUID id) {
        var job=lockJob(id); Instant now=clock.instant();
        if(terminal(job)) return null;
        if(job.getKind()!=QuestionnaireJobKind.EVALUATE) throw conflict("Not an evaluation step");
        var step=steps.findByJobId(id).orElseThrow(QuestionnaireEvaluationService::notFound);
        var run=step.getRun();
        if(!"RUNNING".equals(run.getStatus())) throw conflict("Evaluation run is not active");
        requireExpired(job.getSnapshot());
        if(job.getLeaseUntil()!=null && now.isBefore(job.getLeaseUntil())) throw busy();
        var all=steps.findAllByRunIdOrderByPosition(run.getId());
        var first=all.stream().filter(s->s.getResult()==null).findFirst().orElseThrow();
        if(!first.getId().equals(step.getId())) throw busy();
        if(job.getNextAttemptAt()!=null && now.isBefore(job.getNextAttemptAt())) {bump(job);return null;}
        finishAttempt(job,"UNKNOWN","LEASE_EXPIRED"); clearLease(job);
        if(job.getAttempts()>=3) {job.setStatus(QuestionnaireJobStatus.FAILED);job.setLastErrorCode("ATTEMPT_LIMIT");run.setStatus("FAILED"); return null;}
        job.setStatus(QuestionnaireJobStatus.RUNNING); job.setLeaseToken(UUID.randomUUID());job.setLeaseUntil(now.plusSeconds(110));
        return new Work(id,job.getLeaseToken(),job.getAttempts(),run.getId(),step.getId(),step.getPosition(),step.getKind(),job.getSnapshot().getId(),job.getSnapshot().getFrozenBrief());
    }

    private Inputs prepare(Work work) {
        var frozen=transaction(()->evidence.findBySnapshotId(work.snapshotId()).orElseThrow(QuestionnaireEvaluationService::notFound).getEvidence());
        var partition=transaction(()->steps.findAllByRunIdOrderByPosition(work.runId()).stream().filter(s->s.getPosition()>0).map(QuestionnaireEvaluationStep::getMembers).toList());
        return new Inputs(captured(frozen),partition.stream().map(this::members).map(List::copyOf).toList());
    }

    private void startAttempt(Work work) {
        var job=leased(work);
        if(job.getAttempts()>=3) throw new IllegalStateException("ATTEMPT_LIMIT");
        job.setAttempts(job.getAttempts()+1);
        jdbc.update("INSERT INTO questionnaire_job_attempts(job_id,attempt,started_at,outcome) VALUES (?,?,?,'STARTED')",job.getId(),job.getAttempts(),Timestamp.from(clock.instant()));
    }

    private void finish(Work work,JsonNode result) {
        var job=leased(work); var step=steps.findById(work.stepId()).orElseThrow(QuestionnaireEvaluationService::notFound);
        if(result==null || !result.isObject()) throw new IllegalStateException("INVALID_EVALUATION_RESULT");
        if(step.getResult()!=null) throw busy();
        step.setResult(result); finishAttempt(job,"SUCCEEDED",null); clearLease(job);
        job.setStatus(QuestionnaireJobStatus.SUCCEEDED);job.setNextAttemptAt(null);job.setLastErrorCode(null);
        var all=steps.findAllByRunIdOrderByPosition(work.runId());
        if(all.stream().allMatch(s->s.getResult()!=null)) {
            applyMarks(step.getRun(),all);
            recompute(step.getRun(),all.get(0));
            step.getRun().setStatus("REVIEW");job.getSnapshot().setState(QuestionnaireState.REVIEW);
        }
    }

    private void applyMarks(QuestionnaireEvaluationRun run,List<QuestionnaireEvaluationStep> all) {
        var expected=new LinkedHashMap<UUID,Set<UUID>>();
        for(var member:members(run.getLockedMembers())) expected.put(member.memberId(),member.questions().stream().map(Answer::id).collect(java.util.stream.Collectors.toSet()));
        var received=new HashMap<UUID,Map<UUID,QuestionMark>>();
        for(var step:all) if(step.getPosition()>0) {
            var result=convert(step.getResult(),QuestionnaireEvaluation.class);
            for(var member:result.members()) for(var mark:member.questions()) {
                if(!expected.containsKey(member.memberId()) || !expected.get(member.memberId()).contains(mark.id()) || mark.mark()<0 || mark.mark()>20
                        || received.computeIfAbsent(member.memberId(),k->new HashMap<>()).putIfAbsent(mark.id(),mark)!=null) throw new IllegalStateException("INVALID_EVALUATION_RESULT");
            }
        }
        if(!received.keySet().equals(expected.keySet())) throw new IllegalStateException("INVALID_EVALUATION_RESULT");
        Set<UUID> overridden=new HashSet<>(jdbc.queryForList("SELECT DISTINCT m.question_id FROM questionnaire_mark_adjustments m JOIN questionnaire_questions q ON q.id=m.question_id JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id=?",UUID.class,run.getSnapshot().getId()));
        var incomplete=new HashSet<>(json.convertValue(run.getIncompleteMembers(),new TypeReference<List<UUID>>(){}));
        for(var member:assignments.findAllBySnapshotId(run.getSnapshot().getId())) {
            member.setGradedRevision(member.getLockedAnswerRevision());
            if(incomplete.contains(member.getStudentId())) {
                member.setGradeState(QuestionnaireGradeState.INCOMPLETE);member.setAiScore(0);member.setEffectiveScore(0); continue;
            }
            var marks=received.get(member.getStudentId());
            if(marks==null || marks.size()!=5 || !marks.keySet().equals(expected.get(member.getStudentId()))) throw new IllegalStateException("INVALID_EVALUATION_RESULT");
            int aiTotal=0,effectiveTotal=0;
            for(var q:member.getQuestions()) {
                var mark=marks.get(q.getId()); if(mark==null) throw new IllegalStateException("INVALID_EVALUATION_RESULT");
                if(!overridden.contains(q.getId())) {q.setAiScore(mark.mark());q.setEffectiveScore(mark.mark());q.setGradingNotes(mark.feedback());}
                aiTotal+=q.getAiScore(); effectiveTotal+=q.getEffectiveScore();
            }
            member.setAiScore(aiTotal);member.setEffectiveScore(effectiveTotal);member.setGradeState(QuestionnaireGradeState.GRADED);
        }
    }

    private void recompute(QuestionnaireEvaluationRun run,QuestionnaireEvaluationStep repository) {
        var snapshot=run.getSnapshot();var captured=captured(snapshot.getId());
        var scores=convert(repository.getResult(),RepositoryEvaluation.class).repository();
        var roster=assignments.findAllBySnapshotId(snapshot.getId());
        if(roster.isEmpty() || roster.stream().anyMatch(a->a.getEffectiveScore()==null)) throw conflict("Questionnaire grades are incomplete");
        double repo=.20*scores.get("authenticity").score()+.25*scores.get("quality").score()+.25*scores.get("functionality").score()+.20*scores.get("scope").score()+.10*scores.get("collaboration").score();
        double questionnaire=roster.stream().mapToInt(QuestionnaireAssignment::getEffectiveScore).average().orElseThrow();
        double team=.75*repo+.25*questionnaire;
        var report=reports.findBySubmissionId(snapshot.getSubmission().getId()).orElseGet(()->EvaluationReport.builder().submission(snapshot.getSubmission()).build());
        if(report.isFinalized()) throw conflict("Finalized assessment is immutable");
        report.setAiDetectionScore(scores.get("authenticity").score()); report.setAiDetectionNotes(scores.get("authenticity").notes());
        report.setCodeQualityScore(scores.get("quality").score());report.setCodeQualityNotes(scores.get("quality").notes());
        report.setFunctionalityScore(scores.get("functionality").score());report.setFunctionalityNotes(scores.get("functionality").notes());
        report.setScopeAlignmentScore(scores.get("scope").score());report.setScopeAlignmentNotes(scores.get("scope").notes());
        report.setTeamCollaborationScore(scores.get("collaboration").score());report.setTeamCollaborationNotes(scores.get("collaboration").notes());
        report.setRepositoryScore(repo);report.setQuestionnaireScore(questionnaire);report.setTotalScore(team);
        report.setContributorCapturedAt(captured.contributors().capturedAt());report.setContributorsTruncated(captured.contributors().truncated());
        report.setTriggeredBy(users.getReferenceById(run.getTriggeredById()));report.setEvaluatedAt(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
        report.setOverallSummary("Repository evidence is a bounded sample. Provenance indicators are uncertain, not proof of AI authorship or misconduct. Contributor coverage is the first 100 returned records at the stated timestamp, not commit-pinned or proof of effort.");
        Map<String,Long> commits=new HashMap<>();
        for(var c:captured.contributors().records()) commits.merge(c.login().strip().toLowerCase(Locale.ROOT),c.contributions(),Math::addExact);
        double denominator=captured.contributors().records().stream().mapToDouble(GitHubEvidence.Contributor::contributions).sum();
        report.getStudentScores().clear();
        for(var member:roster) {
            long count=member.getGithubLogin()==null?0:commits.getOrDefault(member.getGithubLogin().strip().toLowerCase(Locale.ROOT),0L);
            double percentage=denominator>0?count*100.0/denominator:0;
            double factor=percentage/(100.0/roster.size());
            double individual=Math.max(0,Math.min(100,team*(.85+.15*Math.min(factor,1.3))));
            report.getStudentScores().add(StudentEvaluationScore.builder().evaluationReport(report).student(users.getReferenceById(member.getStudentId()))
                    .totalCommits(Math.toIntExact(count)).contributionPercentage(percentage).individualScore(individual)
                    .frozenStudentName(member.getStudentName()).frozenGithubLogin(member.getGithubLogin())
                    .performanceNotes("Timestamped first-100 contributor sample; counts are not commit-pinned or proof of effort.").build());
        }
        reports.saveAndFlush(report);
    }

    private void fail(Work work) {
        var job=leased(work);
        finishAttempt(job,"FAILED","EVALUATION_UNAVAILABLE"); clearLease(job);job.setLastErrorCode("EVALUATION_UNAVAILABLE");
        if(job.getAttempts()<=work.attemptsAtClaim() || job.getAttempts()>=3) {
            job.setStatus(QuestionnaireJobStatus.FAILED);runs.findById(work.runId()).orElseThrow().setStatus("FAILED");
        } else {job.setStatus(QuestionnaireJobStatus.PENDING);job.setNextAttemptAt(clock.instant().plusSeconds(30));bump(job);}
    }

    public RunView retry(UUID runId) {
        nontransactional();
        transaction(()->{
            var snapshot=lockRun(runId);var run=runs.findById(runId).orElseThrow(QuestionnaireEvaluationService::notFound);
            requireExpired(snapshot);
            if(snapshot.getState()==QuestionnaireState.FINALIZED) throw conflict("Finalized assessment is immutable");
            if(!runs.findFirstBySnapshotIdOrderBySequenceDesc(snapshot.getId()).orElseThrow().getId().equals(runId)) throw conflict("Evaluation run was superseded");
            if("REVIEW".equals(run.getStatus())) return null;
            var step=steps.findAllByRunIdOrderByPosition(runId).stream().filter(s->s.getResult()==null).findFirst().orElseThrow();var job=step.getJob();
            if(job.getLeaseUntil()!=null && clock.instant().isBefore(job.getLeaseUntil())) throw conflict("Evaluation is already running");
            finishAttempt(job,"UNKNOWN","LEASE_EXPIRED");clearLease(job);
            if(job.getStatus()==QuestionnaireJobStatus.FAILED || job.getAttempts()>=3) {
                job.setStatus(QuestionnaireJobStatus.FAILED);
                step.setJob(jobs.saveAndFlush(QuestionnaireJob.builder().snapshot(snapshot).kind(QuestionnaireJobKind.EVALUATE)
                        .jobKey(runId+":recovery:"+UUID.randomUUID()).retryOf(job.getId()).nextAttemptAt(clock.instant()).build()));
            } else {bump(job);if(job.getStatus()!=QuestionnaireJobStatus.DEFERRED) {job.setStatus(QuestionnaireJobStatus.PENDING);job.setNextAttemptAt(clock.instant());}}
            run.setStatus("RUNNING");snapshot.setState(QuestionnaireState.GRADING);return null;
        });
        dispatchNext(runId); return run(runId);
    }

    private boolean dispatchNext(UUID runId) {
        Dispatch plan=transaction(()->{
            lockRun(runId);var run=runs.findById(runId).orElseThrow(QuestionnaireEvaluationService::notFound);
            if(!"RUNNING".equals(run.getStatus())) return null;
            var step=steps.findAllByRunIdOrderByPosition(runId).stream().filter(s->s.getResult()==null).findFirst().orElse(null);
            if(step==null || terminal(step.getJob()) || step.getJob().getStatus()==QuestionnaireJobStatus.RUNNING) return null;
            var job=step.getJob();return new Dispatch(job.getId(),job.getDispatchGeneration(),job.getNextAttemptAt()==null?clock.instant():job.getNextAttemptAt());
        });
        if(plan==null) return true;
        String failure=null;
        try {queue.enqueue(plan.job(),plan.generation(),plan.due());} catch(AssessmentTaskQueue.DispatchException unavailable) {failure="DISPATCH_UNAVAILABLE";}
        String code=failure;
        transaction(()->{var job=lockJob(plan.job());if(!terminal(job) && job.getDispatchGeneration()==plan.generation() && job.getStatus()!=QuestionnaireJobStatus.RUNNING) {
            job.setLastErrorCode(code);if(code==null && job.getStatus()!=QuestionnaireJobStatus.DEFERRED) job.setStatus(QuestionnaireJobStatus.DISPATCHED);
        } return null;});
        return failure==null;
    }

    public RunView run(UUID id) {
        return transaction(()->{
            var run=runs.findById(id).orElseThrow(QuestionnaireEvaluationService::notFound);
            UUID report=reports.findBySubmissionId(run.getSnapshot().getSubmission().getId()).map(EvaluationReport::getId).orElse(null);
            var views=steps.findAllByRunIdOrderByPosition(id).stream().map(s->{var j=s.getJob();return new StepView(s.getId(),j.getId(),s.getPosition(),s.getKind(),
                    members(s.getMembers()).stream().mapToInt(m->m.questions().size()).sum(),j.getStatus(),j.getAttempts(),j.getDispatchGeneration(),j.getNextAttemptAt(),j.getLeaseUntil(),j.getLastErrorCode());}).toList();
            return new RunView(id,run.getSnapshot().getSubmission().getId(),2,run.getStatus(),report,clock.instant(),views);
        });
    }

    public Optional<EvaluationReportResponse> report(UUID submissionId,UUID viewerId) {
        return transaction(()->{
            var snapshot=snapshots.findBySubmissionIdAndAssessmentVersion(submissionId,2).orElseThrow(QuestionnaireEvaluationService::notFound);
            var viewer=users.findById(viewerId).orElseThrow(QuestionnaireEvaluationService::notFound);
            var submission=snapshot.getSubmission();var party=submission.getParty();var project=submission.getProject();
            boolean coordinator=viewer.getRole()==UserRole.COORDINATOR;
            boolean frozen=assignments.findAllBySnapshotId(snapshot.getId()).stream().anyMatch(a->a.getStudentId().equals(viewerId));
            boolean related=coordinator || frozen || project.getCreatedBy().getId().equals(viewerId)
                    || (party.getSupervisor()!=null && party.getSupervisor().getId().equals(viewerId))
                    || (project.getProjectSupervisor()!=null && project.getProjectSupervisor().getId().equals(viewerId));
            if(!related) throw new ForbiddenException("You cannot view this evaluation");
            return reports.findBySubmissionId(submissionId).filter(r->coordinator || r.isFinalized()).map(EvaluationReportResponse::from);
        });
    }

    public Optional<EvaluationReportResponse> ownerReport(UUID assignmentId,UUID studentId) {
        UUID submission=transaction(()->assignments.findByIdAndStudentId(assignmentId,studentId).orElseThrow(QuestionnaireEvaluationService::notFound).getSnapshot().getSubmission().getId());
        return report(submission,studentId);
    }

    public EvaluationReportResponse finalizeReport(UUID reportId,UUID actor) {
        return transaction(()->{
            UUID submission=jdbc.query("SELECT submission_id FROM evaluation_reports WHERE id=?",(r,n)->r.getObject(1,UUID.class),reportId)
                    .stream().findFirst().orElseThrow(QuestionnaireEvaluationService::notFound);
            // Resolve scalar identity before locking; reload the report only after the authoritative lock.
            var snapshot=lockSubmission(submission);
            var report=reports.findBySubmissionId(submission).orElseThrow(QuestionnaireEvaluationService::notFound);
            if(report.isFinalized()) return EvaluationReportResponse.from(report);
            requireExpired(snapshot);
            var run=runs.findFirstBySnapshotIdOrderBySequenceDesc(snapshot.getId()).orElseThrow(QuestionnaireEvaluationService::notFound);
            if(snapshot.getState()!=QuestionnaireState.REVIEW || !"REVIEW".equals(run.getStatus())) throw conflict("Assessment is not ready for finalization");
            report.setFinalized(true);snapshot.setState(QuestionnaireState.FINALIZED);run.setStatus("FINALIZED");snapshot.getSubmission().setStatus(SubmissionStatus.EVALUATED);
            for(var member:assignments.findAllBySnapshotId(snapshot.getId()).stream().sorted(Comparator.comparing(QuestionnaireAssignment::getStudentId)).toList()) {
                var user=users.getReferenceById(member.getStudentId());scorecards.addEntry(user,snapshot.getSubmission().getProject(),report);
                notifications.save(Notification.builder().recipient(user).type(NotificationType.EVALUATION_COMPLETE).title("Evaluation finalized")
                        .message("Your finalized project grade is available.").referenceType("QUESTIONNAIRE").referenceId(member.getId().toString()).build());
            }
            reports.flush();return EvaluationReportResponse.from(report);
        });
    }

    public PrivateReview privateReview(UUID submissionId) {
        return transaction(()->{var snapshot=snapshots.findBySubmissionIdAndAssessmentVersion(submissionId,2).orElseThrow(QuestionnaireEvaluationService::notFound);
            requireExpired(snapshot);return privateView(snapshot);});
    }

    public PrivateReview adjust(UUID submissionId,UUID questionId,UUID actor,Integer mark,String reason) {
        if(mark==null || mark<0 || mark>20 || reason==null || reason.isBlank() || reason.length()>2000 || !supportedPlainText(reason))
            throw error(HttpStatus.BAD_REQUEST,"A mark from 0 to 20 and a nonblank supported-text reason of at most 2000 characters are required");
        return transaction(()->{
            var snapshot=lockSubmission(submissionId);requireExpired(snapshot);
            if(snapshot.getState()!=QuestionnaireState.REVIEW) throw conflict("Marks can only be adjusted during review");
            var q=questions.findById(questionId).orElseThrow(QuestionnaireEvaluationService::notFound);
            if(!q.getAssignment().getSnapshot().getId().equals(snapshot.getId())) throw notFound();
            var member=q.getAssignment();
            if(member.getGradeState()!=QuestionnaireGradeState.GRADED || q.getEffectiveScore()==null) throw conflict("A complete validated grade is required");
            adjustments.save(QuestionnaireMarkAdjustment.builder().question(q).actorId(actor).previousScore(q.getEffectiveScore()).adjustedScore(mark).reason(reason).adjustedAt(clock.instant()).build());
            q.setEffectiveScore(mark);member.setEffectiveScore(member.getQuestions().stream().mapToInt(QuestionnaireQuestion::getEffectiveScore).sum());
            var run=runs.findFirstBySnapshotIdOrderBySequenceDesc(snapshot.getId()).orElseThrow();
            recompute(run,steps.findAllByRunIdOrderByPosition(run.getId()).get(0));
            return privateView(snapshot);
        });
    }

    private PrivateReview privateView(QuestionnaireSnapshot snapshot) {
        var audit=jdbc.query("SELECT m.question_id,m.actor_id,m.previous_score,m.adjusted_score,m.reason,m.adjusted_at FROM questionnaire_mark_adjustments m JOIN questionnaire_questions q ON q.id=m.question_id JOIN questionnaire_assignments a ON a.id=q.assignment_id WHERE a.snapshot_id=? ORDER BY m.adjusted_at,m.created_at,m.id",
                (r,n)->new Audit(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getInt(3),r.getInt(4),r.getString(5),r.getTimestamp(6).toInstant()),snapshot.getId());
        var members=assignments.findAllBySnapshotId(snapshot.getId()).stream().sorted(Comparator.comparing(QuestionnaireAssignment::getId)).map(a->{
            boolean incomplete=a.getQuestions().size()==5 && a.getQuestions().stream().anyMatch(q->a.getAnswers().getOrDefault(q.getId().toString(),"").isBlank());
            return new PrivateMember(a.getId(),a.getStudentId(),a.getStudentName(),incomplete?QuestionnaireGradeState.INCOMPLETE:a.getGradeState(),
                    incomplete?Integer.valueOf(0):a.getAiScore(),incomplete?Integer.valueOf(0):a.getEffectiveScore(),a.getLockedAnswerRevision(),a.getQuestions().stream().map(q->new PrivateQuestion(q.getId(),q.getPosition(),q.getPrompt(),q.getHiddenRubric(),
                    q.getSourceReferences(),a.getAnswers().getOrDefault(q.getId().toString(),""),q.getAiScore(),q.getEffectiveScore(),q.getGradingNotes(),audit.stream().filter(m->m.questionId().equals(q.getId())).toList())).toList());
        }).toList();
        return new PrivateReview(snapshot.getSubmission().getId(),snapshot.getState(),snapshot.getOpensAt(),snapshot.getClosesAt(),clock.instant(),members);
    }

    public List<CoordinatorSubmission> coordinatorSubmissions() {
        Instant now=clock.instant();
        return jdbc.query("SELECT s.id,s.party_id,p.name,s.project_id,COALESCE(q.frozen_label,project.title),s.status,s.assessment_version,s.version,s.repo_url,s.repo_branch,s.submitted_at,q.state,q.opens_at,q.closes_at,r.id,r.status,e.id,e.finalized FROM submissions s JOIN parties p ON p.id=s.party_id JOIN projects project ON project.id=s.project_id LEFT JOIN questionnaire_snapshots q ON q.submission_id=s.id LEFT JOIN LATERAL (SELECT id,status FROM questionnaire_evaluation_runs WHERE snapshot_id=q.id ORDER BY sequence DESC LIMIT 1) r ON true LEFT JOIN evaluation_reports e ON e.submission_id=s.id ORDER BY s.created_at DESC,s.id",
                (r,n)->new CoordinatorSubmission(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getObject(4,UUID.class),r.getString(5),r.getString(6),r.getInt(7),r.getLong(8),r.getString(9),r.getString(10),
                        r.getTimestamp(11)==null?null:r.getTimestamp(11).toLocalDateTime(),r.getString(12),instant(r.getTimestamp(13)),instant(r.getTimestamp(14)),r.getObject(15,UUID.class),r.getString(16),r.getObject(17,UUID.class),r.getBoolean(18),now));
    }

    private GitHubEvidence captured(UUID snapshot) {
        return captured(evidence.findBySnapshotId(snapshot).orElseThrow(QuestionnaireEvaluationService::notFound).getEvidence());
    }
    private GitHubEvidence captured(JsonNode frozen) {
        var value=convert(frozen,GitHubEvidence.class);
        if(value.contributors()==null || value.contributors().capturedAt()==null || value.contributors().records().stream().anyMatch(c->c.login()==null || c.contributions()<0))
            throw conflict("Frozen contributor evidence is unavailable");
        return value;
    }
    private QuestionnaireSnapshot lockSubmission(UUID submission) {return lockSnapshot(snapshots.idBySubmission(submission).orElseThrow(QuestionnaireEvaluationService::notFound));}
    private QuestionnaireSnapshot lockRun(UUID run) {return lockSnapshot(jdbc.query("SELECT snapshot_id FROM questionnaire_evaluation_runs WHERE id=?",(r,n)->r.getObject(1,UUID.class),run)
            .stream().findFirst().orElseThrow(QuestionnaireEvaluationService::notFound));}
    private QuestionnaireSnapshot lockSnapshot(UUID id) {
        jdbc.queryForObject("SELECT id FROM questionnaire_snapshots WHERE id=? FOR UPDATE",UUID.class,id);
        return snapshots.findById(id).orElseThrow(QuestionnaireEvaluationService::notFound);
    }
    private List<QuestionnaireAssignment> lockRoster(UUID snapshot) {
        jdbc.queryForList("SELECT id FROM questionnaire_assignments WHERE snapshot_id=? ORDER BY id FOR UPDATE",UUID.class,snapshot);
        var roster=assignments.findAllBySnapshotId(snapshot).stream().sorted(Comparator.comparing(QuestionnaireAssignment::getId)).toList();
        if(roster.isEmpty()) throw conflict("Frozen roster is unavailable");return roster;
    }
    private QuestionnaireJob lockJob(UUID id) {
        lockSnapshot(jobs.snapshotId(id).orElseThrow(QuestionnaireEvaluationService::notFound));
        return jobs.findById(id).orElseThrow(QuestionnaireEvaluationService::notFound);
    }
    private QuestionnaireJob leased(Work work) {
        var job=lockJob(work.jobId());
        if(!work.lease().equals(job.getLeaseToken()) || job.getStatus()!=QuestionnaireJobStatus.RUNNING || !clock.instant().isBefore(job.getLeaseUntil())) throw busy();return job;
    }
    private void requireExpired(QuestionnaireSnapshot snapshot) {
        Instant now=clock.instant();
        if(snapshot.getOpensAt()==null || snapshot.getClosesAt()==null || now.isBefore(snapshot.getClosesAt())) throw conflict("Evaluation requires publication and the full 48-hour answer window");
    }
    private void finishAttempt(QuestionnaireJob job,String outcome,String code) {
        jdbc.update("UPDATE questionnaire_job_attempts SET outcome=?,finished_at=?,error_code=? WHERE job_id=? AND outcome='STARTED'",outcome,Timestamp.from(clock.instant()),code,job.getId());
    }
    private static void clearLease(QuestionnaireJob job){job.setLeaseToken(null);job.setLeaseUntil(null);}
    private static void bump(QuestionnaireJob job){job.setDispatchGeneration(job.getDispatchGeneration()+1);}
    private static boolean terminal(QuestionnaireJob job){return job.getStatus()==QuestionnaireJobStatus.SUCCEEDED || job.getStatus()==QuestionnaireJobStatus.FAILED;}
    private List<MemberAnswers> members(JsonNode node){return json.convertValue(node,new TypeReference<List<MemberAnswers>>(){});}
    private <T> T convert(JsonNode node,Class<T> type){return json.convertValue(node,type);}
    private <T> T transaction(Supplier<T> operation){var tx=new TransactionTemplate(manager);tx.setTimeout(2);return tx.execute(status->operation.get());}
    private static void nontransactional(){if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Evaluation requires a nontransactional caller");}
    private static Instant instant(Timestamp value){return value==null?null:value.toInstant();}
    private static ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private static ResponseStatusException notFound(){return error(HttpStatus.NOT_FOUND,"Evaluation not found");}
    private static ResponseStatusException conflict(String message){return error(HttpStatus.CONFLICT,message);}
    private static ResponseStatusException busy(){return error(HttpStatus.SERVICE_UNAVAILABLE,"Evaluation is busy; retry delivery");}
    private record Dispatch(UUID job,int generation,Instant due){}
    private record Work(UUID jobId,UUID lease,int attemptsAtClaim,UUID runId,UUID stepId,int position,String kind,UUID snapshotId,String brief){}
    private record Inputs(GitHubEvidence evidence,List<List<MemberAnswers>> partition){}
    public record RunView(UUID id,UUID submissionId,int assessmentVersion,String status,UUID reportId,Instant serverNow,List<StepView> steps){}
    public record StepView(UUID id,UUID jobId,int position,String kind,int questionCount,QuestionnaireJobStatus status,int attempts,int dispatchGeneration,Instant nextAttemptAt,Instant leaseUntil,String errorCode){}
    public record PrivateReview(UUID submissionId,QuestionnaireState state,Instant opensAt,Instant closesAt,Instant serverNow,List<PrivateMember> members){}
    public record PrivateMember(UUID assignmentId,UUID studentId,String studentName,QuestionnaireGradeState gradeState,Integer aiScore,Integer effectiveScore,Long lockedAnswerRevision,List<PrivateQuestion> questions){}
    public record PrivateQuestion(UUID id,int position,String prompt,String rubric,JsonNode sourceReferences,String answer,Integer aiScore,Integer effectiveScore,String feedback,List<Audit> adjustments){}
    public record Audit(UUID questionId,UUID actorId,int previousScore,int adjustedScore,String reason,Instant adjustedAt){}
    public record CoordinatorSubmission(UUID id,UUID partyId,String partyName,UUID projectId,String projectTitle,String status,int assessmentVersion,long version,String repoUrl,String repoBranch,LocalDateTime submittedAt,
            String questionnaireState,Instant opensAt,Instant closesAt,UUID runId,String runStatus,UUID reportId,boolean finalized,Instant serverNow){}
}
