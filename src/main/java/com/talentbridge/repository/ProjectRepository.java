package com.talentbridge.repository;

import com.talentbridge.entity.Project;
import com.talentbridge.enums.ProjectStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    @Query("SELECT p.status, COUNT(p) FROM Project p GROUP BY p.status")
    List<Object[]> countByStatusGrouped();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Project p WHERE p.id = :id")
    Optional<Project> findByIdForUpdate(@Param("id") UUID id);

    Page<Project> findByStatus(ProjectStatus status, Pageable pageable);
    List<Project> findByCreatedById(UUID userId);
    List<Project> findByProjectSupervisorId(UUID supervisorId);
    @Query("SELECT p FROM Project p WHERE p.status = 'OPEN' AND " +
            "(LOWER(p.title) LIKE LOWER(CONCAT('%',:q,'%')) OR " +
            "LOWER(p.description) LIKE LOWER(CONCAT('%',:q,'%')))")
    Page<Project> searchOpen(@Param("q") String q, Pageable pageable);

    @Query("SELECT COUNT(a) FROM Application a WHERE a.project.id = :id")
    long countApplicationsByProjectId(@Param("id") UUID id);
}
