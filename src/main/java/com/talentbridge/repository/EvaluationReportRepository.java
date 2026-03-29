package com.talentbridge.repository;
import com.talentbridge.entity.EvaluationReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;
@Repository
public interface EvaluationReportRepository extends JpaRepository<EvaluationReport, UUID> {
    Optional<EvaluationReport> findBySubmissionId(UUID submissionId);
}
