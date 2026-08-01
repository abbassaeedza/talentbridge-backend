package com.talentbridge.repository;

import com.talentbridge.entity.NotificationPreference;
import com.talentbridge.enums.NotificationType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {
    Optional<NotificationPreference> findByUserIdAndType(UUID userId, NotificationType type);
    List<NotificationPreference> findByUserId(UUID userId);
    void deleteByUserId(UUID userId);
}
