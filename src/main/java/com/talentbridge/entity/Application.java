package com.talentbridge.entity;
import com.talentbridge.enums.ApplicationStatus;
import jakarta.persistence.*;
import lombok.*;

@Entity @Table(name = "applications",
    uniqueConstraints = @UniqueConstraint(name = "uk_application_party_project", columnNames = {"party_id","project_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Application extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "party_id", nullable = false) private Party party;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "project_id", nullable = false) private Project project;
    @Column(nullable = false) private Integer rankPosition;
    @Column(columnDefinition = "TEXT") private String proposalText;
    @Column(length = 500) private String proposalFileUrl;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 50) private ApplicationStatus status;
    @Column(columnDefinition = "TEXT") private String coordinatorNotes;
}
