package com.talentbridge.repository;
import com.talentbridge.entity.Scorecard;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface ScorecardRepository extends JpaRepository<Scorecard, UUID> {
    Optional<Scorecard> findByStudentId(UUID studentId);
}
