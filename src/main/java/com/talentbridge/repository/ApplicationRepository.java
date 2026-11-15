package com.talentbridge.repository;
import com.talentbridge.entity.Application;
import com.talentbridge.enums.ApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface ApplicationRepository extends JpaRepository<Application, UUID> {
    String CHAT_FROM = " FROM Application a JOIN a.party party JOIN a.project project LEFT JOIN party.assignedProject assigned ";
    String CHAT_SCOPE = " WHERE (:coordinator = true OR party.supervisor.id = :userId "
            + "OR EXISTS (SELECT m.id FROM Party memberParty JOIN memberParty.members m WHERE memberParty = party AND m.id = :userId) "
            + "OR (:projectSupervisor = true AND (assigned.projectSupervisor.id = :userId OR project.projectSupervisor.id = :userId)) "
            + "OR (:company = true AND project.createdBy.id = :userId)) ";
    interface ChatApplication {
        UUID getId(); UUID getPartyId(); String getPartyName(); UUID getProjectId(); String getProjectTitle();
        Integer getRankPosition(); ApplicationStatus getStatus();
    }
    @Query("SELECT a.id AS id, party.id AS partyId, party.name AS partyName, project.id AS projectId, "
            + "project.title AS projectTitle, a.rankPosition AS rankPosition, a.status AS status" + CHAT_FROM + CHAT_SCOPE
            + " ORDER BY a.createdAt DESC, a.id ASC")
    List<ChatApplication> findChatApplications(@Param("userId") UUID userId, @Param("coordinator") boolean coordinator,
            @Param("company") boolean company, @Param("projectSupervisor") boolean projectSupervisor,
            org.springframework.data.domain.Pageable pageable);

    @Query("SELECT a.status, COUNT(a)" + CHAT_FROM + CHAT_SCOPE + " GROUP BY a.status")
    List<Object[]> countChatApplications(@Param("userId") UUID userId, @Param("coordinator") boolean coordinator,
            @Param("company") boolean company, @Param("projectSupervisor") boolean projectSupervisor);

    List<Application> findByPartyIdOrderByRankPositionAsc(UUID partyId);
    List<Application> findByProjectIdAndStatus(UUID projectId, ApplicationStatus status);
    List<Application> findByProjectIdAndStatusIn(UUID projectId, List<ApplicationStatus> statuses);
    List<Application> findByProjectIdOrderByCreatedAtAsc(UUID projectId);
    Optional<Application> findByPartyIdAndProjectId(UUID partyId, UUID projectId);
    boolean existsByPartyIdAndProjectId(UUID partyId, UUID projectId);
    @Query("SELECT CASE WHEN COUNT(a) > 0 THEN true ELSE false END FROM Application a JOIN a.party.members m WHERE a.project.createdBy.id = :companyId AND m.id = :studentId")
    boolean existsForCompanyAndStudent(@Param("companyId") UUID companyId,
                                       @Param("studentId") UUID studentId);
}
