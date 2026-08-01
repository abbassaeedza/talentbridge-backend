package com.talentbridge.entity;

import com.talentbridge.enums.ProjectSupervisorInvitationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "project_supervisor_invitations", indexes = @Index(name = "idx_project_supervisor_invitation_token", columnList = "token_hash", unique = true))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProjectSupervisorInvitation extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private CompanyProfile company;

    @Column(name = "email", nullable = false, length = 255)
    private String email;
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectSupervisorInvitationStatus status;
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;
}
