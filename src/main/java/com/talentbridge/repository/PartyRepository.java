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
