package com.talentbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.dto.response.SubmissionResponse;
import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.exception.*;
import com.talentbridge.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
public class QuestionnaireFinalSubmissionService {
    private final SubmissionService legacy;
    private final SubmissionRepository submissions;
    private final PartyRepository parties;
    private final ProjectRepository projects;
    private final QuestionnaireSnapshotRepository snapshots;
    private final QuestionnaireAssignmentRepository assignments;
    private final QuestionnaireJobRepository jobs;
    private final GitHubEvidenceService github;
    private final QuestionnaireGenerationService generation;
    private final ObjectMapper json;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final TransactionTemplate tx;

    public QuestionnaireFinalSubmissionService(SubmissionService legacy, SubmissionRepository submissions,
            PartyRepository parties, ProjectRepository projects, QuestionnaireSnapshotRepository snapshots,
            QuestionnaireAssignmentRepository assignments, QuestionnaireJobRepository jobs, GitHubEvidenceService github,
            QuestionnaireGenerationService generation, ObjectMapper json, Clock clock, JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${app.questionnaire-enabled:false}") boolean enabled) {
        this.legacy=legacy; this.submissions=submissions; this.parties=parties; this.projects=projects;
        this.snapshots=snapshots; this.assignments=assignments; this.jobs=jobs; this.github=github;
        this.generation=generation; this.json=json; this.clock=clock; this.jdbc=jdbc; this.enabled=enabled;
        tx=new TransactionTemplate(manager); tx.setTimeout(5);
    }

    public SubmissionResponse finalSubmit(UUID partyId, UUID leaderId, Long expectedVersion) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Final acceptance requires a nontransactional caller");
        if (!enabled) return legacy.finalSubmit(partyId, leaderId);
        if (expectedVersion == null || expectedVersion < 0) throw conflict("Save the draft and provide its version before submitting");
        Prepared draft = tx.execute(status -> {
            Party party=parties.findById(partyId).orElseThrow(() -> new ResourceNotFoundException("Party",partyId.toString()));
            Submission sub=validate(party,leaderId,expectedVersion);
            return new Prepared(sub,party.getLeader().getGithubAccessToken());
        });
        GitHubEvidence.Resolution resolution;
        try { resolution=github.resolveRepository(draft.url,draft.branch,draft.token); }
        catch (GitHubEvidence.EvidenceException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Repository resolution unavailable");
        }
        Accepted accepted=tx.execute(status -> {
            // Lock rows before loading entities so follow-on locking cannot retain stale leader/deadline data.
            jdbc.queryForObject("SELECT id FROM parties WHERE id=? FOR UPDATE",UUID.class,partyId);
            Party party=parties.findById(partyId).orElseThrow(() -> new ResourceNotFoundException("Party",partyId.toString()));
            if (party.getAssignedProject()==null || !draft.projectId.equals(party.getAssignedProject().getId()))
                throw conflict("The assigned project changed; reload before submitting");
            jdbc.queryForObject("SELECT id FROM projects WHERE id=? FOR UPDATE",UUID.class,draft.projectId);
            Project project=projects.findById(draft.projectId).orElseThrow(() -> conflict("Assigned project unavailable"));
            Submission sub=validate(party,leaderId,expectedVersion);
            if (!draft.submissionId.equals(sub.getId()) || !Objects.equals(draft.url,sub.getRepoUrl())
                    || !Objects.equals(draft.branch,sub.getRepoBranch())) throw conflict("The saved draft changed; reload before submitting");
            if (project.getStatus()!=ProjectStatus.ASSIGNED) throw conflict("Project is no longer assigned");
            assertDeadline(project);
            String brief=brief(project);
            LinkedHashMap<UUID,User> roster=new LinkedHashMap<>();
            roster.put(party.getLeader().getId(),party.getLeader());
            party.getMembers().stream().sorted(Comparator.comparing(User::getId)).forEach(u -> roster.put(u.getId(),u));
            QuestionnaireSnapshot snapshot=snapshots.save(QuestionnaireSnapshot.builder().submission(sub)
                    .state(QuestionnaireState.GENERATING).frozenBrief(brief).frozenLabel(validText(project.getTitle(),100))
                    .frozenLeaderId(leaderId).frozenResolution(json.valueToTree(resolution)).build());
            for (User student : roster.values()) assignments.save(QuestionnaireAssignment.builder().snapshot(snapshot)
                    .studentId(student.getId()).studentName(validText(student.getFullName(),255))
                    .githubLogin(student.getGithubUsername()==null?null:validText(student.getGithubUsername(),100)).build());
            QuestionnaireJob job=jobs.save(QuestionnaireJob.builder().snapshot(snapshot).kind(QuestionnaireJobKind.EVIDENCE)
                    .jobKey(snapshot.getId()+":evidence").nextAttemptAt(clock.instant()).build());
            sub.setAssessmentVersion(2); sub.setStatus(SubmissionStatus.SUBMITTED);
            sub.setSubmittedAt(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
            party.setStatus(PartyStatus.SUBMITTED);
            submissions.saveAndFlush(sub);
            return new Accepted(SubmissionService.toResponse(sub),job.getId());
        });
        try { generation.dispatch(accepted.jobId()); }
        catch (AssessmentTaskQueue.DispatchException failure) { /* Durable status and coordinator retry cover the commit/enqueue gap. */ }
        return accepted.response();
    }

    private Submission validate(Party party,UUID leader, long version) {
        if (!party.getLeader().getId().equals(leader)) throw new ForbiddenException("Only the party leader can manage submissions");
        if (party.getAssignedProject()==null) throw new BadRequestException("Party has no assigned project");
        Submission sub=submissions.findByPartyId(party.getId()).orElseThrow(() -> conflict("Save your submission first"));
        if (sub.getStatus()!=SubmissionStatus.DRAFT || sub.getVersion()!=version) throw conflict("The saved draft changed; reload before submitting");
        if (!sub.getProject().getId().equals(party.getAssignedProject().getId())) throw conflict("Submission belongs to a different project");
        if (sub.getRepoUrl()==null || sub.getRepoUrl().isBlank()) throw new BadRequestException("GitHub repository URL is required");
        assertDeadline(party.getAssignedProject());
        return sub;
    }
    private void assertDeadline(Project project) {
        if (project.getDeadline()!=null && !project.getDeadline().isAfter(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC)))
            throw new BadRequestException("The project deadline has expired");
    }
    private String brief(Project project) {
        var fields=new LinkedHashMap<String,Object>(); fields.put("title",project.getTitle());
        fields.put("description",project.getDescription()); fields.put("scope",project.getScope());
        fields.put("deliverables",project.getDeliverables()); fields.put("evaluationCriteria",project.getEvaluationCriteria());
        fields.put("tools",project.getTools());
        try { return validText(json.writeValueAsString(fields),96000); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new BadRequestException("Project brief unavailable"); }
    }
    private static String validText(String value,int max) {
        if (value==null || value.isBlank() || value.length()>max || value.indexOf('\0')>=0 || !StandardCharsets.UTF_8.newEncoder().canEncode(value))
            throw new BadRequestException("Frozen project or participant information is invalid or too large");
        return value;
    }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
    private record Accepted(SubmissionResponse response,UUID jobId) {}
    private static final class Prepared {
        final UUID submissionId,projectId;
        final String url,branch,token;
        Prepared(Submission sub,String token) { submissionId=sub.getId(); projectId=sub.getProject().getId();
            url=sub.getRepoUrl(); branch=sub.getRepoBranch(); this.token=token; }
    }
}
