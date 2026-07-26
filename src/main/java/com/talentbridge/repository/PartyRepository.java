package com.talentbridge.repository;
import com.talentbridge.entity.Party;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface PartyRepository extends JpaRepository<Party, UUID> {
    @Query("SELECT p FROM Party p JOIN p.members m WHERE m.id = :userId")
    Optional<Party> findByMemberId(@Param("userId") UUID userId);
    List<Party> findBySupervisorId(UUID supervisorId);
    @Query("SELECT COUNT(p) FROM Party p WHERE p.supervisor.id = :supId AND p.semester = :sem")
    long countBySupervisorAndSemester(@Param("supId") UUID supId, @Param("sem") String sem);
}
