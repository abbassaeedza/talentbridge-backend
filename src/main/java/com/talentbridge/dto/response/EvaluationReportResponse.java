package com.talentbridge.dto.response;

import com.talentbridge.entity.EvaluationReport;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationReportResponse {
    private UUID id;
    private UUID submissionId;
    private Double aiDetectionScore;
    private String aiDetectionNotes;
    private Double codeQualityScore;
    private String codeQualityNotes;
    private Double functionalityScore;
    private String functionalityNotes;
    private Double scopeAlignmentScore;
    private String scopeAlignmentNotes;
    private Double teamCollaborationScore;
    private String teamCollaborationNotes;
    private Double totalScore;
    private String overallSummary;
    private List<StudentScoreResponse> studentScores;
    private LocalDateTime evaluatedAt;
    private boolean finalized;

    public static EvaluationReportResponse from(EvaluationReport report) {
        return EvaluationReportResponse.builder()
                .id(report.getId())
                .submissionId(report.getSubmission() != null ? report.getSubmission().getId() : null)
                .aiDetectionScore(report.getAiDetectionScore())
                .aiDetectionNotes(report.getAiDetectionNotes())
                .codeQualityScore(report.getCodeQualityScore())
                .codeQualityNotes(report.getCodeQualityNotes())
                .functionalityScore(report.getFunctionalityScore())
                .functionalityNotes(report.getFunctionalityNotes())
                .scopeAlignmentScore(report.getScopeAlignmentScore())
                .scopeAlignmentNotes(report.getScopeAlignmentNotes())
                .teamCollaborationScore(report.getTeamCollaborationScore())
                .teamCollaborationNotes(report.getTeamCollaborationNotes())
                .totalScore(report.getTotalScore())
                .overallSummary(report.getOverallSummary())
                .studentScores(report.getStudentScores().stream()
                        .map(score -> StudentScoreResponse.builder()
                                .id(score.getId())
                                .student(UserSummary.builder()
                                        .id(score.getStudent().getId())
                                        .firstName(score.getStudent().getFirstName())
                                        .lastName(score.getStudent().getLastName())
                                        .email(score.getStudent().getEmail())
                                        .githubUsername(score.getStudent().getGithubUsername())
                                        .build())
                                .totalCommits(score.getTotalCommits())
                                .contributionPercentage(score.getContributionPercentage())
                                .individualScore(score.getIndividualScore())
                                .performanceNotes(score.getPerformanceNotes())
                                .build())
                        .toList())
                .evaluatedAt(report.getEvaluatedAt())
                .finalized(report.isFinalized())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StudentScoreResponse {
        private UUID id;
        private UserSummary student;
        private Integer totalCommits;
        private Double contributionPercentage;
        private Double individualScore;
        private String performanceNotes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserSummary {
        private UUID id;
        private String firstName;
        private String lastName;
        private String email;
        private String githubUsername;
    }
}
