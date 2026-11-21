package com.talentbridge.repository;

import com.talentbridge.entity.QuestionnaireSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface QuestionnaireSnapshotRepository extends JpaRepository<QuestionnaireSnapshot, UUID> {
    @Query("SELECT s.id FROM QuestionnaireSnapshot s WHERE s.submission.id = :id AND s.assessmentVersion = 2")
    Optional<UUID> idBySubmission(@Param("id") UUID id);
    Optional<QuestionnaireSnapshot> findBySubmissionIdAndAssessmentVersion(UUID submissionId, int assessmentVersion);
}
