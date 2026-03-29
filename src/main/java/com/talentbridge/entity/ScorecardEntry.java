package com.talentbridge.entity;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "scorecard_entries")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ScorecardEntry extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "scorecard_id", nullable = false) private Scorecard scorecard;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "project_id", nullable = false) private Project project;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "evaluation_report_id") private EvaluationReport evaluationReport;
    @Column(columnDefinition = "NUMERIC") private Double score;
    private String semester;
    private Integer academicYear;
}
