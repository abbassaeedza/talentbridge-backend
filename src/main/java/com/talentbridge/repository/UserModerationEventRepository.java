package com.talentbridge.repository;

import com.talentbridge.entity.UserModerationEvent;
import com.talentbridge.enums.ModerationEventType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserModerationEventRepository extends JpaRepository<UserModerationEvent, UUID> {
    long countByNormalizedEmailAndEventType(String normalizedEmail, ModerationEventType eventType);
}
