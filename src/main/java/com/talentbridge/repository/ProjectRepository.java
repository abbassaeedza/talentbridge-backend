package com.talentbridge.repository;

import com.talentbridge.entity.Project;
import com.talentbridge.enums.ProjectStatus;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ProjectRepository extends JpaRepository<Project, UUID> {
    Page<Project> findByStatus(ProjectStatus status, Pageable pageable);
    List<Project> findByCreatedById(UUID userId);
    List<Project> findByCompanyId(UUID companyId);

    @Query("SELECT p FROM Project p WHERE p.status = 'OPEN' AND " +
            "(LOWER(p.title) LIKE LOWER(CONCAT('%',:q,'%')) OR " +
            "LOWER(p.description) LIKE LOWER(CONCAT('%',:q,'%')))")
    Page<Project> searchOpen(@Param("q") String q, Pageable pageable);

    @Query("SELECT COUNT(a) FROM Application a WHERE a.project.id = :id")
    long countApplicationsByProjectId(@Param("id") UUID id);
}