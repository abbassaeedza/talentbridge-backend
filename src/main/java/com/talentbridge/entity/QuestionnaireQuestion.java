package com.talentbridge.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "questionnaire_questions", uniqueConstraints =
        @UniqueConstraint(columnNames = {"assignment_id", "position"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuestionnaireQuestion extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "assignment_id", nullable = false, updatable = false)
    @JsonIgnore private QuestionnaireAssignment assignment;
    @Column(nullable = false, updatable = false) private int position;
    @Column(nullable = false, columnDefinition = "TEXT", updatable = false) private String prompt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    private JsonNode sourceReferences;
    @JsonIgnore @Column(nullable = false, columnDefinition = "TEXT", updatable = false) private String hiddenRubric;
    @JsonIgnore private Integer aiScore;
    @JsonIgnore private Integer effectiveScore;
    @JsonIgnore @Column(columnDefinition = "TEXT") private String gradingNotes;
}
