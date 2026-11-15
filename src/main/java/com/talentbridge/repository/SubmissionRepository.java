package com.talentbridge.repository;
import com.talentbridge.entity.Submission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface SubmissionRepository extends JpaRepository<Submission, UUID> {
    String CHAT_INDIVIDUAL_SCOPE = "(:coordinator = true OR party.supervisor.id = :userId "
            + "OR project.createdBy.id = :userId OR project.projectSupervisor.id = :userId "
            + "OR EXISTS (SELECT m.id FROM Party memberParty JOIN memberParty.members m WHERE memberParty = party AND m.id = :userId))";
    String CHAT_FROM = " FROM Submission s JOIN s.party party JOIN s.project project LEFT JOIN project.assignedParty currentParty ";
    String CHAT_SCOPE = " WHERE (" + CHAT_INDIVIDUAL_SCOPE + " OR currentParty.supervisor.id = :userId) ";
    interface ChatSubmission {
        UUID getId(); UUID getPartyId(); String getPartyName(); UUID getProjectId(); String getProjectTitle();
        com.talentbridge.enums.SubmissionStatus getStatus(); java.time.LocalDateTime getSubmittedAt();
    }
    @Query("SELECT s.id AS id, party.id AS partyId, party.name AS partyName, project.id AS projectId, "
            + "project.title AS projectTitle, s.status AS status, s.submittedAt AS submittedAt"
            + CHAT_FROM + CHAT_SCOPE + " ORDER BY s.createdAt DESC, s.id ASC")
    List<ChatSubmission> findChatSubmissions(@org.springframework.data.repository.query.Param("userId") UUID userId,
            @org.springframework.data.repository.query.Param("coordinator") boolean coordinator,
            org.springframework.data.domain.Pageable pageable);

    @Query("SELECT s.status, COUNT(s)" + CHAT_FROM + CHAT_SCOPE + " GROUP BY s.status")
    List<Object[]> countChatSubmissions(@org.springframework.data.repository.query.Param("userId") UUID userId,
            @org.springframework.data.repository.query.Param("coordinator") boolean coordinator);

    @Query("SELECT s.status, COUNT(s) FROM Submission s GROUP BY s.status")
    List<Object[]> countByStatusGrouped();

    Optional<Submission> findByPartyId(UUID partyId);
    List<Submission> findByProjectId(UUID projectId);
}
