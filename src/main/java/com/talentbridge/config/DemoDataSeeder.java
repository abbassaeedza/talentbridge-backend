package com.talentbridge.config;

import com.talentbridge.entity.Application;
import com.talentbridge.entity.CompanyProfile;
import com.talentbridge.entity.Notification;
import com.talentbridge.entity.Party;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.StudentProfile;
import com.talentbridge.entity.Submission;
import com.talentbridge.entity.User;
import com.talentbridge.enums.ApplicationStatus;
import com.talentbridge.enums.NotificationType;
import com.talentbridge.enums.PartyStatus;
import com.talentbridge.enums.ProjectStatus;
import com.talentbridge.enums.SubmissionStatus;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@Slf4j
public class DemoDataSeeder {

    static final String MARKER_EMAIL = "demo.company@talentbridge.com";

    private final UserRepository userRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final ProjectRepository projectRepository;
    private final PartyRepository partyRepository;
    private final ApplicationRepository applicationRepository;
    private final SubmissionRepository submissionRepository;
    private final NotificationRepository notificationRepository;

    public void seed(User coordinator) {
        if (userRepository.existsByEmail(MARKER_EMAIL)) {
            log.info("Representative demo data already exists - skipping seed.");
            return;
        }

        String password = coordinator.getPassword();
        User company = user(MARKER_EMAIL, "Nexa", "Systems",
                UserRole.COMPANY, UserStatus.APPROVED, password);
        User partySupervisor = user("demo.party-supervisor@talentbridge.com", "Sara", "Ahmed",
                UserRole.PARTY_SUPERVISOR, UserStatus.APPROVED, password);
        User projectSupervisor = user("demo.project-supervisor@talentbridge.com", "Omar", "Khan",
                UserRole.PROJECT_SUPERVISOR, UserStatus.APPROVED, password);
        List<User> students = List.of(
                user("alice.student@talentbridge.com", "Alice", "Ali", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("bob.student@talentbridge.com", "Bob", "Raza", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("carol.student@talentbridge.com", "Carol", "Iqbal", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("dan.student@talentbridge.com", "Dan", "Malik", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("erin.student@talentbridge.com", "Erin", "Shah", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("pending.student@talentbridge.com", "Pending", "Student", UserRole.STUDENT, UserStatus.PENDING, password),
                user("rejected.student@talentbridge.com", "Rejected", "Student", UserRole.STUDENT, UserStatus.REJECTED, password),
                user("suspended.student@talentbridge.com", "Suspended", "Student", UserRole.STUDENT, UserStatus.SUSPENDED, password));

        userRepository.saveAll(Stream.concat(
                Stream.of(company, partySupervisor, projectSupervisor), students.stream()).toList());

        CompanyProfile companyProfile = CompanyProfile.builder()
                .user(company)
                .companyName("Nexa Systems")
                .industry("Software")
                .description("Product engineering and analytics company.")
                .website("https://example.com/nexa-systems")
                .country("Pakistan")
                .city("Islamabad")
                .build();
        companyProfileRepository.save(companyProfile);
        studentProfileRepository.saveAll(students.stream().map(this::studentProfile).toList());

        List<Project> projects = buildProjects(company, companyProfile, projectSupervisor, coordinator);
        projectRepository.saveAll(projects);
        List<Party> parties = buildParties(students, partySupervisor, projects);
        partyRepository.saveAll(parties);
        applicationRepository.saveAll(buildApplications(parties, projects));
        submissionRepository.saveAll(buildSubmissions(parties, projects));
        notificationRepository.saveAll(buildNotifications(coordinator));
        log.info("Representative TalentBridge demo data created.");
    }

    private User user(String email, String firstName, String lastName, UserRole role,
                      UserStatus status, String password) {
        return User.builder()
                .email(email)
                .password(password)
                .firstName(firstName)
                .lastName(lastName)
                .role(role)
                .status(status)
                .emailVerified(status != UserStatus.PENDING)
                .rejectionReason(status == UserStatus.REJECTED ? "Demo rejection example" : null)
                .build();
    }

    private StudentProfile studentProfile(User user) {
        return StudentProfile.builder()
                .user(user)
                .age(22)
                .university("National University")
                .yearOfStudy("Final Year")
                .major("Computer Science")
                .skills(new ArrayList<>(List.of("Java", "React", "PostgreSQL")))
                .bio("Demo student profile for TalentBridge testing.")
                .gpa(3.5)
                .build();
    }

    private List<Project> buildProjects(User company, CompanyProfile profile,
                                        User supervisor, User coordinator) {
        LocalDateTime now = LocalDateTime.now();
        return List.of(
                project("AI Support Copilot", ProjectStatus.OPEN, company, profile,
                        supervisor, coordinator, now.plusDays(14)),
                project("Hiring Analytics Dashboard", ProjectStatus.PENDING_REVIEW, company,
                        profile, null, null, null),
                project("Cloud Cost Optimiser", ProjectStatus.ASSIGNED, company, profile,
                        supervisor, coordinator, now.plusDays(30)),
                project("Partner Portal Refresh", ProjectStatus.DRAFT, company,
                        profile, null, null, null),
                project("Legacy Process Digitisation", ProjectStatus.CLOSED, company,
                        profile, supervisor, coordinator, now.minusDays(7)),
                project("Coordinator Sandbox Project", ProjectStatus.ARCHIVED, company,
                        profile, null, coordinator, null));
    }

    private Project project(String title, ProjectStatus status, User owner,
                            CompanyProfile company, User supervisor, User approver,
                            LocalDateTime deadline) {
        return Project.builder()
                .title(title)
                .internalName("Demo " + title)
                .projectField("Software")
                .description("Representative TalentBridge demo project.")
                .scope("Plan, build, test, and present the requested product.")
                .deliverables("Source code, documentation, tests, and final presentation.")
                .evaluationCriteria("Functionality, quality, usability, and collaboration.")
                .tools(new ArrayList<>(List.of("React", "Spring Boot", "PostgreSQL")))
                .status(status)
                .createdBy(owner)
                .company(company)
                .projectSupervisor(supervisor)
                .approvedBy(approver)
                .deadline(deadline)
                .build();
    }

    private List<Party> buildParties(List<User> students, User supervisor,
                                     List<Project> projects) {
        return List.of(
                party("Team Alpha Demo", students.get(0), supervisor,
                        PartyStatus.ASSIGNED, projects.get(2)),
                party("Team Beta Demo", students.get(1), supervisor,
                        PartyStatus.SUBMITTED, projects.get(4)),
                party("Team Gamma Demo", students.get(2), supervisor,
                        PartyStatus.ACTIVE, null),
                party("Team Delta Demo", students.get(3), null,
                        PartyStatus.FORMING, null),
                party("Team Epsilon Demo", students.get(4), supervisor,
                        PartyStatus.COMPLETED, null));
    }

    private Party party(String name, User leader, User supervisor, PartyStatus status,
                        Project project) {
        return Party.builder()
                .name(name)
                .leader(leader)
                .members(new HashSet<>(Set.of(leader)))
                .supervisor(supervisor)
                .status(status)
                .semester("Fall")
                .academicYear(2026)
                .assignedProject(project)
                .build();
    }

    private List<Application> buildApplications(List<Party> parties, List<Project> projects) {
        return List.of(
                application(parties.get(0), projects.get(2), 1, ApplicationStatus.ASSIGNED),
                application(parties.get(2), projects.get(0), 1, ApplicationStatus.PENDING),
                application(parties.get(3), projects.get(5), 2, ApplicationStatus.REJECTED));
    }

    private Application application(Party party, Project project, int rank,
                                    ApplicationStatus status) {
        return Application.builder()
                .party(party)
                .project(project)
                .rankPosition(rank)
                .proposalText("Representative demo proposal for " + project.getTitle())
                .status(status)
                .build();
    }

    private List<Submission> buildSubmissions(List<Party> parties, List<Project> projects) {
        return List.of(
                Submission.builder()
                        .party(parties.get(0))
                        .project(projects.get(2))
                        .repoUrl("https://github.com/talentbridge-demo/cloud-cost-optimiser")
                        .repoBranch("main")
                        .status(SubmissionStatus.DRAFT)
                        .notes("Draft demo submission.")
                        .build(),
                Submission.builder()
                        .party(parties.get(1))
                        .project(projects.get(4))
                        .repoUrl("https://github.com/talentbridge-demo/process-digitisation")
                        .repoBranch("main")
                        .status(SubmissionStatus.SUBMITTED)
                        .submittedAt(LocalDateTime.now().minusDays(2))
                        .notes("Submitted demo work.")
                        .build());
    }

    private List<Notification> buildNotifications(User coordinator) {
        return List.of(
                notification(coordinator, NotificationType.GENERAL,
                        "Demo data ready", "Representative demo records are available.", true),
                notification(coordinator, NotificationType.NEW_PROJECT_POSTED,
                        "Project awaiting review", "Hiring Analytics Dashboard needs review.", false),
                notification(coordinator, NotificationType.DEADLINE_REMINDER,
                        "Deadline scheduled", "AI Support Copilot has an active deadline.", false));
    }

    private Notification notification(User recipient, NotificationType type, String title,
                                      String message, boolean read) {
        return Notification.builder()
                .recipient(recipient)
                .type(type)
                .title(title)
                .message(message)
                .read(read)
                .build();
    }
}
