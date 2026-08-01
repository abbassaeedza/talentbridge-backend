package com.talentbridge.service;

import com.talentbridge.config.AppProperties;
import com.talentbridge.dto.request.ApplicationRequest;
import com.talentbridge.entity.Party;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.Submission;
import com.talentbridge.entity.User;
import com.talentbridge.enums.PartyStatus;
import com.talentbridge.enums.UserRole;
import com.talentbridge.exception.BadRequestException;
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
    void blocksProjectUnassignmentAfterASubmissionExists() {
        UUID partyId = UUID.randomUUID();
        Party party = Party.builder()
                .assignedProject(Project.builder().build())
                .build();
        when(partyRepository.findByIdForUpdate(partyId)).thenReturn(Optional.of(party));
        when(submissionRepository.findByPartyId(partyId)).thenReturn(Optional.of(Submission.builder().build()));

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> partyService.unassignProject(partyId));

        assertEquals("A party with a submission cannot be unassigned", error.getMessage());
    }

    private User user(UUID id, UserRole role) {
        User user = User.builder()
                .email(id + "@example.com")
                .firstName("Demo")
                .lastName("User")
                .role(role)
                .build();
        user.setId(id);
        return user;
    }
}
