package com.talentbridge.repository;
import com.talentbridge.entity.Party;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface PartyRepository extends JpaRepository<Party, UUID> {
    String CHAT_FROM = " FROM Party p LEFT JOIN p.assignedProject project LEFT JOIN p.supervisor supervisor ";
    String CHAT_SCOPE = " WHERE (:directory = true OR :coordinator = true OR supervisor.id = :userId "
            + "OR project.projectSupervisor.id = :userId OR project.createdBy.id = :userId "
            + "OR EXISTS (SELECT m.id FROM Party memberParty JOIN memberParty.members m WHERE memberParty = p AND m.id = :userId)) ";
    interface ChatParty {
        UUID getId(); String getName(); com.talentbridge.enums.PartyStatus getStatus();
        long getMemberCount(); String getSupervisorName(); UUID getAssignedProjectId(); boolean getViewerIsLeader();
    }
    @Query("SELECT p.id AS id, p.name AS name, p.status AS status, SIZE(p.members) AS memberCount, "
            + "CASE WHEN supervisor.id IS NULL THEN NULL ELSE CONCAT(supervisor.firstName, ' ', supervisor.lastName) END AS supervisorName, "
            + "project.id AS assignedProjectId, CASE WHEN p.leader.id = :userId THEN true ELSE false END AS viewerIsLeader"
            + CHAT_FROM + CHAT_SCOPE + " ORDER BY p.createdAt DESC, p.id ASC")
    List<ChatParty> findChatParties(@Param("userId") UUID userId, @Param("coordinator") boolean coordinator,
                                    @Param("directory") boolean directory, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT p.status, COUNT(p)" + CHAT_FROM + CHAT_SCOPE + " GROUP BY p.status")
    List<Object[]> countChatParties(@Param("userId") UUID userId, @Param("coordinator") boolean coordinator,
                                    @Param("directory") boolean directory);

    long countByAssignedProjectIsNotNull();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT DISTINCT p FROM Party p LEFT JOIN FETCH p.members WHERE p.id = :id")
    Optional<Party> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Party p WHERE p.id IN :ids ORDER BY p.id")
    List<Party> findAllByIdForUpdate(@Param("ids") List<UUID> ids);

    @Query("SELECT p FROM Party p JOIN p.members m WHERE m.id = :userId")
    Optional<Party> findByMemberId(@Param("userId") UUID userId);
    List<Party> findBySupervisorId(UUID supervisorId);
    @Query("SELECT p FROM Party p WHERE p.assignedProject.projectSupervisor.id = :supervisorId")
    List<Party> findByProjectSupervisorId(@Param("supervisorId") UUID supervisorId);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Party p JOIN p.members m WHERE m.id = :studentId")
    boolean existsByMemberId(@Param("studentId") UUID studentId);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Party p JOIN p.members m WHERE m.id = :studentId AND p.supervisor.id = :supervisorId")
    boolean existsByStudentAndPartySupervisor(@Param("studentId") UUID studentId,
                                               @Param("supervisorId") UUID supervisorId);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END FROM Party p JOIN p.members m WHERE m.id = :studentId AND p.assignedProject.projectSupervisor.id = :supervisorId")
    boolean existsByStudentAndProjectSupervisor(@Param("studentId") UUID studentId,
                                                 @Param("supervisorId") UUID supervisorId);

    long countBySupervisorIdAndSemesterAndAcademicYearAndIdNot(
            UUID supervisorId, String semester, Integer academicYear, UUID partyId);
}
