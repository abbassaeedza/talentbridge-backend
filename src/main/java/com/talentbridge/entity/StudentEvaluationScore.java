package com.talentbridge.entity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "student_evaluation_scores")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StudentEvaluationScore extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "evaluation_report_id", nullable = false) private EvaluationReport evaluationReport;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "student_id", nullable = false) private User student;
    private Integer totalCommits;
    private Integer linesAdded;
    private Integer linesDeleted;
    @Column(columnDefinition = "NUMERIC") private Double contributionPercentage;
    @Column(columnDefinition = "NUMERIC") private Double individualScore;
    @Column(columnDefinition = "TEXT") private String performanceNotes;
}
