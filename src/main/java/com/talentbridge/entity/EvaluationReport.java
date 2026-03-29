package com.talentbridge.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity @Table(name = "evaluation_reports")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EvaluationReport extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "submission_id", nullable = false) private Submission submission;
    @Column(columnDefinition = "NUMERIC") private Double aiDetectionScore;
    @Column(columnDefinition = "TEXT") private String aiDetectionNotes;
    @Column(columnDefinition = "NUMERIC") private Double codeQualityScore;
    @Column(columnDefinition = "TEXT") private String codeQualityNotes;
    @Column(columnDefinition = "NUMERIC") private Double functionalityScore;
    @Column(columnDefinition = "TEXT") private String functionalityNotes;
    @Column(columnDefinition = "NUMERIC") private Double scopeAlignmentScore;
    @Column(columnDefinition = "TEXT") private String scopeAlignmentNotes;
    @Column(columnDefinition = "NUMERIC") private Double teamCollaborationScore;
    @Column(columnDefinition = "TEXT") private String teamCollaborationNotes;
    @Column(columnDefinition = "NUMERIC") private Double totalScore;
    @Column(columnDefinition = "TEXT") private String overallSummary;
    @OneToMany(mappedBy = "evaluationReport", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default private List<StudentEvaluationScore> studentScores = new ArrayList<>();
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "triggered_by_id") private User triggeredBy;
    private LocalDateTime evaluatedAt;
    @Column(nullable = false) private boolean finalized = false;
}
