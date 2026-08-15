package com.talentbridge.repository;
import com.talentbridge.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface SubmissionRepository extends JpaRepository<Submission, UUID> {
    @Query("SELECT s.status, COUNT(s) FROM Submission s GROUP BY s.status")
    List<Object[]> countByStatusGrouped();

    Optional<Submission> findByPartyId(UUID partyId);
    List<Submission> findByProjectId(UUID projectId);
}
