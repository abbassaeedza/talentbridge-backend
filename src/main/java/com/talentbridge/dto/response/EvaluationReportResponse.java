package com.talentbridge.dto.response;

import com.talentbridge.entity.EvaluationReport;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationReportResponse {
    private UUID id;
    private UUID submissionId;
    private int assessmentVersion;
    private Double repositoryScore;
    private Double questionnaireScore;
    private Map<String,Double> effectiveWeights;
    private ContributorCoverage contributorCoverage;
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
        boolean v2=report.getSubmission()!=null && report.getSubmission().getAssessmentVersion()==2;
        var response=EvaluationReportResponse.builder()
                .id(report.getId())
                .submissionId(report.getSubmission() != null ? report.getSubmission().getId() : null)
                .assessmentVersion(v2?2:1)
                .repositoryScore(v2?display(report.getRepositoryScore()):null)
                .questionnaireScore(v2?display(report.getQuestionnaireScore()):null)
                .effectiveWeights(v2?Map.of("authenticity",15.0,"quality",18.75,"functionality",18.75,"scope",15.0,"collaboration",7.5,"questionnaire",25.0):null)
                .contributorCoverage(v2?new ContributorCoverage(report.getContributorCapturedAt(),100,Boolean.TRUE.equals(report.getContributorsTruncated()),false):null)
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
                                        .firstName(v2?score.getFrozenStudentName():score.getStudent().getFirstName())
                                        .lastName(v2?"":score.getStudent().getLastName())
                                        .email(v2?null:score.getStudent().getEmail())
                                        .githubUsername(v2?score.getFrozenGithubLogin():score.getStudent().getGithubUsername())
                                        .build())
                                .totalCommits(score.getTotalCommits())
                                .contributionPercentage(v2?display(score.getContributionPercentage()):score.getContributionPercentage())
                                .individualScore(v2?display(score.getIndividualScore()):score.getIndividualScore())
                                .performanceNotes(score.getPerformanceNotes())
                                .build())
                        .toList())
                .evaluatedAt(report.getEvaluatedAt())
                .finalized(report.isFinalized())
                .build();
        if(v2) {
            response.setAiDetectionScore(display(response.getAiDetectionScore())); response.setCodeQualityScore(display(response.getCodeQualityScore()));
            response.setFunctionalityScore(display(response.getFunctionalityScore()));response.setScopeAlignmentScore(display(response.getScopeAlignmentScore()));
            response.setTeamCollaborationScore(display(response.getTeamCollaborationScore()));response.setTotalScore(display(response.getTotalScore()));
        }
        return response;
    }
    private static Double display(Double value){return value==null?null:Math.round(value*10.0)/10.0;}
    public record ContributorCoverage(Instant capturedAt,int limit,boolean truncated,boolean commitPinned){}

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
