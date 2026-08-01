package com.talentbridge.entity;

import com.talentbridge.enums.ModerationEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "user_moderation_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserModerationEvent extends BaseEntity {
    @Column(name = "normalized_email", nullable = false, length = 255)
    private String normalizedEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private ModerationEventType eventType;

    @Column(name = "coordinator_id")
    private UUID coordinatorId;
}
