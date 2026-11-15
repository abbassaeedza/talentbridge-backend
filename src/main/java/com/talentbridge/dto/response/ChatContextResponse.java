package com.talentbridge.dto.response;

import com.talentbridge.enums.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ChatContextResponse(
        Viewer viewer, Instant snapshotTimeUTC, GlobalDeadline globalDeadline,
        SummarySet<ProjectSummary> openProjects, SummarySet<ProjectSummary> projects,
        SummarySet<PartySummary> parties, SummarySet<PartySummary> partyDirectory,
        SummarySet<ApplicationSummary> applications, SummarySet<SubmissionSummary> submissions,
        SummarySet<EvaluationSummary> evaluations, Counts counts, ProjectBrief myAssignedProject,
        MyScorecard myScorecard, SummarySet<DeadlineSummary> upcomingDeadlines) {

    public record SummarySet<T>(List<T> items, long total, boolean truncated) {
        public SummarySet(List<T> items, long total) { this(List.copyOf(items), total, total > items.size()); }
    }
    public record Viewer(UUID id, UserRole role, String name) {}
    public record GlobalDeadline(boolean enabled, Instant deadlineUTC) {}
    public record ProjectSummary(UUID id, String title, ProjectStatus status, String companyName,
                                 Instant deadlineUTC, boolean finished, long partyApplicationCount) {}
    public record PartySummary(UUID id, String name, PartyStatus status, long memberCount,
                               String supervisorName, UUID assignedProjectId, boolean viewerIsLeader) {}
    public record ApplicationSummary(UUID id, UUID partyId, String partyName, UUID projectId,
                                     String projectTitle, Integer rankPosition, ApplicationStatus status) {}
    public record SubmissionSummary(UUID id, UUID partyId, String partyName, UUID projectId,
                                    String projectTitle, SubmissionStatus status, Instant submittedAtUTC) {}
    public record EvaluationSummary(UUID id, UUID submissionId, UUID projectId, boolean finalized,
                                    Instant evaluatedAtUTC, Double totalScore, Double aiDetectionScore,
                                    Double codeQualityScore, Double functionalityScore, Double scopeAlignmentScore,
                                    Double teamCollaborationScore, Double myIndividualScore,
                                    Double myContributionPercentage, Integer myTotalCommits) {}
    public record Counts(Map<ProjectStatus, Long> projectsByStatus, Map<PartyStatus, Long> partiesByStatus,
                         Map<ApplicationStatus, Long> applicationsByStatus,
                         Map<SubmissionStatus, Long> submissionsByStatus,
                         long finalizedEvaluations, UserCounts users) {}
    public record UserCounts(long total, Map<UserRole, Long> byRole, Map<UserStatus, Long> byStatus) {}
    public record ProjectBrief(UUID id, String title, ProjectStatus status, String companyName,
                               Instant deadlineUTC, boolean finished, long partyApplicationCount,
                               String description, String scope, String deliverables, String evaluationCriteria,
                               List<String> tools, String projectField, List<String> truncatedFields) {}
    public record MyScorecard(Double averageScore, int totalProjects, SummarySet<ScorecardEntrySummary> entries) {}
    public record ScorecardEntrySummary(UUID id, UUID projectId, String projectTitle, Double score,
                                       String semester, Integer academicYear) {}
    public record DeadlineSummary(UUID projectId, String title, Instant deadlineUTC) {}
}
