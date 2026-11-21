package com.talentbridge.repository;

import com.talentbridge.entity.QuestionnaireAssignment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;

public interface QuestionnaireAssignmentRepository extends JpaRepository<QuestionnaireAssignment, UUID> {
    List<QuestionnaireAssignment> findAllByStudentIdOrderByCreatedAtDesc(UUID studentId);
    Optional<QuestionnaireAssignment> findByIdAndStudentId(UUID id, UUID studentId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM QuestionnaireAssignment a WHERE a.id = :id AND a.studentId = :studentId")
    Optional<QuestionnaireAssignment> findOwnedForUpdate(@Param("id") UUID id, @Param("studentId") UUID studentId);
    List<QuestionnaireAssignment> findAllBySnapshotId(UUID snapshotId);
}
