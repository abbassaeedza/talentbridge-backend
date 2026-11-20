package com.talentbridge.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "questionnaire_mark_adjustments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuestionnaireMarkAdjustment extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "question_id", nullable = false, updatable = false)
    @JsonIgnore private QuestionnaireQuestion question;
    @Column(nullable = false, updatable = false) private UUID actorId;
    @Column(nullable = false, updatable = false) private int previousScore;
    @Column(nullable = false, updatable = false) private int adjustedScore;
    @Column(nullable = false, length = 2000, updatable = false) private String reason;
    @Column(nullable = false, updatable = false) private Instant adjustedAt;
}
