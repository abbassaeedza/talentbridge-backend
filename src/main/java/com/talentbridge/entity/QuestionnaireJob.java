package com.talentbridge.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.talentbridge.enums.QuestionnaireJobKind;
import com.talentbridge.enums.QuestionnaireJobStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "questionnaire_jobs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuestionnaireJob extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    @JsonIgnore private QuestionnaireSnapshot snapshot;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "assignment_id", updatable = false)
    @JsonIgnore private QuestionnaireAssignment assignment;
    @Column(nullable = false, unique = true, length = 200, updatable = false) private String jobKey;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private QuestionnaireJobKind kind;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    @Builder.Default private QuestionnaireJobStatus status = QuestionnaireJobStatus.PENDING;
    private int attempts;
    private int dispatchGeneration;
    @Column(updatable = false) private UUID retryOf;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default @JsonIgnore private List<UUID> questionIds = List.of();
    private UUID leaseToken;
    private Instant leaseUntil;
    private Instant nextAttemptAt;
    @Column(length = 100) private String lastErrorCode;
    @Version private long version;
}
