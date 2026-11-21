package com.talentbridge.repository;
import com.talentbridge.entity.QuestionnaireEvaluationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface QuestionnaireEvaluationRunRepository extends JpaRepository<QuestionnaireEvaluationRun,UUID> {
    Optional<QuestionnaireEvaluationRun> findFirstBySnapshotIdOrderBySequenceDesc(UUID snapshotId);
}
