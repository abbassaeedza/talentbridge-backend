package com.talentbridge.service;

import com.talentbridge.entity.ApplicationSettings;
import com.talentbridge.entity.EvaluationReport;
import com.talentbridge.entity.Party;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.Submission;
import com.talentbridge.entity.User;
import com.talentbridge.enums.ProjectStatus;
import com.talentbridge.enums.SubmissionStatus;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.repository.ApplicationRepository;
import com.talentbridge.repository.ApplicationSettingsRepository;
import com.talentbridge.repository.PartyRepository;
import com.talentbridge.repository.ProjectRepository;
import com.talentbridge.repository.ScorecardRepository;
import com.talentbridge.repository.SubmissionRepository;
import com.talentbridge.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatContextService {
    private final ProjectRepository projectRepository;
    private final PartyRepository partyRepository;
    private final SubmissionRepository submissionRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final ApplicationSettingsRepository settingsRepository;
    private final ScorecardRepository scorecardRepository;

    @Transactional(readOnly = true)
    public String build(User user) {
        StringBuilder context = new StringBuilder()
                .append("Snapshot time: ").append(LocalDateTime.now().withNano(0)).append('\n')
                .append("Authenticated user: ").append(user.getFullName())
                .append("; role=").append(user.getRole()).append('\n');
        appendGlobalDeadline(context);

        switch (user.getRole()) {
            case COORDINATOR -> appendCoordinatorContext(context);
            case COMPANY -> appendProjectContext(context,
                    projectRepository.findByCreatedById(user.getId()));
            case PROJECT_SUPERVISOR -> appendProjectContext(context,
                    projectRepository.findByProjectSupervisorId(user.getId()));
            case PARTY_SUPERVISOR -> appendPartySupervisorContext(context, user);
            case STUDENT -> appendStudentContext(context, user);
        }
        return context.toString();
    }

    private void appendCoordinatorContext(StringBuilder context) {
        appendProjects(context, projectRepository.findAll());
        appendParties(context, partyRepository.findAll());
        List<User> allUsers = userRepository.findAll();
        Map<UserStatus, Long> users = allUsers.stream()
                .collect(Collectors.groupingBy(User::getStatus, LinkedHashMap::new, Collectors.counting()));
        context.append("User counts: total=").append(users.values().stream().mapToLong(Long::longValue).sum());
        Arrays.stream(UserStatus.values()).forEach(status ->
                context.append(", ").append(status).append('=').append(users.getOrDefault(status, 0L)));
        context.append('\n');
        Map<UserRole, Long> roles = allUsers.stream()
                .collect(Collectors.groupingBy(User::getRole, LinkedHashMap::new, Collectors.counting()));
        context.append("User role counts:");
        Arrays.stream(UserRole.values()).forEach(role ->
                context.append(' ').append(role).append('=').append(roles.getOrDefault(role, 0L)));
        context.append('\n');
        context.append("Coordinator user directory:\n");
        allUsers.stream()
                .sorted(Comparator.comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER))
                .limit(200)
                .forEach(account -> context.append("- user=").append(account.getFullName())
                        .append("; email=").append(account.getEmail())
                        .append("; role=").append(account.getRole())
                        .append("; status=").append(account.getStatus()).append('\n'));
        appendSubmissionCounts(context, submissionRepository.findAll());
    }

    private void appendProjectContext(StringBuilder context, List<Project> projects) {
        appendProjects(context, projects);
        appendSubmissionCounts(context, projects.stream()
                .flatMap(project -> submissionRepository.findByProjectId(project.getId()).stream())
                .toList());
    }

    private void appendPartySupervisorContext(StringBuilder context, User user) {
        List<Party> parties = partyRepository.findBySupervisorId(user.getId());
        context.append("My managed parties:\n");
        appendParties(context, parties);
        context.append("Browsable party directory:\n");
        appendParties(context, partyRepository.findAll());
        appendProjects(context, parties.stream()
                .map(Party::getAssignedProject)
                .filter(java.util.Objects::nonNull)
                .toList());
        appendSubmissionCounts(context, parties.stream()
                .map(party -> submissionRepository.findByPartyId(party.getId()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList());
    }

    private void appendStudentContext(StringBuilder context, User user) {
        List<Project> visibleProjects = new ArrayList<>(
                projectRepository.findByStatus(ProjectStatus.OPEN, Pageable.unpaged()).getContent());
        partyRepository.findByMemberId(user.getId()).ifPresentOrElse(party -> {
            Project assigned = party.getAssignedProject();
            if (assigned != null && visibleProjects.stream().noneMatch(project -> project.getId().equals(assigned.getId()))) {
                visibleProjects.add(assigned);
            }
            context.append("My party: ").append(party.getName())
                    .append("; status=").append(party.getStatus())
                    .append("; members=").append(party.getMembers().size())
                    .append("; partySupervisor=")
                    .append(party.getSupervisor() == null ? "none" : party.getSupervisor().getFullName())
                    .append('\n');
            var applications = applicationRepository.findByPartyIdOrderByRankPositionAsc(party.getId());
            context.append("My project applications: count=").append(applications.size()).append('\n');
            applications.stream().limit(20).forEach(application -> context
                    .append("- rank=").append(application.getRankPosition())
                    .append("; project=").append(application.getProject().getTitle())
                    .append("; status=").append(application.getStatus()).append('\n'));
            submissionRepository.findByPartyId(party.getId()).ifPresent(submission -> {
                context.append("My submission: status=").append(submission.getStatus())
                        .append("; project=").append(submission.getProject().getTitle());
                if (submission.getEvaluationReport() != null) {
                    context.append("; teamScore=").append(submission.getEvaluationReport().getTotalScore())
                            .append("; finalized=").append(submission.getEvaluationReport().isFinalized());
                }
                context.append('\n');
                if (submission.getEvaluationReport() != null) {
                    appendStudentEvaluation(context, submission.getEvaluationReport(), user);
                }
            });
        }, () -> context.append("My party: none\n"));
        scorecardRepository.findByStudentId(user.getId()).ifPresentOrElse(scorecard -> {
            context.append("My scorecard: averageScore=").append(scorecard.getAverageScore())
                    .append("; totalProjects=").append(scorecard.getTotalProjects()).append('\n');
            scorecard.getEntries().stream().limit(20).forEach(entry -> context
                    .append("- scorecardProject=").append(entry.getProject().getTitle())
                    .append("; score=").append(entry.getScore())
                    .append("; semester=").append(entry.getSemester())
                    .append("; academicYear=").append(entry.getAcademicYear()).append('\n'));
        }, () -> context.append("My scorecard: no evaluated projects yet\n"));
        appendProjects(context, visibleProjects);
    }

    private void appendStudentEvaluation(StringBuilder context, EvaluationReport report, User student) {
        context.append("My evaluation weighting: AI authenticity=20%, code quality=25%, functionality=25%, "
                + "scope alignment=20%, team collaboration=10%\n")
                .append("- AI authenticity: score=").append(report.getAiDetectionScore())
                .append("/100; reason=").append(report.getAiDetectionNotes()).append('\n')
                .append("- Code quality: score=").append(report.getCodeQualityScore())
                .append("/100; reason=").append(report.getCodeQualityNotes()).append('\n')
                .append("- Functionality: score=").append(report.getFunctionalityScore())
                .append("/100; reason=").append(report.getFunctionalityNotes()).append('\n')
                .append("- Scope alignment: score=").append(report.getScopeAlignmentScore())
                .append("/100; reason=").append(report.getScopeAlignmentNotes()).append('\n')
                .append("- Team collaboration: score=").append(report.getTeamCollaborationScore())
                .append("/100; reason=").append(report.getTeamCollaborationNotes()).append('\n')
                .append("- Team total: ").append(report.getTotalScore()).append("/100; summary=")
                .append(report.getOverallSummary()).append('\n');
        report.getStudentScores().stream()
                .filter(score -> score.getStudent().getId().equals(student.getId()))
                .findFirst()
                .ifPresent(score -> context.append("My individual evaluation: score=")
                        .append(score.getIndividualScore()).append("/100; commits=")
                        .append(score.getTotalCommits()).append("; contribution=")
                        .append(score.getContributionPercentage()).append("%; reason=")
                        .append(score.getPerformanceNotes()).append('\n'));
    }

    private void appendGlobalDeadline(StringBuilder context) {
        settingsRepository.findById(ApplicationSettings.ID).ifPresentOrElse(settings -> context
                        .append("Global deadline: enabled=").append(settings.isGlobalDeadlineEnabled())
                        .append("; deadline=").append(settings.getGlobalDeadline()).append('\n'),
                () -> context.append("Global deadline: not configured\n"));
    }

    private void appendProjects(StringBuilder context, List<Project> projects) {
        Map<ProjectStatus, Long> counts = projects.stream()
                .collect(Collectors.groupingBy(Project::getStatus, LinkedHashMap::new, Collectors.counting()));
        long finishedWorkflow = projects.stream().filter(this::isFinished).count();
        long unfinished = projects.stream().filter(project -> !isFinished(project)
                && project.getStatus() != ProjectStatus.ARCHIVED).count();
        long assignedInProgress = projects.stream().filter(project -> project.getStatus() == ProjectStatus.ASSIGNED
                && !isFinished(project)).count();
        long unassignedActive = projects.stream().filter(project -> project.getStatus() == ProjectStatus.OPEN
                || project.getStatus() == ProjectStatus.PENDING_REVIEW).count();
        context.append("Project counts: total=").append(projects.size())
                .append(", assigned=").append(counts.getOrDefault(ProjectStatus.ASSIGNED, 0L))
                .append(", assignedInProgress=").append(assignedInProgress)
                .append(", openAvailable=").append(counts.getOrDefault(ProjectStatus.OPEN, 0L))
                .append(", pendingReview=").append(counts.getOrDefault(ProjectStatus.PENDING_REVIEW, 0L))
                .append(", drafts=").append(counts.getOrDefault(ProjectStatus.DRAFT, 0L))
                .append(", closedFinished=").append(counts.getOrDefault(ProjectStatus.CLOSED, 0L))
                .append(", archived=").append(counts.getOrDefault(ProjectStatus.ARCHIVED, 0L))
                .append(", finishedWorkflow=").append(finishedWorkflow)
                .append(", unfinished=").append(unfinished)
                .append(", unassignedActive=").append(unassignedActive).append('\n');
        projects.stream()
                .sorted(Comparator.comparing(Project::getTitle, String.CASE_INSENSITIVE_ORDER))
                .limit(50)
                .forEach(project -> context.append("- project=").append(project.getTitle())
                        .append("; status=").append(project.getStatus())
                        .append("; applications=")
                        .append(projectRepository.countApplicationsByProjectId(project.getId()))
                        .append("; deadline=").append(project.getDeadline())
                        .append("; party=").append(project.getAssignedParty() == null
                                ? "none" : project.getAssignedParty().getName())
                        .append("; projectSupervisor=").append(project.getProjectSupervisor() == null
                                ? "none" : project.getProjectSupervisor().getFullName())
                        .append('\n'));
    }

    private boolean isFinished(Project project) {
        if (project.getStatus() == ProjectStatus.CLOSED) return true;
        Party party = project.getAssignedParty();
        if (party == null) return false;
        if (party.getStatus() == com.talentbridge.enums.PartyStatus.SUBMITTED
                || party.getStatus() == com.talentbridge.enums.PartyStatus.COMPLETED) return true;
        Submission submission = party.getSubmission();
        return submission != null && submission.getStatus() != SubmissionStatus.DRAFT;
    }

    private void appendParties(StringBuilder context, List<Party> parties) {
        context.append("Party counts: total=").append(parties.size()).append('\n');
        parties.stream().limit(50).forEach(party -> context.append("- party=").append(party.getName())
                .append("; status=").append(party.getStatus())
                .append("; members=").append(party.getMembers().size())
                .append("; project=").append(party.getAssignedProject() == null
                        ? "none" : party.getAssignedProject().getTitle())
                .append("; partySupervisor=").append(party.getSupervisor() == null
                        ? "none" : party.getSupervisor().getFullName())
                .append('\n'));
    }

    private void appendSubmissionCounts(StringBuilder context, List<Submission> submissions) {
        Map<SubmissionStatus, Long> counts = submissions.stream()
                .collect(Collectors.groupingBy(Submission::getStatus, LinkedHashMap::new, Collectors.counting()));
        context.append("Submission counts: total=").append(submissions.size());
        Arrays.stream(SubmissionStatus.values()).forEach(status ->
                context.append(", ").append(status).append('=').append(counts.getOrDefault(status, 0L)));
        context.append('\n');
        submissions.stream().limit(50).forEach(submission -> {
            context.append("- submissionProject=").append(submission.getProject().getTitle())
                    .append("; party=").append(submission.getParty().getName())
                    .append("; status=").append(submission.getStatus());
            if (submission.getEvaluationReport() != null) {
                context.append("; evaluationScore=").append(submission.getEvaluationReport().getTotalScore())
                        .append("; evaluationFinalized=").append(submission.getEvaluationReport().isFinalized())
                        .append("; evaluationSummary=").append(submission.getEvaluationReport().getOverallSummary());
            }
            context.append('\n');
        });
    }
}
