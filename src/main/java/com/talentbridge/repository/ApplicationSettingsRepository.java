package com.talentbridge.repository;

import com.talentbridge.entity.ApplicationSettings;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationSettingsRepository extends JpaRepository<ApplicationSettings, Short> {
}
