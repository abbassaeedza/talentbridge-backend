package com.talentbridge.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.talentbridge.enums.QuestionnaireGradeState;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.*;

@Entity @Table(name = "questionnaire_assignments", uniqueConstraints =
        @UniqueConstraint(columnNames = {"snapshot_id", "student_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QuestionnaireAssignment extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    @JsonIgnore private QuestionnaireSnapshot snapshot;
    @Column(nullable = false, updatable = false) private UUID studentId;
    @Column(nullable = false, length = 255, updatable = false) private String studentName;
    @JsonIgnore @Column(length = 100, updatable = false) private String githubLogin;
    @Version private long revision;
    @OneToMany(mappedBy = "assignment", cascade = CascadeType.ALL) @OrderBy("position ASC")
    @Builder.Default @JsonIgnore private List<QuestionnaireQuestion> questions = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default @JsonIgnore private Map<String, String> answers = new LinkedHashMap<>();
    private Instant savedAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    @Builder.Default @JsonIgnore private QuestionnaireGradeState gradeState = QuestionnaireGradeState.PENDING;
    @JsonIgnore private Long gradedRevision;
    @JsonIgnore private Long lockedAnswerRevision;
    @JsonIgnore private Integer aiScore;
    @JsonIgnore private Integer effectiveScore;
}
