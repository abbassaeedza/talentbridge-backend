package com.talentbridge.repository;

import com.talentbridge.entity.ApplicationSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface ApplicationSettingsRepository extends JpaRepository<ApplicationSettings, Short> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT settings FROM ApplicationSettings settings WHERE settings.id = :id")
    Optional<ApplicationSettings> findByIdForUpdate(@Param("id") Short id);
}
