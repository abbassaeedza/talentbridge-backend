package com.talentbridge.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.type.SqlTypes;

@Entity @Table(name="questionnaire_evaluation_steps")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuestionnaireEvaluationStep extends BaseEntity {
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="run_id",nullable=false,updatable=false)
    @JsonIgnore private QuestionnaireEvaluationRun run;
    @Column(nullable=false,updatable=false) private int position;
    @Column(nullable=false,length=30,updatable=false) private String kind;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable=false,columnDefinition="jsonb",updatable=false)
    @JsonIgnore private JsonNode members;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb")
    @ColumnTransformer(write="NULLIF(?::jsonb, 'null'::jsonb)") @JsonIgnore private JsonNode result;
    @OneToOne(fetch=FetchType.LAZY) @JoinColumn(name="job_id",nullable=false,unique=true)
    @JsonIgnore private QuestionnaireJob job;
}
