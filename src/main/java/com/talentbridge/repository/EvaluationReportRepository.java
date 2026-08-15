package com.talentbridge.repository;
import com.talentbridge.entity.EvaluationReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface EvaluationReportRepository extends JpaRepository<EvaluationReport, UUID> {
    long countByFinalizedTrue();

    Optional<EvaluationReport> findBySubmissionId(UUID submissionId);
}
