package com.talentbridge.config;

import com.talentbridge.entity.Application;
import com.talentbridge.entity.Notification;
import com.talentbridge.entity.Party;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.Submission;
import com.talentbridge.entity.User;
import com.talentbridge.enums.PartyStatus;
import com.talentbridge.enums.ProjectStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    }

    @Test
    void reconcilesGuardrailDataWhenTheDemoDatasetAlreadyExists() {
        when(userRepository.existsByEmail(DemoDataSeeder.MARKER_EMAIL)).thenReturn(true);

        seeder.seed(coordinator);

        verify(userRepository).saveAll(argThat(users -> count(users) == 4));
        verify(studentProfileRepository).saveAll(argThat(profiles -> count(profiles) == 4));
        verify(partyRepository).findAll();
        verify(partyRepository, times(0)).saveAll(any());
        verifyNoInteractions(companyProfileRepository, projectRepository,
                applicationRepository, submissionRepository, notificationRepository);
    }

    @Test
    void createsRepresentativeDatasetOnce() {
        when(userRepository.existsByEmail(DemoDataSeeder.MARKER_EMAIL)).thenReturn(false);

        seeder.seed(coordinator);

        verify(userRepository).saveAll(argThat(users -> {
            var saved = StreamSupport.stream(users.spliterator(), false).toList();
            return saved.size() == 15
                    && saved.stream().map(User::getStatus).collect(Collectors.toSet())
                    .equals(Set.of(UserStatus.PENDING, UserStatus.APPROVED,
                            UserStatus.REJECTED, UserStatus.SUSPENDED));
        }));
        verify(studentProfileRepository).saveAll(argThat(profiles ->
                StreamSupport.stream(profiles.spliterator(), false).count() == 12));
        verify(companyProfileRepository).save(any());
        verify(projectRepository).saveAll(argThat(projects -> {
            var statuses = StreamSupport.stream(projects.spliterator(), false)
                    .map(Project::getStatus)
                    .collect(Collectors.toSet());
            return statuses.equals(Set.of(ProjectStatus.DRAFT, ProjectStatus.PENDING_REVIEW,
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
        verify(applicationRepository).saveAll(argThat(items -> count(items) == 3));
        verify(submissionRepository).saveAll(argThat(items -> count(items) == 2));
        verify(notificationRepository).saveAll(argThat(items -> count(items) == 3));
    }

    private long count(Iterable<?> items) {
        return StreamSupport.stream(items.spliterator(), false).count();
    }
}
