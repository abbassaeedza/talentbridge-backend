package com.talentbridge.service;

import com.talentbridge.config.AppProperties;
import com.talentbridge.entity.ApplicationSettings;
import com.talentbridge.entity.Application;
import com.talentbridge.entity.Party;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.User;
import com.talentbridge.enums.ProjectStatus;
import com.talentbridge.enums.ApplicationStatus;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.dto.request.ProjectRequest;
import com.talentbridge.exception.BadRequestException;
import com.talentbridge.repository.ApplicationRepository;
import com.talentbridge.repository.ApplicationSettingsRepository;
import com.talentbridge.repository.PartyRepository;
import com.talentbridge.repository.ProjectRepository;
import com.talentbridge.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectServiceDeadlineTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private UserRepository userRepository;
    @Mock private PartyRepository partyRepository;
    @Mock private ApplicationRepository applicationRepository;
    @Mock private NotificationService notificationService;
    @Mock private ApplicationSettingsRepository applicationSettingsRepository;
    @Mock private AppProperties appProperties;
    @InjectMocks private ProjectService projectService;

    @Test
    void keepsTheSavedGlobalDeadlineAfterAnIndividualProjectDeadlineChanges() {
        AtomicReference<ApplicationSettings> storedSettings = new AtomicReference<>();
        Project openProject = Project.builder()
                .status(ProjectStatus.OPEN)
                .createdBy(User.builder().firstName("Demo").lastName("Company").build())
                .build();
        LocalDateTime globalDeadline = LocalDateTime.of(2030, 8, 31, 17, 0);
        LocalDateTime individualDeadline = LocalDateTime.of(2030, 8, 20, 17, 0);

        when(applicationSettingsRepository.findById(ApplicationSettings.ID))
                .thenAnswer(ignored -> Optional.ofNullable(storedSettings.get()));
        when(applicationSettingsRepository.save(any(ApplicationSettings.class)))
                .thenAnswer(invocation -> {
                    ApplicationSettings settings = invocation.getArgument(0);
                    storedSettings.set(settings);
                    return settings;
                });
        when(projectRepository.findByStatus(ProjectStatus.OPEN, Pageable.unpaged()))
                .thenReturn(new PageImpl<>(List.of(openProject)));
        when(projectRepository.findById(any(UUID.class))).thenReturn(Optional.of(openProject));
        when(projectRepository.save(openProject)).thenReturn(openProject);

        projectService.setGlobalDeadline(globalDeadline);
        projectService.setDeadline(UUID.randomUUID(), individualDeadline);

        assertEquals(globalDeadline, projectService.getGlobalDeadline().orElseThrow());
        assertEquals(individualDeadline, openProject.getDeadline());
    }

    @Test
    void refusesToAssignAnUndersizedParty() {
        UUID projectId = UUID.randomUUID();
        UUID partyId = UUID.randomUUID();
        Project project = Project.builder()
                .status(ProjectStatus.OPEN)
                .createdBy(User.builder().firstName("Demo").lastName("Company").build())
                .build();
        project.setId(projectId);
        Party party = Party.builder()
                .members(new java.util.HashSet<>(List.of(User.builder().build())))
                .build();
        AppProperties.Party rules = new AppProperties.Party();
        rules.setMinSize(2);
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));
        when(projectRepository.findByIdForUpdate(projectId)).thenReturn(Optional.of(project));
        when(appProperties.getParty()).thenReturn(rules);

        var error = assertThrows(com.talentbridge.exception.BadRequestException.class,
                () -> projectService.assignToParty(projectId, partyId, UUID.randomUUID()));

        assertEquals("Party needs minimum 2 members", error.getMessage());
    }

    @Test
    void withdrawsTheSelectedPartysOtherApplicationsOnAssignment() {
        UUID projectId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();
        UUID partyId = UUID.randomUUID();
        User leader = User.builder().build();
        User member = User.builder().build();
        Project project = Project.builder()
                .status(ProjectStatus.OPEN)
                .createdBy(User.builder().firstName("Demo").lastName("Company").build())
                .build();
        project.setId(projectId);
        Project otherProject = Project.builder().status(ProjectStatus.OPEN).build();
        otherProject.setId(otherProjectId);
        Party party = Party.builder()
                .leader(leader)
                .members(new java.util.HashSet<>(List.of(leader, member)))
                .build();
        Application selected = Application.builder()
                .party(party).project(project).rankPosition(1).status(ApplicationStatus.PENDING).build();
        Application other = Application.builder()
                .party(party).project(otherProject).rankPosition(2).status(ApplicationStatus.PENDING).build();
        AppProperties.Party rules = new AppProperties.Party();
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));
        when(projectRepository.findByIdForUpdate(projectId)).thenReturn(Optional.of(project));
        when(appProperties.getParty()).thenReturn(rules);
        when(applicationRepository.findByPartyIdAndProjectId(partyId, projectId)).thenReturn(Optional.of(selected));
        when(applicationRepository.findByPartyIdOrderByRankPositionAsc(partyId)).thenReturn(List.of(selected, other));
        when(applicationRepository.findByProjectIdAndStatus(projectId, ApplicationStatus.PENDING))
                .thenReturn(List.of());

        projectService.assignToParty(projectId, partyId, UUID.randomUUID());

        assertEquals(ApplicationStatus.ASSIGNED, selected.getStatus());
        assertEquals(ApplicationStatus.WITHDRAWN, other.getStatus());
    }

    @Test
    void rejectsAProjectSupervisorWhoDoesNotHaveTheProjectSupervisorRole() {
        UUID creatorId = UUID.randomUUID();
        UUID supervisorId = UUID.randomUUID();
        User creator = User.builder()
                .firstName("Company").lastName("Owner")
                .role(UserRole.COMPANY).status(UserStatus.APPROVED)
                .build();
        creator.setId(creatorId);
        User notASupervisor = User.builder()
                .firstName("Wrong").lastName("Role")
                .role(UserRole.COMPANY).status(UserStatus.APPROVED)
                .build();
        notASupervisor.setId(supervisorId);
        ProjectRequest request = new ProjectRequest();
        request.setTitle("Project");
        request.setDescription("Project description");
        request.setProjectSupervisorId(supervisorId);
        when(userRepository.findById(creatorId)).thenReturn(Optional.of(creator));
        when(userRepository.findById(supervisorId)).thenReturn(Optional.of(notASupervisor));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> projectService.create(creatorId, request));

        assertEquals("Project supervisor must be an approved project supervisor for this company", error.getMessage());
    }
}
