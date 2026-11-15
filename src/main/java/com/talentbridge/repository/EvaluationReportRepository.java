package com.talentbridge.repository;
import com.talentbridge.entity.EvaluationReport;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface EvaluationReportRepository extends JpaRepository<EvaluationReport, UUID> {
    String CHAT_FROM = " FROM EvaluationReport e JOIN e.submission s JOIN s.party party JOIN s.project project ";
    String CHAT_SCOPE = " WHERE " + SubmissionRepository.CHAT_INDIVIDUAL_SCOPE;
    interface ChatEvaluation {
        UUID getId(); UUID getSubmissionId(); UUID getProjectId(); boolean getFinalized();
        java.time.LocalDateTime getEvaluatedAt(); Double getTotalScore(); Double getAiDetectionScore();
        Double getCodeQualityScore(); Double getFunctionalityScore(); Double getScopeAlignmentScore();
        Double getTeamCollaborationScore(); Double getMyIndividualScore();
        Double getMyContributionPercentage(); Integer getMyTotalCommits();
    }
    @org.springframework.data.jpa.repository.Query("SELECT e.id AS id, s.id AS submissionId, project.id AS projectId, "
            + "e.finalized AS finalized, e.evaluatedAt AS evaluatedAt, e.totalScore AS totalScore, "
            + "e.aiDetectionScore AS aiDetectionScore, e.codeQualityScore AS codeQualityScore, "
            + "e.functionalityScore AS functionalityScore, e.scopeAlignmentScore AS scopeAlignmentScore, "
            + "e.teamCollaborationScore AS teamCollaborationScore, own.individualScore AS myIndividualScore, "
            + "own.contributionPercentage AS myContributionPercentage, own.totalCommits AS myTotalCommits"
            + CHAT_FROM + " LEFT JOIN e.studentScores own ON own.student.id = :userId " + CHAT_SCOPE
            + " ORDER BY e.createdAt DESC, e.id ASC")
    java.util.List<ChatEvaluation> findChatEvaluations(@org.springframework.data.repository.query.Param("userId") UUID userId,
            @org.springframework.data.repository.query.Param("coordinator") boolean coordinator,
            org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("SELECT e.finalized, COUNT(e)" + CHAT_FROM + CHAT_SCOPE + " GROUP BY e.finalized")
    java.util.List<Object[]> countChatEvaluations(@org.springframework.data.repository.query.Param("userId") UUID userId,
            @org.springframework.data.repository.query.Param("coordinator") boolean coordinator);

    long countByFinalizedTrue();

    Optional<EvaluationReport> findBySubmissionId(UUID submissionId);
}
