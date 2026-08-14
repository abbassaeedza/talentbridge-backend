package com.talentbridge.service;

import com.talentbridge.entity.EvaluationReport;
import com.talentbridge.entity.Party;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.StudentEvaluationScore;
import com.talentbridge.entity.Submission;
import com.talentbridge.entity.User;
import com.talentbridge.repository.EvaluationReportRepository;
import com.talentbridge.repository.SubmissionRepository;
import com.talentbridge.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationServiceReevaluationTest {
    @Mock private SubmissionRepository submissionRepository;
    @Mock private EvaluationReportRepository evaluationReportRepository;
    @Mock private UserRepository userRepository;
    @Mock private ScorecardService scorecardService;
    @Mock private GitHubService gitHubService;
    @Mock private OpenAIService openAIService;
    @Mock private NotificationService notificationService;

    private EvaluationService service;
    private UUID reportId;
    private User linkedStudent;
    private User unlinkedStudent;
    private EvaluationReport report;

    @BeforeEach
    void setUp() {
        service = new EvaluationService(submissionRepository, evaluationReportRepository, userRepository,
                scorecardService, gitHubService, openAIService, notificationService);
        reportId = UUID.randomUUID();
        linkedStudent = user("Carol", "Iqbal", "carol@example.com", "tryinnewthings505-a11y");
        unlinkedStudent = user("Hana", "Yusuf", "hana@example.com", null);
        Project project = Project.builder().title("Network Health Monitor").build();
        project.setId(UUID.randomUUID());
        Party party = Party.builder()
                .leader(linkedStudent)
                .members(Set.of(linkedStudent, unlinkedStudent))
                .build();
        Submission submission = Submission.builder()
                .party(party)
                .project(project)
                .repoUrl("https://github.com/acme/network-health-monitor")
                .build();
        submission.setId(UUID.randomUUID());
        report = EvaluationReport.builder()
                .submission(submission)
                .aiDetectionScore(85.0)
                .codeQualityScore(70.0)
                .functionalityScore(60.0)
                .scopeAlignmentScore(50.0)
                .teamCollaborationScore(20.0)
                .totalScore(63.0)
                .overallSummary("Existing deterministic report")
                .studentScores(new ArrayList<>(List.of(StudentEvaluationScore.builder()
                        .student(linkedStudent)
                        .totalCommits(0)
                        .contributionPercentage(0.0)
                        .individualScore(54.0)
                        .build())))
                .finalized(false)
                .build();
        report.setId(reportId);
    }

    @Test
    void reevaluationUsesOnlyLinkedOAuthUsernamesAndPreservesAiScores() {
        when(evaluationReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(gitHubService.fetchContributorData(any(), any())).thenReturn(new GitHubService.ContributorData(
                List.of(), Map.of("TryinNewThings505-A11Y", 7, "hana", 5)));
        when(evaluationReportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.reevaluate(reportId, UUID.randomUUID());

        assertEquals(85.0, response.getAiDetectionScore());
        assertEquals(63.0, response.getTotalScore());
        assertEquals(2, response.getStudentScores().size());
        var carol = response.getStudentScores().stream()
                .filter(score -> score.getStudent().getId().equals(linkedStudent.getId()))
                .findFirst().orElseThrow();
        var hana = response.getStudentScores().stream()
                .filter(score -> score.getStudent().getId().equals(unlinkedStudent.getId()))
                .findFirst().orElseThrow();
        assertEquals(7, carol.getTotalCommits());
        assertEquals(58.3, carol.getContributionPercentage());
        assertEquals(0, hana.getTotalCommits());
        assertEquals(0.0, hana.getContributionPercentage());
        assertTrue(hana.getPerformanceNotes().contains("GitHub not connected"));
        verify(openAIService, never()).evaluateRepository(any(), any(), any(), any());
        verify(scorecardService).addEntry(linkedStudent, report.getSubmission().getProject(), report);
        verify(scorecardService).addEntry(unlinkedStudent, report.getSubmission().getProject(), report);
    }

    @Test
    void finalizedReportsCannotBeReevaluated() {
        report.setFinalized(true);
        when(evaluationReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        assertThrows(com.talentbridge.exception.BadRequestException.class,
                () -> service.reevaluate(reportId, UUID.randomUUID()));
        verify(gitHubService, never()).fetchContributorData(any(), any());
    }

    private User user(String firstName, String lastName, String email, String githubUsername) {
        User user = User.builder()
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .githubUsername(githubUsername)
                .githubAccessToken(githubUsername == null ? null : "token")
                .build();
        user.setId(UUID.randomUUID());
        return user;
    }
}
