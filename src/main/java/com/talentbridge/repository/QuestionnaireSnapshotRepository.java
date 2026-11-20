package com.talentbridge.repository;

import com.talentbridge.entity.QuestionnaireSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface QuestionnaireSnapshotRepository extends JpaRepository<QuestionnaireSnapshot, UUID> {
    Optional<QuestionnaireSnapshot> findBySubmissionIdAndAssessmentVersion(UUID submissionId, int assessmentVersion);
}
