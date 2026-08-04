package com.talentbridge.config;

import com.talentbridge.entity.Application;
import com.talentbridge.entity.Notification;
import com.talentbridge.entity.Party;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.Submission;
import com.talentbridge.entity.User;
import com.talentbridge.enums.PartyStatus;
import com.talentbridge.enums.ProjectStatus;
import com.talentbridge.enums.ProjectSupervisorInvitationStatus;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.repository.ApplicationRepository;
import com.talentbridge.repository.CompanyProfileRepository;
import com.talentbridge.repository.NotificationRepository;
import com.talentbridge.repository.PartyRepository;
import com.talentbridge.repository.ProjectRepository;
import com.talentbridge.repository.StudentProfileRepository;
import com.talentbridge.repository.SubmissionRepository;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.repository.UserModerationEventRepository;
import com.talentbridge.repository.ApplicationSettingsRepository;
import com.talentbridge.repository.EvaluationReportRepository;
import com.talentbridge.repository.NotificationPreferenceRepository;
import com.talentbridge.repository.ProjectSupervisorInvitationRepository;
import com.talentbridge.repository.ScorecardRepository;
import com.talentbridge.repository.SupervisorProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class DemoDataSeederTest {

    @Mock private UserRepository userRepository;
    @Mock private StudentProfileRepository studentProfileRepository;
    @Mock private CompanyProfileRepository companyProfileRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private PartyRepository partyRepository;
    @Mock private ApplicationRepository applicationRepository;
    @Mock private SubmissionRepository submissionRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private ApplicationSettingsRepository applicationSettingsRepository;
    @Mock private SupervisorProfileRepository supervisorProfileRepository;
    @Mock private ProjectSupervisorInvitationRepository invitationRepository;
    @Mock private EvaluationReportRepository evaluationReportRepository;
    @Mock private ScorecardRepository scorecardRepository;
    @Mock private NotificationPreferenceRepository notificationPreferenceRepository;
    @Mock private UserModerationEventRepository moderationEventRepository;
    @InjectMocks private DemoDataSeeder seeder;

    private User coordinator;

    @BeforeEach
    void setUp() {
        coordinator = User.builder()
                .email("coordinator@talentbridge.com")
                .password("encoded-demo-password")
                .firstName("Demo")
                .lastName("Coordinator")
                .role(UserRole.COORDINATOR)
                .status(UserStatus.APPROVED)
                .emailVerified(true)
                .build();
        coordinator.setId(java.util.UUID.randomUUID());
    }

    @Test
    void leavesCurrentDemoDataUntouchedOnOrdinaryRestart() {
        com.talentbridge.entity.ApplicationSettings settings = new com.talentbridge.entity.ApplicationSettings();
        settings.setDemoDataVersion(DemoDataSeeder.DEMO_DATA_VERSION);
        when(applicationSettingsRepository.findById(com.talentbridge.entity.ApplicationSettings.ID))
                .thenReturn(java.util.Optional.of(settings));
        seeder.seed(coordinator);

        verifyNoInteractions(userRepository, companyProfileRepository, projectRepository, partyRepository,
                applicationRepository, submissionRepository, notificationRepository);
    }

    @Test
    void createsRepresentativeDatasetOnce() {
        com.talentbridge.entity.NotificationPreference existingPreference =
                com.talentbridge.entity.NotificationPreference.builder()
                        .user(coordinator).type(com.talentbridge.enums.NotificationType.DEADLINE_REMINDER)
                        .emailEnabled(true).build();
        when(notificationPreferenceRepository.findByUserIdAndType(
                coordinator.getId(), com.talentbridge.enums.NotificationType.DEADLINE_REMINDER))
                .thenReturn(java.util.Optional.of(existingPreference));

        seeder.seed(coordinator);

        verify(userRepository).saveAll(argThat(users -> {
            var saved = StreamSupport.stream(users.spliterator(), false).toList();
            return saved.size() == 22
                    && saved.stream().map(User::getStatus).collect(Collectors.toSet())
                    .equals(Set.of(UserStatus.PENDING, UserStatus.APPROVED,
                            UserStatus.REJECTED, UserStatus.SUSPENDED));
        }));
        verify(studentProfileRepository).saveAll(argThat(profiles ->
                StreamSupport.stream(profiles.spliterator(), false).count() == 13));
        verify(companyProfileRepository).saveAll(argThat(profiles -> count(profiles) == 5));
        verify(projectRepository).saveAll(argThat(projects -> {
            var saved = StreamSupport.stream(projects.spliterator(), false).toList();
            var statuses = saved.stream()
                    .map(Project::getStatus)
                    .collect(Collectors.toSet());
            return saved.size() == 10 && statuses.equals(Set.of(ProjectStatus.DRAFT, ProjectStatus.PENDING_REVIEW,
                    ProjectStatus.OPEN, ProjectStatus.ASSIGNED, ProjectStatus.CLOSED,
                    ProjectStatus.ARCHIVED));
        }));
        verify(partyRepository).saveAll(argThat(parties -> {
            var saved = StreamSupport.stream(parties.spliterator(), false).toList();
            var statuses = saved.stream()
                    .map(Party::getStatus)
                    .collect(Collectors.toSet());
            return statuses.equals(Set.of(PartyStatus.FORMING, PartyStatus.ACTIVE,
                    PartyStatus.ASSIGNED, PartyStatus.SUBMITTED, PartyStatus.COMPLETED))
                    && saved.stream().allMatch(p -> p.getMembers().size() <= 3)
                    && saved.stream()
                    .filter(p -> p.getStatus() != PartyStatus.FORMING)
                    .allMatch(p -> p.getMembers().size() >= 2)
                    && saved.stream().filter(p -> p.getSupervisor() != null).count() <= 2;
        }));
        verify(applicationRepository).saveAll(argThat(items -> count(items) == 4));
        verify(submissionRepository).saveAll(argThat(items -> count(items) == 3));
        verify(notificationRepository).saveAll(argThat(items -> count(items) == 3));
        verify(invitationRepository).saveAll(argThat(invitations ->
                StreamSupport.stream(invitations.spliterator(), false)
                        .map(com.talentbridge.entity.ProjectSupervisorInvitation::getStatus)
                        .collect(Collectors.toSet())
                        .equals(Set.of(ProjectSupervisorInvitationStatus.PENDING,
                                ProjectSupervisorInvitationStatus.EXPIRED,
                                ProjectSupervisorInvitationStatus.REVOKED,
                                ProjectSupervisorInvitationStatus.ACCEPTED))));
        verify(notificationPreferenceRepository).save(existingPreference);
        org.junit.jupiter.api.Assertions.assertFalse(existingPreference.isEmailEnabled());
    }

    @Test
    void replacesOnlyKnownFixtureParties() {
        com.talentbridge.entity.ApplicationSettings settings = new com.talentbridge.entity.ApplicationSettings();
        settings.setDemoDataVersion(1);
        User demoCompany = User.builder().email(DemoDataSeeder.MARKER_EMAIL).build();
        demoCompany.setId(java.util.UUID.randomUUID());
        Party fixture = Party.builder().name("Team Alpha Demo").build();
        Party nonFixture = Party.builder().name("Capstone Demo").build();
        when(applicationSettingsRepository.findById(com.talentbridge.entity.ApplicationSettings.ID))
                .thenReturn(java.util.Optional.of(settings));
        when(userRepository.findByEmail(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        when(userRepository.findByEmail(DemoDataSeeder.MARKER_EMAIL))
                .thenReturn(java.util.Optional.of(demoCompany));
        when(partyRepository.findAll()).thenReturn(List.of(fixture, nonFixture));

        seeder.seed(coordinator);

        verify(partyRepository).delete(fixture);
        verify(partyRepository, never()).delete(nonFixture);
        verify(userRepository).flush();
    }

    @Test
    void removesLegacyTestDomainFixtures() {
        com.talentbridge.entity.ApplicationSettings settings = new com.talentbridge.entity.ApplicationSettings();
        settings.setDemoDataVersion(3);
        User legacy = User.builder().email("audit-student-123@talentbridge.test").build();
        legacy.setId(java.util.UUID.randomUUID());
        when(applicationSettingsRepository.findById(com.talentbridge.entity.ApplicationSettings.ID))
                .thenReturn(java.util.Optional.of(settings));
        when(userRepository.findAll()).thenReturn(List.of(legacy));

        seeder.seed(coordinator);

        verify(userRepository).deleteAll(argThat(users ->
                StreamSupport.stream(users.spliterator(), false).anyMatch(user -> user == legacy)));
    }

    @Test
    void givesEverySeededScorecardEntryItsOwningScorecard() {
        org.mockito.ArgumentCaptor<com.talentbridge.entity.Scorecard> scorecard =
                org.mockito.ArgumentCaptor.forClass(com.talentbridge.entity.Scorecard.class);

        seeder.seed(coordinator);

        verify(scorecardRepository).save(scorecard.capture());
        org.junit.jupiter.api.Assertions.assertTrue(scorecard.getValue().getEntries().stream()
                .allMatch(entry -> entry.getScorecard() == scorecard.getValue()));
    }

    private long count(Iterable<?> items) {
        return StreamSupport.stream(items.spliterator(), false).count();
    }
}
