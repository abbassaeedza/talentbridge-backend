package com.talentbridge.repository;
import com.talentbridge.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
@Repository
public interface SubmissionRepository extends JpaRepository<Submission, UUID> {
    Optional<Submission> findByPartyId(UUID partyId);
    List<Submission> findByProjectId(UUID projectId);
}
