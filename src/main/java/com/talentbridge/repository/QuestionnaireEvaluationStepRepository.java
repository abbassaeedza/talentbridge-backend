package com.talentbridge.repository;
import com.talentbridge.entity.QuestionnaireEvaluationStep;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface QuestionnaireEvaluationStepRepository extends JpaRepository<QuestionnaireEvaluationStep,UUID> {
    List<QuestionnaireEvaluationStep> findAllByRunIdOrderByPosition(UUID runId);
    Optional<QuestionnaireEvaluationStep> findByJobId(UUID jobId);
}
