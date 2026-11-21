package com.talentbridge.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

@Entity @Table(name="questionnaire_evaluation_runs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuestionnaireEvaluationRun extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="snapshot_id",nullable=false,updatable=false)
    @JsonIgnore private QuestionnaireSnapshot snapshot;
    @Column(nullable=false,updatable=false) private int sequence;
    @Column(nullable=false,updatable=false) private UUID triggeredById;
    @Column(nullable=false,length=30) private String status;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb",updatable=false)
    @JsonIgnore private JsonNode lockedMembers;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb",updatable=false)
    @JsonIgnore private JsonNode incompleteMembers;
}
