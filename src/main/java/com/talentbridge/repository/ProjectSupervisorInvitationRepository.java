package com.talentbridge.repository;

import com.talentbridge.entity.ProjectSupervisorInvitation;
import com.talentbridge.enums.ProjectSupervisorInvitationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectSupervisorInvitationRepository extends JpaRepository<ProjectSupervisorInvitation, UUID> {
    List<ProjectSupervisorInvitation> findByCompanyIdOrderByCreatedAtDesc(UUID companyId);
    List<ProjectSupervisorInvitation> findByEmailAndStatus(String email, ProjectSupervisorInvitationStatus status);
    Optional<ProjectSupervisorInvitation> findByTokenHash(String tokenHash);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ProjectSupervisorInvitation i where i.tokenHash = :tokenHash")
    Optional<ProjectSupervisorInvitation> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}
