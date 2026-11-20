package com.talentbridge.entity;

import com.talentbridge.enums.QuestionnaireState;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity @Table(name = "questionnaire_snapshots", uniqueConstraints =
        @UniqueConstraint(columnNames = {"submission_id", "assessment_version"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuestionnaireSnapshot extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "submission_id", nullable = false, unique = true, updatable = false)
    @JsonIgnore private Submission submission;
    @Builder.Default @Column(nullable = false, updatable = false) private int assessmentVersion = 2;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private QuestionnaireState state;
    @JsonIgnore @Column(nullable = false, columnDefinition = "TEXT", updatable = false) private String frozenBrief;
    @OneToOne(mappedBy = "snapshot", fetch = FetchType.LAZY) @JsonIgnore private QuestionnaireEvidence evidence;
    private Instant opensAt;
    private Instant closesAt;
    @Version private long version;
}
