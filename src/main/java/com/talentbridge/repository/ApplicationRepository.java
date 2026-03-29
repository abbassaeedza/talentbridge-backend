package com.talentbridge.repository;
import com.talentbridge.entity.Application;
import com.talentbridge.enums.ApplicationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
@Repository
public interface ApplicationRepository extends JpaRepository<Application, UUID> {
    List<Application> findByPartyIdOrderByRankPositionAsc(UUID partyId);
    List<Application> findByProjectIdAndStatus(UUID projectId, ApplicationStatus status);
    Optional<Application> findByPartyIdAndProjectId(UUID partyId, UUID projectId);
    boolean existsByPartyIdAndProjectId(UUID partyId, UUID projectId);
}
