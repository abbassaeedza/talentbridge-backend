package com.talentbridge.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity @Table(name = "questionnaire_evidence")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuestionnaireEvidence extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "snapshot_id", nullable = false, unique = true, updatable = false)
    @JsonIgnore private QuestionnaireSnapshot snapshot;
    @Column(nullable = false, length = 500, updatable = false) private String repositoryUrl;
    @Column(nullable = false, length = 100, updatable = false) private String repositoryBranch;
    @Column(nullable = false, length = 40, updatable = false) private String commitSha;
    @Column(nullable = false, updatable = false) private Instant capturedAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    @JsonIgnore private JsonNode evidence;
}
