package com.talentbridge.repository;

import com.talentbridge.entity.SupervisorProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupervisorProfileRepository extends JpaRepository<SupervisorProfile, UUID> {
    Optional<SupervisorProfile> findByUserId(UUID userId);
    List<SupervisorProfile> findByCompanyId(UUID companyId);
}
