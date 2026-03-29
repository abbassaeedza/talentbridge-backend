package com.talentbridge.entity;
import com.talentbridge.enums.SubmissionStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity @Table(name = "submissions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Submission extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY) @JoinColumn(name = "party_id", nullable = false) private Party party;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "project_id", nullable = false) private Project project;
    @Column(length = 500) private String repoUrl;
    @Column(length = 100) private String repoBranch;
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "submission_documents", joinColumns = @JoinColumn(name = "submission_id"))
    @Column(name = "document_url", length = 500)
    @Builder.Default private List<String> documentUrls = new ArrayList<>();
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50) private SubmissionStatus status;
    private LocalDateTime submittedAt;
    @Column(columnDefinition = "TEXT") private String notes;
    @OneToOne(mappedBy = "submission", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private EvaluationReport evaluationReport;
}
