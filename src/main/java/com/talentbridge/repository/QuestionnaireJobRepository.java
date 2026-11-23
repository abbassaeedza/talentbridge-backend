package com.talentbridge.repository;

import com.talentbridge.entity.QuestionnaireJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface QuestionnaireJobRepository extends JpaRepository<QuestionnaireJob, UUID> {
    Optional<QuestionnaireJob> findByJobKey(String jobKey);
    List<QuestionnaireJob> findAllBySnapshotIdOrderByCreatedAtAsc(UUID snapshotId);
    @Query("SELECT j.snapshot.id FROM QuestionnaireJob j WHERE j.id = :id")
    Optional<UUID> snapshotId(@Param("id") UUID id);
    @Query("SELECT j FROM QuestionnaireJob j JOIN FETCH j.snapshot WHERE j.id = :id")
    Optional<QuestionnaireJob> findWithSnapshotById(@Param("id") UUID id);
}
