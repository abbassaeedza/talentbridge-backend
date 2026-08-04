package com.talentbridge.service;

import com.talentbridge.entity.Party;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.User;
import com.talentbridge.entity.Submission;
import com.talentbridge.enums.SubmissionStatus;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.exception.ForbiddenException;
import com.talentbridge.exception.BadRequestException;
import com.talentbridge.repository.PartyRepository;
import com.talentbridge.repository.ProjectRepository;
import com.talentbridge.repository.SubmissionRepository;
import com.talentbridge.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionServiceDeadlineTest {

    @Mock private SubmissionRepository submissionRepository;
    @Mock private PartyRepository partyRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private UserRepository userRepository;
    @Mock private FileStorageService fileStorageService;
    @Mock private NotificationService notificationService;
    @InjectMocks private SubmissionService submissionService;

    @Test
    void blocksFinalSubmissionAfterTheAssignedProjectDeadline() {
        UUID partyId = UUID.randomUUID();
        UUID leaderId = UUID.randomUUID();
        User leader = User.builder().email("leader@example.com").build();
        leader.setId(leaderId);
        Project project = Project.builder().deadline(LocalDateTime.now().minusMinutes(1)).build();
        Party party = Party.builder().leader(leader).assignedProject(project).build();
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> submissionService.finalSubmit(partyId, leaderId));

        assertEquals("The project deadline has expired", error.getMessage());
    }

    @Test
    void preventsEditingASubmittedSubmission() {
        UUID partyId = UUID.randomUUID();
        UUID leaderId = UUID.randomUUID();
        User leader = User.builder().build();
        leader.setId(leaderId);
        Party party = Party.builder().leader(leader).assignedProject(Project.builder().build()).build();
        Submission submission = Submission.builder().status(SubmissionStatus.SUBMITTED).build();
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));
        when(submissionRepository.findByPartyId(partyId)).thenReturn(Optional.of(submission));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> submissionService.saveDraft(partyId, leaderId, "https://example.com/repo", "main", null, null));

        assertEquals("Submitted work cannot be edited", error.getMessage());
    }

    @Test
    void preventsSubmittingTheSameWorkTwice() {
        UUID partyId = UUID.randomUUID();
        UUID leaderId = UUID.randomUUID();
        User leader = User.builder().build();
        leader.setId(leaderId);
        Party party = Party.builder().leader(leader).assignedProject(Project.builder().build()).build();
        Submission submission = Submission.builder()
                .status(SubmissionStatus.SUBMITTED)
                .repoUrl("https://example.com/repo")
                .build();
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));
        when(submissionRepository.findByPartyId(partyId)).thenReturn(Optional.of(submission));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> submissionService.finalSubmit(partyId, leaderId));

        assertEquals("Submission has already been finalized", error.getMessage());
    }

    @Test
    void preventsEditingADraftFromAPreviouslyAssignedProject() {
        UUID partyId = UUID.randomUUID();
        UUID leaderId = UUID.randomUUID();
        User leader = User.builder().build();
        leader.setId(leaderId);
        Project previousProject = Project.builder().build();
        previousProject.setId(UUID.randomUUID());
        Project assignedProject = Project.builder().build();
        assignedProject.setId(UUID.randomUUID());
        Party party = Party.builder().leader(leader).assignedProject(assignedProject).build();
        Submission submission = Submission.builder()
                .project(previousProject)
                .status(SubmissionStatus.DRAFT)
                .build();
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));
        when(submissionRepository.findByPartyId(partyId)).thenReturn(Optional.of(submission));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> submissionService.saveDraft(partyId, leaderId, "https://example.com/repo", "main", null, null));

        assertEquals("Submission belongs to a different project", error.getMessage());
    }

    @Test
    void preventsFinalizingADraftFromAPreviouslyAssignedProject() {
        UUID partyId = UUID.randomUUID();
        UUID leaderId = UUID.randomUUID();
        User leader = User.builder().build();
        leader.setId(leaderId);
        Project previousProject = Project.builder().build();
        previousProject.setId(UUID.randomUUID());
        Project assignedProject = Project.builder().build();
        assignedProject.setId(UUID.randomUUID());
        Party party = Party.builder().leader(leader).assignedProject(assignedProject).build();
        Submission submission = Submission.builder()
                .project(previousProject)
                .status(SubmissionStatus.DRAFT)
                .repoUrl("https://example.com/repo")
                .build();
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));
        when(submissionRepository.findByPartyId(partyId)).thenReturn(Optional.of(submission));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> submissionService.finalSubmit(partyId, leaderId));

        assertEquals("Submission belongs to a different project", error.getMessage());
    }

    @Test
    void hidesPartySubmissionsFromUnrelatedStudents() {
        UUID partyId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        User member = user(UUID.randomUUID(), UserRole.STUDENT);
        User viewer = user(viewerId, UserRole.STUDENT);
        User owner = user(UUID.randomUUID(), UserRole.COMPANY);
        Project project = Project.builder().createdBy(owner).build();
        Party party = Party.builder()
                .leader(member)
                .members(new java.util.HashSet<>(java.util.Set.of(member)))
                .build();
        Submission submission = Submission.builder().party(party).project(project).build();
        when(submissionRepository.findByPartyId(partyId)).thenReturn(Optional.of(submission));
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(viewer));

        assertThrows(ForbiddenException.class,
                () -> submissionService.getByPartyId(partyId, viewerId));
    }

    @Test
    void doesNotExposeALegacySubmissionToTheNewProjectOwner() {
        UUID partyId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        User oldOwner = user(UUID.randomUUID(), UserRole.COMPANY);
        User newOwner = user(viewerId, UserRole.COMPANY);
        User member = user(UUID.randomUUID(), UserRole.STUDENT);
        Project oldProject = Project.builder().createdBy(oldOwner).build();
        Project newProject = Project.builder().createdBy(newOwner).build();
        Party party = Party.builder()
                .leader(member)
                .members(new java.util.HashSet<>(java.util.Set.of(member)))
                .assignedProject(newProject)
                .build();
        Submission submission = Submission.builder().party(party).project(oldProject).build();
        when(submissionRepository.findByPartyId(partyId)).thenReturn(Optional.of(submission));
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(newOwner));

        assertThrows(ForbiddenException.class,
                () -> submissionService.getByPartyId(partyId, viewerId));
    }

    @Test
    void hidesProjectSubmissionsFromUnrelatedSupervisors() {
        UUID projectId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        User owner = user(UUID.randomUUID(), UserRole.COMPANY);
        User viewer = user(viewerId, UserRole.PROJECT_SUPERVISOR);
        Project project = Project.builder().createdBy(owner).build();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(viewer));
        when(submissionRepository.findByProjectId(projectId)).thenReturn(java.util.List.of());

        assertThrows(ForbiddenException.class,
                () -> submissionService.getByProjectId(projectId, viewerId));
    }

    @Test
    void returnsCompactProjectSubmissionResponses() {
        UUID projectId = UUID.randomUUID();
        UUID partyId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        User coordinator = user(viewerId, UserRole.COORDINATOR);
        User owner = user(UUID.randomUUID(), UserRole.COMPANY);
        Project project = Project.builder().title("Demo project").createdBy(owner).build();
        project.setId(projectId);
        Party party = Party.builder().name("Demo party").build();
        party.setId(partyId);
        Submission submission = Submission.builder()
                .party(party).project(project).status(SubmissionStatus.DRAFT)
                .documentUrls(List.of()).build();
        when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(coordinator));
        when(submissionRepository.findByProjectId(projectId)).thenReturn(List.of(submission));

        var response = submissionService.getByProjectId(projectId, viewerId).get(0);

        assertEquals(partyId, response.getPartyId());
        assertEquals("Demo party", response.getPartyName());
        assertEquals(projectId, response.getProjectId());
        assertEquals("Demo project", response.getProjectTitle());
    }

    private User user(UUID id, UserRole role) {
        User user = User.builder()
                .email(id + "@example.com")
                .firstName("Demo")
                .lastName("User")
                .role(role)
                .status(UserStatus.APPROVED)
                .build();
        user.setId(id);
        return user;
    }
}
