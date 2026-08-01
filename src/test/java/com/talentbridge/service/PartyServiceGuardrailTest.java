package com.talentbridge.service;

import com.talentbridge.config.AppProperties;
import com.talentbridge.dto.request.ApplicationRequest;
import com.talentbridge.dto.request.PartyRequest;
import com.talentbridge.entity.Party;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.Submission;
import com.talentbridge.entity.User;
import com.talentbridge.entity.Application;
import com.talentbridge.enums.ApplicationStatus;
import com.talentbridge.enums.PartyStatus;
import com.talentbridge.enums.ProjectStatus;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.exception.BadRequestException;
import com.talentbridge.exception.ForbiddenException;
import com.talentbridge.repository.ApplicationRepository;
import com.talentbridge.repository.PartyRepository;
import com.talentbridge.repository.ProjectRepository;
import com.talentbridge.repository.SubmissionRepository;
import com.talentbridge.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

@ExtendWith(MockitoExtension.class)
class PartyServiceGuardrailTest {

    @Mock private PartyRepository partyRepository;
    @Mock private UserRepository userRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private ApplicationRepository applicationRepository;
    @Mock private SubmissionRepository submissionRepository;
    @Mock private NotificationService notificationService;
    @Spy private AppProperties appProperties = new AppProperties();
    @InjectMocks private PartyService partyService;

    @Test
    void activatesSingleMemberPartiesWhenTheConfiguredMinimumIsOne() {
        UUID leaderId = UUID.randomUUID();
        User leader = user(leaderId, UserRole.STUDENT);
        PartyRequest request = new PartyRequest();
        request.setName("Solo Team");
        request.setSemester("Fall");
        request.setAcademicYear(2026);
        appProperties.getParty().setMinSize(1);
        when(userRepository.findByIdForUpdate(leaderId)).thenReturn(Optional.of(leader));
        when(partyRepository.findByMemberId(leaderId)).thenReturn(Optional.empty());
        when(partyRepository.save(any(Party.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = partyService.create(leaderId, request);

        assertEquals(PartyStatus.ACTIVE, response.getStatus());
    }

    @Test
    void blocksApplicationsWhenTheActualPartySizeIsBelowTheMinimum() {
        UUID partyId = UUID.randomUUID();
        UUID leaderId = UUID.randomUUID();
        User leader = User.builder().email("leader@example.com").build();
        leader.setId(leaderId);
        Party party = Party.builder()
                .leader(leader)
                .members(new HashSet<>(Set.of(leader)))
                .status(PartyStatus.ACTIVE)
                .build();
        ApplicationRequest request = new ApplicationRequest();
        request.setProjectId(UUID.randomUUID());
        request.setRankPosition(1);
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> partyService.applyToProject(partyId, leaderId, request));

        assertEquals("Party needs minimum 2 members", error.getMessage());
    }

    @Test
    void blocksConcurrentJoinsAtTheConfiguredMaximum() {
        UUID partyId = UUID.randomUUID();
        User leader = user(UUID.randomUUID(), UserRole.STUDENT);
        Party party = Party.builder()
                .leader(leader)
                .members(new HashSet<>(Set.of(
                        leader,
                        user(UUID.randomUUID(), UserRole.STUDENT),
                        user(UUID.randomUUID(), UserRole.STUDENT))))
                .status(PartyStatus.ACTIVE)
                .build();
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> partyService.join(partyId, UUID.randomUUID()));

        assertEquals("Party is full (max 3 members)", error.getMessage());
    }

    @Test
    void locksTheStudentWhileJoiningToPreventTwoPartyMemberships() {
        UUID partyId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        User leader = user(UUID.randomUUID(), UserRole.STUDENT);
        User student = user(studentId, UserRole.STUDENT);
        Party party = Party.builder()
                .leader(leader)
                .members(new HashSet<>(Set.of(leader)))
                .status(PartyStatus.FORMING)
                .build();
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));
        when(userRepository.findByIdForUpdate(studentId)).thenReturn(Optional.of(student));
        when(partyRepository.save(party)).thenReturn(party);

        partyService.join(partyId, studentId);

        verify(userRepository).findByIdForUpdate(studentId);
    }

    @Test
    void letsAnActivePartyGrowToTheConfiguredMaximum() {
        UUID partyId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();
        User leader = user(UUID.randomUUID(), UserRole.STUDENT);
        User member = user(UUID.randomUUID(), UserRole.STUDENT);
        User student = user(studentId, UserRole.STUDENT);
        Party party = Party.builder()
                .leader(leader)
                .members(new HashSet<>(Set.of(leader, member)))
                .status(PartyStatus.ACTIVE)
                .build();
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));
        when(userRepository.findByIdForUpdate(studentId)).thenReturn(Optional.of(student));
        when(partyRepository.findByMemberId(studentId)).thenReturn(Optional.empty());
        when(partyRepository.save(party)).thenReturn(party);

        partyService.join(partyId, studentId);

        assertEquals(3, party.getMembers().size());
        assertEquals(PartyStatus.ACTIVE, party.getStatus());
    }

    @Test
    void locksTheProjectWhileApplyingToPreventAssignmentRaces() {
        UUID partyId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID leaderId = UUID.randomUUID();
        User leader = user(leaderId, UserRole.STUDENT);
        Party party = Party.builder()
                .name("Safe Team")
                .leader(leader)
                .members(new HashSet<>(Set.of(leader, user(UUID.randomUUID(), UserRole.STUDENT))))
                .status(PartyStatus.ACTIVE)
                .build();
        party.setId(partyId);
        Project project = Project.builder().title("Safe Project").status(ProjectStatus.OPEN).build();
        project.setId(projectId);
        ApplicationRequest request = new ApplicationRequest();
        request.setProjectId(projectId);
        request.setRankPosition(1);
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));
        when(applicationRepository.findByPartyIdOrderByRankPositionAsc(partyId)).thenReturn(java.util.List.of());
        when(projectRepository.findByIdForUpdate(projectId)).thenReturn(Optional.of(project));
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> invocation.getArgument(0));

        partyService.applyToProject(partyId, leaderId, request);

        verify(projectRepository).findByIdForUpdate(projectId);
    }

    @Test
    void usesTheConfiguredSupervisorCapacityForTheAcademicTerm() {
        UUID partyId = UUID.randomUUID();
        UUID supervisorId = UUID.randomUUID();
        User leader = user(UUID.randomUUID(), UserRole.STUDENT);
        User supervisor = user(supervisorId, UserRole.PARTY_SUPERVISOR);
        Party party = Party.builder()
                .leader(leader)
                .members(new HashSet<>(Set.of(leader)))
                .status(PartyStatus.FORMING)
                .semester("Fall")
                .academicYear(2026)
                .build();
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));
        when(userRepository.findByIdForUpdate(supervisorId)).thenReturn(Optional.of(supervisor));
        when(partyRepository.countBySupervisorIdAndSemesterAndAcademicYearAndIdNot(
                supervisorId, "Fall", 2026, partyId)).thenReturn(2L);

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> partyService.assignSupervisor(partyId, supervisorId));

        assertEquals("Supervisor already has 2 parties this academic term", error.getMessage());
    }

    @Test
    void refusesToAssignASuspendedSupervisor() {
        UUID partyId = UUID.randomUUID();
        UUID supervisorId = UUID.randomUUID();
        User leader = user(UUID.randomUUID(), UserRole.STUDENT);
        User supervisor = user(supervisorId, UserRole.PARTY_SUPERVISOR);
        supervisor.setStatus(UserStatus.SUSPENDED);
        Party party = Party.builder()
                .leader(leader)
                .members(new HashSet<>(Set.of(leader)))
                .status(PartyStatus.ACTIVE)
                .semester("Fall")
                .academicYear(2026)
                .build();
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));
        when(userRepository.findByIdForUpdate(supervisorId)).thenReturn(Optional.of(supervisor));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> partyService.assignSupervisor(partyId, supervisorId));

        assertEquals("Supervisor account must be approved", error.getMessage());
    }

    @Test
    void blocksSupervisorClaimsForPartiesThatAreStillForming() {
        UUID partyId = UUID.randomUUID();
        UUID supervisorId = UUID.randomUUID();
        User supervisor = user(supervisorId, UserRole.PARTY_SUPERVISOR);
        Party party = Party.builder()
                .leader(user(UUID.randomUUID(), UserRole.STUDENT))
                .status(PartyStatus.FORMING)
                .build();
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> partyService.claimSupervisor(partyId, supervisorId));

        assertEquals("Only active or assigned parties can be claimed", error.getMessage());
    }

    @Test
    void hidesPartyRankingsFromUnrelatedUsers() {
        UUID partyId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        User member = user(UUID.randomUUID(), UserRole.STUDENT);
        User viewer = user(viewerId, UserRole.STUDENT);
        member.setStatus(UserStatus.APPROVED);
        viewer.setStatus(UserStatus.APPROVED);
        Party party = Party.builder()
                .leader(member)
                .members(new HashSet<>(Set.of(member)))
                .status(PartyStatus.ACTIVE)
                .build();
        when(partyRepository.findById(partyId)).thenReturn(Optional.of(party));
        when(userRepository.findById(viewerId)).thenReturn(Optional.of(viewer));

        assertThrows(ForbiddenException.class,
                () -> partyService.getApplicationsByParty(partyId, viewerId));
    }

    @Test
    void blocksProjectUnassignmentAfterASubmissionExists() {
        UUID partyId = UUID.randomUUID();
        Project project = Project.builder().build();
        project.setId(UUID.randomUUID());
        Party party = Party.builder()
                .assignedProject(project)
                .build();
        party.setId(partyId);
        when(partyRepository.findById(partyId)).thenReturn(Optional.of(party));
        when(applicationRepository.findByProjectIdAndStatusIn(any(), anyList()))
                .thenReturn(java.util.List.of());
        when(partyRepository.findAllByIdForUpdate(anyList())).thenReturn(java.util.List.of(party));
        when(submissionRepository.findByPartyId(partyId)).thenReturn(Optional.of(Submission.builder().build()));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> partyService.unassignProject(partyId));

        assertEquals("A party with a submission cannot be unassigned", error.getMessage());
    }

    @Test
    void restoresWithdrawnChoicesWhenAProjectIsUnassigned() {
        UUID partyId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        User leader = user(UUID.randomUUID(), UserRole.STUDENT);
        User member = user(UUID.randomUUID(), UserRole.STUDENT);
        Project assignedProject = Project.builder().status(com.talentbridge.enums.ProjectStatus.ASSIGNED).build();
        assignedProject.setId(projectId);
        Project otherProject = Project.builder().status(com.talentbridge.enums.ProjectStatus.OPEN).build();
        Party waitingParty = Party.builder().assignedProject(null).build();
        Party busyParty = Party.builder().assignedProject(Project.builder().build()).build();
        waitingParty.setId(UUID.randomUUID());
        busyParty.setId(UUID.randomUUID());
        Party party = Party.builder()
                .leader(leader)
                .members(new HashSet<>(Set.of(leader, member)))
                .assignedProject(assignedProject)
                .status(PartyStatus.ASSIGNED)
                .build();
        party.setId(partyId);
        Application selected = Application.builder()
                .party(party).project(assignedProject).rankPosition(1).status(ApplicationStatus.ASSIGNED).build();
        Application other = Application.builder()
                .party(party).project(otherProject).rankPosition(2).status(ApplicationStatus.WITHDRAWN).build();
        Application stranded = Application.builder()
                .party(waitingParty).project(assignedProject).rankPosition(1).status(ApplicationStatus.WITHDRAWN).build();
        Application busy = Application.builder()
                .party(busyParty).project(assignedProject).rankPosition(1).status(ApplicationStatus.REASSIGNED).build();
        when(partyRepository.findById(partyId)).thenReturn(Optional.of(party));
        when(submissionRepository.findByPartyId(partyId)).thenReturn(Optional.empty());
        when(projectRepository.findByIdForUpdate(projectId)).thenReturn(Optional.of(assignedProject));
        when(applicationRepository.findByProjectIdAndStatusIn(projectId,
                java.util.List.of(ApplicationStatus.ASSIGNED, ApplicationStatus.REASSIGNED,
                        ApplicationStatus.WITHDRAWN)))
                .thenReturn(java.util.List.of(selected, stranded, busy));
        when(partyRepository.findAllByIdForUpdate(anyList()))
                .thenReturn(java.util.List.of(party, waitingParty, busyParty));
        when(applicationRepository.findByPartyIdOrderByRankPositionAsc(partyId))
                .thenReturn(java.util.List.of(selected, other));
        when(partyRepository.save(party)).thenReturn(party);

        partyService.unassignProject(partyId);

        assertEquals(ApplicationStatus.PENDING, selected.getStatus());
        assertEquals(ApplicationStatus.PENDING, other.getStatus());
        assertEquals(ApplicationStatus.PENDING, stranded.getStatus());
        assertEquals(ApplicationStatus.WITHDRAWN, busy.getStatus());
        verify(partyRepository).findAllByIdForUpdate(anyList());
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
