package com.talentbridge.repository;
import com.talentbridge.entity.Scorecard;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface ScorecardRepository extends JpaRepository<Scorecard, UUID> {
    interface ChatScorecard { Double getAverageScore(); Integer getTotalProjects(); }
    interface ChatEntry {
        UUID getId(); UUID getProjectId(); String getProjectTitle(); Double getScore();
        String getSemester(); Integer getAcademicYear();
    }
    @org.springframework.data.jpa.repository.Query("SELECT s.averageScore AS averageScore, s.totalProjects AS totalProjects "
            + "FROM Scorecard s WHERE s.student.id = :userId")
    Optional<ChatScorecard> findChatScorecard(@org.springframework.data.repository.query.Param("userId") UUID userId);

    @org.springframework.data.jpa.repository.Query("SELECT e.id AS id, e.project.id AS projectId, e.project.title AS projectTitle, "
            + "e.score AS score, e.semester AS semester, e.academicYear AS academicYear FROM ScorecardEntry e "
            + "WHERE e.scorecard.student.id = :userId ORDER BY e.createdAt DESC, e.id ASC")
    java.util.List<ChatEntry> findChatEntries(@org.springframework.data.repository.query.Param("userId") UUID userId,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT COUNT(e) FROM ScorecardEntry e WHERE e.scorecard.student.id = :userId")
    long countChatEntries(@org.springframework.data.repository.query.Param("userId") UUID userId);

    Optional<Scorecard> findByStudentId(UUID studentId);
}
