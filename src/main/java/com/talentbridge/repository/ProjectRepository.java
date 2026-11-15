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
    String CHAT_FROM = " FROM Project p LEFT JOIN p.assignedParty party LEFT JOIN party.submission submission LEFT JOIN p.company company ";
    String CHAT_RELATED = "(:coordinator = true OR p.createdBy.id = :userId OR p.projectSupervisor.id = :userId "
            + "OR party.supervisor.id = :userId OR EXISTS (SELECT m.id FROM Party memberParty JOIN memberParty.members m "
            + "WHERE memberParty.id = party.id AND m.id = :userId))";
    String CHAT_SCOPE = " WHERE ((:openOnly = true AND p.status = 'OPEN') OR (:openOnly = false AND " + CHAT_RELATED + ")) ";
    String CHAT_FINISHED = "(p.status = 'CLOSED' OR party.status IN ('SUBMITTED','COMPLETED') "
            + "OR submission.status IN ('SUBMITTED','UNDER_EVALUATION','EVALUATED'))";
    String CHAT_SELECT = "SELECT p.id AS id, p.title AS title, p.status AS status, "
            + "COALESCE(company.companyName, 'TalentBridge') AS companyName, p.deadline AS deadline, "
            + "CASE WHEN " + CHAT_FINISHED + " THEN true ELSE false END AS finished ";
    String CHAT_UPCOMING = " WHERE (p.status = 'OPEN' OR " + CHAT_RELATED + ") "
            + "AND p.status IN ('PENDING_REVIEW','OPEN','ASSIGNED') AND p.deadline > :now "
            + "AND p.status <> 'CLOSED' AND (party.status IS NULL OR party.status NOT IN ('SUBMITTED','COMPLETED')) "
            + "AND (submission.status IS NULL OR submission.status = 'DRAFT') ";

    interface ChatProject {
        UUID getId(); String getTitle(); ProjectStatus getStatus(); String getCompanyName();
        java.time.LocalDateTime getDeadline(); boolean getFinished();
    }
    interface ChatBrief extends ChatProject {
        String getDescription(); String getScope(); String getDeliverables();
        String getEvaluationCriteria(); String getProjectField();
    }
    @Query(CHAT_SELECT + CHAT_FROM + CHAT_SCOPE + " ORDER BY p.createdAt DESC, p.id ASC")
    List<ChatProject> findChatProjects(@Param("userId") UUID userId, @Param("coordinator") boolean coordinator,
                                       @Param("openOnly") boolean openOnly, Pageable pageable);

    @Query("SELECT p.status, COUNT(p)" + CHAT_FROM + CHAT_SCOPE + " GROUP BY p.status")
    List<Object[]> countChatProjects(@Param("userId") UUID userId, @Param("coordinator") boolean coordinator,
                                      @Param("openOnly") boolean openOnly);

    @Query(CHAT_SELECT + ", SUBSTRING(p.description,1,1501) AS description, SUBSTRING(p.scope,1,1501) AS scope, "
            + "SUBSTRING(p.deliverables,1,1501) AS deliverables, SUBSTRING(p.evaluationCriteria,1,1501) AS evaluationCriteria, "
            + "p.projectField AS projectField" + CHAT_FROM
            + " WHERE EXISTS (SELECT m.id FROM Party ownParty JOIN ownParty.members m WHERE ownParty.id = party.id AND m.id = :userId)")
    Optional<ChatBrief> findChatAssignedBrief(@Param("userId") UUID userId);

    @Query("SELECT tool FROM Project p JOIN p.tools tool WHERE p.id = :projectId ORDER BY tool ASC")
    List<String> findChatTools(@Param("projectId") UUID projectId, Pageable pageable);

    @Query(CHAT_SELECT + CHAT_FROM + CHAT_UPCOMING + " ORDER BY p.deadline ASC, p.id ASC")
    List<ChatProject> findChatUpcoming(@Param("userId") UUID userId, @Param("coordinator") boolean coordinator,
                                      @Param("now") java.time.LocalDateTime now, Pageable pageable);

    @Query("SELECT COUNT(p)" + CHAT_FROM + CHAT_UPCOMING)
    long countChatUpcoming(@Param("userId") UUID userId, @Param("coordinator") boolean coordinator,
                           @Param("now") java.time.LocalDateTime now);

    @Query("SELECT a.project.id, COUNT(a) FROM Application a WHERE a.project.id IN :ids GROUP BY a.project.id")
    List<Object[]> countChatApplications(@Param("ids") List<UUID> ids);

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
