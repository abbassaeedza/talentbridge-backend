package com.talentbridge.entity;
import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Entity @Table(name = "scorecards")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Scorecard extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "student_id", nullable = false) private User student;
    @OneToMany(mappedBy = "scorecard", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default private List<ScorecardEntry> entries = new ArrayList<>();
    @Column(columnDefinition = "NUMERIC") private Double averageScore;
    private Integer totalProjects;
}
