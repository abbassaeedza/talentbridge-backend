package com.talentbridge.repository;
import com.talentbridge.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface SubmissionRepository extends JpaRepository<Submission, UUID> {
    Optional<Submission> findByPartyId(UUID partyId);
    List<Submission> findByProjectId(UUID projectId);
}
