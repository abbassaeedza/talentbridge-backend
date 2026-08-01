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
