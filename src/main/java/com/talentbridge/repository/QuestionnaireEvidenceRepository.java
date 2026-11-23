package com.talentbridge.repository;

import com.talentbridge.entity.QuestionnaireEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface QuestionnaireEvidenceRepository extends JpaRepository<QuestionnaireEvidence, UUID> {
    Optional<QuestionnaireEvidence> findBySnapshotId(UUID snapshotId);
    boolean existsBySnapshotId(UUID snapshotId);
}
