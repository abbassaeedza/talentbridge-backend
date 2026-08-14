package com.talentbridge.service;

import com.talentbridge.entity.EvaluationReport;
import com.talentbridge.entity.Party;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.Scorecard;
import com.talentbridge.entity.ScorecardEntry;
import com.talentbridge.entity.StudentEvaluationScore;
import com.talentbridge.entity.Submission;
import com.talentbridge.entity.User;
import com.talentbridge.enums.PartyStatus;
import com.talentbridge.enums.ProjectStatus;
import com.talentbridge.enums.SubmissionStatus;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.repository.ApplicationRepository;
import com.talentbridge.repository.ApplicationSettingsRepository;
import com.talentbridge.repository.PartyRepository;
import com.talentbridge.repository.ProjectRepository;
import com.talentbridge.repository.ScorecardRepository;
import com.talentbridge.repository.SubmissionRepository;
import com.talentbridge.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatContextServiceTest {
    @Mock private ProjectRepository projectRepository;
    @Mock private PartyRepository partyRepository;
    @Mock private SubmissionRepository submissionRepository;
    @Mock private ApplicationRepository applicationRepository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationSettingsRepository settingsRepository;
    @Mock private ScorecardRepository scorecardRepository;

    @Test
    void givesTheCoordinatorCurrentProjectCounts() {
        User coordinator = User.builder()
                .firstName("System")
                .lastName("Coordinator")
                .role(UserRole.COORDINATOR)
                .status(UserStatus.APPROVED)
                .build();
        coordinator.setId(UUID.randomUUID());
        User student = User.builder()
                .firstName("Demo")
                .lastName("Student")
                .role(UserRole.STUDENT)
                .status(UserStatus.PENDING)
                .build();
        student.setId(UUID.randomUUID());
        when(settingsRepository.findById((short) 1)).thenReturn(Optional.empty());
        when(projectRepository.findAll()).thenReturn(List.of(
                project("Assigned project", ProjectStatus.ASSIGNED),
                project("Open one", ProjectStatus.OPEN),
                project("Open two", ProjectStatus.OPEN),
                project("Finished", ProjectStatus.CLOSED)));
        when(partyRepository.findAll()).thenReturn(List.of());
        when(userRepository.findAll()).thenReturn(List.of(coordinator, student));
        when(submissionRepository.findAll()).thenReturn(List.of());

        String context = new ChatContextService(projectRepository, partyRepository,
                submissionRepository, applicationRepository, userRepository, settingsRepository,
                scorecardRepository)
                .build(coordinator);

        assertTrue(context.contains("role=COORDINATOR"));
        assertTrue(context.contains("total=4, assigned=1, assignedInProgress=1, openAvailable=2"));
        assertTrue(context.contains("finishedWorkflow=1, unfinished=3, unassignedActive=2"));
        assertTrue(context.contains("User role counts: STUDENT=1"));
        assertTrue(context.contains("PENDING=1"));
    }

    @Test
    void givesAStudentTheirScorecardAndEvaluationBreakdown() {
        User student = User.builder()
                .firstName("Demo")
                .lastName("Student")
                .role(UserRole.STUDENT)
                .status(UserStatus.APPROVED)
                .build();
        student.setId(UUID.randomUUID());
        Project project = project("Student project", ProjectStatus.CLOSED);
        StudentEvaluationScore individual = StudentEvaluationScore.builder()
                .student(student)
                .totalCommits(7)
                .contributionPercentage(70.0)
                .individualScore(76.0)
                .performanceNotes("Good contribution with limited tests")
                .build();
        EvaluationReport report = EvaluationReport.builder()
                .aiDetectionScore(80.0).aiDetectionNotes("Mostly authentic")
                .codeQualityScore(70.0).codeQualityNotes("Needs documentation")
                .functionalityScore(75.0).functionalityNotes("Core flow works")
                .scopeAlignmentScore(60.0).scopeAlignmentNotes("One deliverable missing")
                .teamCollaborationScore(50.0).teamCollaborationNotes("Uneven commits")
                .totalScore(69.25).overallSummary("Functional but incomplete")
                .studentScores(List.of(individual))
                .build();
        Party party = Party.builder()
                .name("Demo party")
                .status(PartyStatus.SUBMITTED)
                .members(Set.of(student))
                .assignedProject(project)
                .build();
        party.setId(UUID.randomUUID());
        Submission submission = Submission.builder()
                .party(party)
                .project(project)
                .status(SubmissionStatus.EVALUATED)
                .evaluationReport(report)
                .build();
        ScorecardEntry entry = ScorecardEntry.builder()
                .project(project)
                .score(82.0)
                .semester("Fall")
                .academicYear(2026)
                .build();
        Scorecard scorecard = Scorecard.builder()
                .student(student)
                .averageScore(82.0)
                .totalProjects(1)
                .entries(List.of(entry))
                .build();
        when(settingsRepository.findById((short) 1)).thenReturn(Optional.empty());
        when(projectRepository.findByStatus(ProjectStatus.OPEN, Pageable.unpaged()))
                .thenReturn(Page.empty());
        when(partyRepository.findByMemberId(student.getId())).thenReturn(Optional.of(party));
        when(applicationRepository.findByPartyIdOrderByRankPositionAsc(party.getId()))
                .thenReturn(List.of());
        when(submissionRepository.findByPartyId(party.getId())).thenReturn(Optional.of(submission));
        when(scorecardRepository.findByStudentId(student.getId())).thenReturn(Optional.of(scorecard));

        String context = new ChatContextService(projectRepository, partyRepository,
                submissionRepository, applicationRepository, userRepository, settingsRepository,
                scorecardRepository)
                .build(student);

        assertTrue(context.contains("My scorecard: averageScore=82.0; totalProjects=1"));
        assertTrue(context.contains("scorecardProject=Student project; score=82.0"));
        assertTrue(context.contains("AI authenticity=20%, code quality=25%, functionality=25%"));
        assertTrue(context.contains("Scope alignment: score=60.0/100; reason=One deliverable missing"));
        assertTrue(context.contains("My individual evaluation: score=76.0/100; commits=7; contribution=70.0%"));
    }

    private Project project(String title, ProjectStatus status) {
        Project project = Project.builder().title(title).status(status).build();
        project.setId(UUID.randomUUID());
        return project;
    }
}
