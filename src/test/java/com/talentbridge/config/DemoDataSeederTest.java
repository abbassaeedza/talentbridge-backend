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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.junit.jupiter.api.Assertions.*;

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
    @Mock private PasswordEncoder passwordEncoder;
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
        ReflectionTestUtils.setField(seeder, "demoPassword", "test-only-demo-password");
        ReflectionTestUtils.setField(seeder, "resetDemoData", true);
        lenient().when(userRepository.findByEmail(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        lenient().when(applicationSettingsRepository.findByIdForUpdate(com.talentbridge.entity.ApplicationSettings.ID))
                .thenReturn(java.util.Optional.of(new com.talentbridge.entity.ApplicationSettings()));
        lenient().when(passwordEncoder.matches(org.mockito.ArgumentMatchers.anyString(), any())).thenReturn(false);
        lenient().when(passwordEncoder.encode("test-only-demo-password")).thenReturn("test-only-encoded-value");
    }

    @Test
    void leavesCurrentDemoDataUntouchedOnOrdinaryRestart() {
        com.talentbridge.entity.ApplicationSettings settings = new com.talentbridge.entity.ApplicationSettings();
        settings.setDemoDataVersion(DemoDataSeeder.DEMO_DATA_VERSION);
        when(applicationSettingsRepository.findByIdForUpdate(com.talentbridge.entity.ApplicationSettings.ID))
                .thenReturn(java.util.Optional.of(settings));
        seeder.seed(coordinator);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(companyProfileRepository, projectRepository, partyRepository,
                applicationRepository, submissionRepository, notificationRepository);
    }

    @Test
    void createsRepresentativeDatasetOnce() {
        seeder.seed(coordinator);

        verify(userRepository).saveAll(argThat(users -> {
            var saved = StreamSupport.stream(users.spliterator(), false).toList();
            return saved.size() == 23
                    && saved.stream().map(User::getEmail).collect(Collectors.toSet())
                        .equals(Set.copyOf(DemoDataSeeder.DEMO_USER_EMAILS))
                    && saved.stream().allMatch(user -> user.getEmail().matches("[a-z]+\\.[a-z]+@example\\.test"))
                    && saved.stream().allMatch(user -> "test-only-encoded-value".equals(user.getPassword()))
                    && saved.stream().filter(user -> user.getRole() == UserRole.COORDINATOR)
                        .allMatch(user -> DemoDataSeeder.DEMO_COORDINATOR_EMAIL.equals(user.getEmail()))
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
        verify(notificationPreferenceRepository).save(argThat(preference -> !preference.isEmailEnabled()
                && DemoDataSeeder.DEMO_COORDINATOR_EMAIL.equals(preference.getUser().getEmail())));
    }

    @Test
    void cleansOnlyExactLegacyAndCurrentAccountsAndPreservesOtherDomainsAndOperator() {
        com.talentbridge.entity.ApplicationSettings settings = new com.talentbridge.entity.ApplicationSettings();
        settings.setDemoDataVersion(1);
        User legacy = account("alice.student@talentbridge.com");
        User current = account(DemoDataSeeder.MARKER_EMAIL);
        User sameDomain = account("real.person@talentbridge.com");
        User testDomain = account("audit-student-123@talentbridge.test");
        User exampleDomain = account("real.person@example.test");
        Party nonFixture = Party.builder().name("Team Alpha Demo").leader(sameDomain)
                .members(Set.of(sameDomain)).build();
        when(applicationSettingsRepository.findByIdForUpdate(com.talentbridge.entity.ApplicationSettings.ID))
                .thenReturn(java.util.Optional.of(settings));
        when(userRepository.findByEmail(legacy.getEmail())).thenReturn(java.util.Optional.of(legacy));
        when(userRepository.findByEmail(DemoDataSeeder.MARKER_EMAIL))
                .thenReturn(java.util.Optional.of(current));
        when(partyRepository.findAll()).thenReturn(List.of(nonFixture));

        seeder.seed(coordinator);

        verify(partyRepository, never()).delete(nonFixture);
        verify(userRepository).deleteAll(argThat(users -> {
            var deleted = StreamSupport.stream(users.spliterator(), false).toList();
            return deleted.equals(List.of(legacy, current)) && !deleted.contains(coordinator)
                    && !deleted.contains(sameDomain) && !deleted.contains(testDomain) && !deleted.contains(exampleDomain);
        }));
        verify(userRepository, never()).findAll();
        verify(userRepository).flush();
    }

    @Test
    void skipsVersionMismatchWithoutResetApproval() {
        com.talentbridge.entity.ApplicationSettings settings = new com.talentbridge.entity.ApplicationSettings();
        settings.setDemoDataVersion(3);
        when(applicationSettingsRepository.findByIdForUpdate(com.talentbridge.entity.ApplicationSettings.ID))
                .thenReturn(java.util.Optional.of(settings));
        ReflectionTestUtils.setField(seeder, "resetDemoData", false);

        seeder.seed(coordinator);

        verifyNoInteractions(userRepository, projectRepository, partyRepository);
        verify(applicationSettingsRepository, never()).save(any());
        assertEquals(3, settings.getDemoDataVersion());
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

    @Test
    void sameVersionSynchronizesOnlyExactFixturePasswordsWithoutResettingWork() {
        var settings = currentSettings();
        User student = account("mira.shah@example.test");
        User demoCoordinator = account(DemoDataSeeder.DEMO_COORDINATOR_EMAIL);
        student.setPassword("old-test-value");
        demoCoordinator.setPassword("unchanged-test-value");
        when(userRepository.findByEmail(student.getEmail())).thenReturn(java.util.Optional.of(student));
        when(userRepository.findByEmail(demoCoordinator.getEmail())).thenReturn(java.util.Optional.of(demoCoordinator));
        when(passwordEncoder.matches("test-only-demo-password", demoCoordinator.getPassword())).thenReturn(true);
        when(passwordEncoder.matches("test-only-demo-password", "test-only-encoded-value")).thenReturn(true);
        ReflectionTestUtils.setField(seeder, "resetDemoData", false);

        seeder.seed(coordinator);
        seeder.seed(coordinator);

        verify(userRepository).save(student);
        verify(userRepository, never()).save(demoCoordinator);
        verify(userRepository, never()).findByEmail(coordinator.getEmail());
        verify(userRepository, never()).deleteAll(any());
        assertEquals("test-only-encoded-value", student.getPassword());
        assertEquals(DemoDataSeeder.DEMO_DATA_VERSION, settings.getDemoDataVersion());
        verifyNoInteractions(projectRepository, partyRepository, applicationRepository, submissionRepository,
                scorecardRepository, evaluationReportRepository, notificationRepository,
                notificationPreferenceRepository, studentProfileRepository, companyProfileRepository,
                supervisorProfileRepository, invitationRepository, moderationEventRepository);
        verify(applicationSettingsRepository, never()).save(any());
    }

    @Test
    void missingDemoSecretNeverCopiesOperatorPasswordOrDeletesAnything() {
        ReflectionTestUtils.setField(seeder, "demoPassword", " ");

        seeder.seed(coordinator);

        assertNoFixtureWrites();
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void newerSeedVersionIsNeverDowngradedEvenWithResetEnabled() {
        var settings = currentSettings();
        settings.setDemoDataVersion(DemoDataSeeder.DEMO_DATA_VERSION + 1);

        seeder.seed(coordinator);

        assertNoFixtureWrites();
    }

    @Test
    void locksSingletonBeforeReadingVersionOrLookingUpFixtureAccounts() throws Exception {
        var query = ApplicationSettingsRepository.class.getMethod("findByIdForUpdate", Short.class);
        assertEquals(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE,
                query.getAnnotation(org.springframework.data.jpa.repository.Lock.class).value());
        assertEquals("SELECT settings FROM ApplicationSettings settings WHERE settings.id = :id",
                query.getAnnotation(org.springframework.data.jpa.repository.Query.class).value());
        assertNotNull(DemoDataSeeder.class.getMethod("seed", User.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class));

        seeder.seed(coordinator);

        var order = org.mockito.Mockito.inOrder(applicationSettingsRepository, userRepository);
        order.verify(applicationSettingsRepository).findByIdForUpdate(com.talentbridge.entity.ApplicationSettings.ID);
        order.verify(userRepository).findByEmail(DemoDataSeeder.LEGACY_USER_EMAILS.get(0));
    }

    @Test
    void missingSingletonCannotBypassResetSerialization() {
        when(applicationSettingsRepository.findByIdForUpdate(com.talentbridge.entity.ApplicationSettings.ID))
                .thenReturn(java.util.Optional.empty());

        seeder.seed(coordinator);

        assertNoFixtureWrites();
        verifyNoInteractions(userRepository);
    }

    @Test
    void operatorCannotBeReconfiguredToAResettableDemoIdentity() {
        coordinator.setEmail(DemoDataSeeder.DEMO_COORDINATOR_EMAIL);

        seeder.seed(coordinator);

        assertNoFixtureWrites();
    }

    @Test
    void approvedResetDeletesExtraDemoOwnedWorkButPreservesNonDemoRecords() {
        User demo = legacyAccount();
        User other = account("real.person@example.test");
        var company = com.talentbridge.entity.CompanyProfile.builder().user(demo).build();
        company.setId(java.util.UUID.randomUUID());
        Project voicebot = Project.builder().createdBy(demo).company(company)
                .title("AI Voicebot Development").approvedBy(coordinator).build();
        Project voiceAgent = Project.builder().createdBy(demo).company(company)
                .title("AI Voice Agent Development").build();
        Project nonDemo = Project.builder().createdBy(other).title("Preserved project").build();
        Party party = Party.builder().name("Custom demo-owned party").leader(demo)
                .members(Set.of(demo)).assignedProject(voicebot).build();
        Application application = Application.builder().party(party).project(voiceAgent).build();
        Submission submission = Submission.builder().party(party).project(voicebot).build();
        var evaluation = com.talentbridge.entity.EvaluationReport.builder()
                .submission(submission).triggeredBy(coordinator).build();
        party.setSubmission(submission);
        submission.setEvaluationReport(evaluation);
        party.getApplications().add(application);
        voiceAgent.getApplications().add(application);
        demo.setCompanyProfile(company);
        var scorecard = com.talentbridge.entity.Scorecard.builder().student(demo).build();
        scorecard.getEntries().add(com.talentbridge.entity.ScorecardEntry.builder()
                .scorecard(scorecard).project(voicebot).evaluationReport(evaluation).build());
        Notification demoNotice = Notification.builder().recipient(demo).title("Custom notice").build();
        Notification realNotice = Notification.builder().recipient(other).title("Demo data ready")
                .message("Representative demo records are available.").build();
        Notification operatorNotice = Notification.builder().recipient(coordinator).title("Preserve operator notice").build();
        when(companyProfileRepository.findAll()).thenReturn(List.of(company));
        when(projectRepository.findAll()).thenReturn(List.of(voicebot, voiceAgent, nonDemo));
        when(partyRepository.findAll()).thenReturn(List.of(party));
        when(applicationRepository.findAll()).thenReturn(List.of(application));
        when(submissionRepository.findAll()).thenReturn(List.of(submission));
        when(evaluationReportRepository.findAll()).thenReturn(List.of(evaluation));
        when(scorecardRepository.findAll()).thenReturn(List.of(scorecard));
        when(notificationRepository.findAll()).thenReturn(List.of(demoNotice, realNotice, operatorNotice));

        seeder.seed(coordinator);

        var order = org.mockito.Mockito.inOrder(scorecardRepository, evaluationReportRepository,
                applicationRepository, submissionRepository, partyRepository, projectRepository, companyProfileRepository,
                userRepository);
        order.verify(scorecardRepository).delete(scorecard);
        order.verify(evaluationReportRepository).delete(evaluation);
        order.verify(applicationRepository).delete(application);
        order.verify(submissionRepository).delete(submission);
        order.verify(partyRepository).delete(party);
        order.verify(projectRepository).delete(voicebot);
        order.verify(projectRepository).delete(voiceAgent);
        order.verify(companyProfileRepository).delete(company);
        order.verify(userRepository).deleteAll(List.of(demo));
        verify(projectRepository, never()).delete(nonDemo);
        verify(notificationRepository).delete(demoNotice);
        verify(notificationRepository, never()).delete(realNotice);
        verify(notificationRepository, never()).delete(operatorNotice);
        verify(notificationPreferenceRepository, never()).deleteByUserId(coordinator.getId());
        assertNull(party.getSubmission());
        assertNull(submission.getEvaluationReport());
        assertTrue(party.getApplications().isEmpty());
        assertTrue(voiceAgent.getApplications().isEmpty());
        assertNull(demo.getCompanyProfile());
        verify(applicationSettingsRepository).save(argThat(settings ->
                Integer.valueOf(DemoDataSeeder.DEMO_DATA_VERSION).equals(settings.getDemoDataVersion())));
    }

    @Test
    void blocksResetForNonDemoProjectMerelySupervisedByDemoUser() {
        User demo = legacyAccount();
        Project external = Project.builder().createdBy(account("real.owner@example.test"))
                .projectSupervisor(demo).build();
        when(projectRepository.findAll()).thenReturn(List.of(external));

        seeder.seed(coordinator);

        assertNoFixtureWrites();
    }

    @Test
    void blocksResetForNonDemoMemberInDemoOwnedParty() {
        User demo = legacyAccount();
        Party shared = Party.builder().leader(demo).members(Set.of(demo, account("real.student@example.test"))).build();
        when(partyRepository.findAll()).thenReturn(List.of(shared));

        seeder.seed(coordinator);

        assertNoFixtureWrites();
    }

    @Test
    void blocksResetForExternalApplicationToDemoProject() {
        User demo = legacyAccount();
        Project project = Project.builder().createdBy(demo).build();
        when(projectRepository.findAll()).thenReturn(List.of(project));
        when(applicationRepository.findAll()).thenReturn(List.of(Application.builder()
                .party(Party.builder().leader(account("real.student@example.test")).build()).project(project).build()));

        seeder.seed(coordinator);

        assertNoFixtureWrites();
    }

    @Test
    void blocksResetForSubmissionLinkingDemoPartyToExternalProject() {
        User demo = legacyAccount();
        Party party = Party.builder().leader(demo).members(Set.of(demo)).build();
        when(partyRepository.findAll()).thenReturn(List.of(party));
        when(submissionRepository.findAll()).thenReturn(List.of(Submission.builder().party(party)
                .project(Project.builder().createdBy(account("real.owner@example.test")).build()).build()));

        seeder.seed(coordinator);

        assertNoFixtureWrites();
    }

    @Test
    void blocksResetForDemoStudentScoresOnExternalEvaluation() {
        User demo = legacyAccount();
        var evaluation = com.talentbridge.entity.EvaluationReport.builder()
                .submission(Submission.builder().build()).build();
        evaluation.getStudentScores().add(com.talentbridge.entity.StudentEvaluationScore.builder().student(demo).build());
        when(evaluationReportRepository.findAll()).thenReturn(List.of(evaluation));

        seeder.seed(coordinator);

        assertNoFixtureWrites();
    }

    @Test
    void blocksResetForExternalScorecardReferencingDemoProject() {
        User demo = legacyAccount();
        Project project = Project.builder().createdBy(demo).build();
        when(projectRepository.findAll()).thenReturn(List.of(project));
        var card = com.talentbridge.entity.Scorecard.builder().student(account("real.student@example.test")).build();
        card.getEntries().add(com.talentbridge.entity.ScorecardEntry.builder().project(project).build());
        when(scorecardRepository.findAll()).thenReturn(List.of(card));

        seeder.seed(coordinator);

        assertNoFixtureWrites();
    }

    @Test
    void blocksResetForExternalSupervisorEmployedByDemoCompany() {
        User demo = legacyAccount();
        var company = com.talentbridge.entity.CompanyProfile.builder().user(demo).build();
        company.setId(java.util.UUID.randomUUID());
        when(companyProfileRepository.findAll()).thenReturn(List.of(company));
        when(supervisorProfileRepository.findAll()).thenReturn(List.of(com.talentbridge.entity.SupervisorProfile.builder()
                .user(account("real.supervisor@example.test")).company(company).build()));

        seeder.seed(coordinator);

        assertNoFixtureWrites();
    }

    private com.talentbridge.entity.ApplicationSettings currentSettings() {
        var settings = new com.talentbridge.entity.ApplicationSettings();
        settings.setDemoDataVersion(DemoDataSeeder.DEMO_DATA_VERSION);
        when(applicationSettingsRepository.findByIdForUpdate(com.talentbridge.entity.ApplicationSettings.ID))
                .thenReturn(java.util.Optional.of(settings));
        return settings;
    }

    private User account(String email) {
        User user = User.builder().email(email).role(UserRole.STUDENT).build();
        user.setId(java.util.UUID.randomUUID());
        return user;
    }

    private User legacyAccount() {
        User demo = account("alice.student@talentbridge.com");
        when(userRepository.findByEmail(demo.getEmail())).thenReturn(java.util.Optional.of(demo));
        return demo;
    }

    private void assertNoFixtureWrites() {
        for (Object repository : List.of(userRepository, studentProfileRepository, companyProfileRepository,
                projectRepository, partyRepository, applicationRepository, submissionRepository, notificationRepository,
                applicationSettingsRepository, supervisorProfileRepository, invitationRepository, evaluationReportRepository,
                scorecardRepository, notificationPreferenceRepository, moderationEventRepository)) {
            var writes = org.mockito.Mockito.mockingDetails(repository).getInvocations().stream()
                    .map(invocation -> invocation.getMethod().getName())
                    .filter(name -> name.startsWith("save") || name.startsWith("delete") || name.equals("flush")).toList();
            assertTrue(writes.isEmpty(), "Blocked reset must not issue writes: " + writes);
        }
    }

    private long count(Iterable<?> items) {
        return StreamSupport.stream(items.spliterator(), false).count();
    }
}
