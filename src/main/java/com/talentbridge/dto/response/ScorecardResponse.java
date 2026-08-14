package com.talentbridge.dto.response;

import com.talentbridge.entity.Scorecard;
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
public class ScorecardResponse {
    private UUID id;
    private UUID studentId;
    private List<EntryResponse> entries;
    private Double averageScore;
    private Integer totalProjects;

    public static ScorecardResponse from(Scorecard scorecard) {
        if (scorecard == null) return null;
        return ScorecardResponse.builder()
                .id(scorecard.getId())
                .studentId(scorecard.getStudent().getId())
                .entries(scorecard.getEntries().stream()
                        .map(entry -> EntryResponse.builder()
                                .id(entry.getId())
                                .project(ProjectSummary.builder()
                                        .id(entry.getProject().getId())
                                        .title(entry.getProject().getTitle())
                                        .build())
                                .score(entry.getScore())
                                .semester(entry.getSemester())
                                .academicYear(entry.getAcademicYear())
                                .createdAt(entry.getCreatedAt())
                                .evaluationReport(entry.getEvaluationReport() == null
                                        ? null
                                        : EvaluationReportResponse.from(entry.getEvaluationReport()))
                                .build())
                        .toList())
                .averageScore(scorecard.getAverageScore())
                .totalProjects(scorecard.getTotalProjects())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntryResponse {
        private UUID id;
        private ProjectSummary project;
        private Double score;
        private String semester;
        private Integer academicYear;
        private LocalDateTime createdAt;
        private EvaluationReportResponse evaluationReport;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProjectSummary {
        private UUID id;
        private String title;
    }
}
