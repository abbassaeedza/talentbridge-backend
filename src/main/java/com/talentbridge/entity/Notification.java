package com.talentbridge.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.talentbridge.enums.NotificationType;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "notifications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Notification extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    @JsonIgnoreProperties({"studentProfile", "companyProfile", "password",
            "githubAccessToken", "emailVerificationToken",
            "hibernateLazyInitializer", "handler"})
    private User recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100)
    private NotificationType type;

    @Column(nullable = false, length = 255) private String title;
    @Column(columnDefinition = "TEXT")      private String message;
    @Column(nullable = false)               private boolean read = false;
    @Column(length = 255)                   private String referenceId;
    @Column(length = 100)                   private String referenceType;
}