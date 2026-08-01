package com.talentbridge.config;

import com.talentbridge.entity.Application;
import com.talentbridge.entity.ApplicationSettings;
import com.talentbridge.entity.CompanyProfile;
import com.talentbridge.entity.EvaluationReport;
import com.talentbridge.entity.Notification;
import com.talentbridge.entity.NotificationPreference;
import com.talentbridge.entity.Party;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.ProjectSupervisorInvitation;
import com.talentbridge.entity.Scorecard;
import com.talentbridge.entity.ScorecardEntry;
import com.talentbridge.entity.StudentProfile;
import com.talentbridge.entity.Submission;
import com.talentbridge.entity.SupervisorProfile;
import com.talentbridge.entity.User;
import com.talentbridge.enums.ApplicationStatus;
import com.talentbridge.enums.NotificationType;
import com.talentbridge.enums.PartyStatus;
import com.talentbridge.enums.ProjectStatus;
import com.talentbridge.enums.ProjectSupervisorInvitationStatus;
import com.talentbridge.enums.SubmissionStatus;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.repository.ApplicationRepository;
import com.talentbridge.repository.ApplicationSettingsRepository;
import com.talentbridge.repository.CompanyProfileRepository;
import com.talentbridge.repository.NotificationRepository;
import com.talentbridge.repository.PartyRepository;
import com.talentbridge.repository.ProjectRepository;
import com.talentbridge.repository.StudentProfileRepository;
import com.talentbridge.repository.SubmissionRepository;
import com.talentbridge.repository.SupervisorProfileRepository;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.repository.EvaluationReportRepository;
import com.talentbridge.repository.ScorecardRepository;
import com.talentbridge.repository.ProjectSupervisorInvitationRepository;
import com.talentbridge.repository.NotificationPreferenceRepository;
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
    static final int DEMO_DATA_VERSION = 2;
    private static final Set<String> DEMO_PARTY_NAMES = Set.of(
            "Team Alpha Demo", "Team Beta Demo", "Team Gamma Demo", "Team Delta Demo", "Team Epsilon Demo");

    private final UserRepository userRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final ProjectRepository projectRepository;
    private final PartyRepository partyRepository;
    private final ApplicationRepository applicationRepository;
    private final SubmissionRepository submissionRepository;
    private final NotificationRepository notificationRepository;
    private final ApplicationSettingsRepository applicationSettingsRepository;
    private final SupervisorProfileRepository supervisorProfileRepository;
    private final ProjectSupervisorInvitationRepository invitationRepository;
    private final EvaluationReportRepository evaluationReportRepository;
    private final ScorecardRepository scorecardRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;

    public void seed(User coordinator) {
        ApplicationSettings settings = applicationSettingsRepository.findById(ApplicationSettings.ID)
                .orElseGet(ApplicationSettings::new);
        if (Integer.valueOf(DEMO_DATA_VERSION).equals(settings.getDemoDataVersion())) return;
        if (userRepository.existsByEmail(MARKER_EMAIL)) clearDemoFixtures();

        String password = coordinator.getPassword();
        User company = user(MARKER_EMAIL, "Nexa", "Systems",
                UserRole.COMPANY, UserStatus.APPROVED, password);
        User partySupervisor = user("demo.party-supervisor@talentbridge.com", "Sara", "Ahmed",
                UserRole.PARTY_SUPERVISOR, UserStatus.APPROVED, password);
        User projectSupervisor = user("demo.project-supervisor@talentbridge.com", "Omar", "Khan",
                UserRole.PROJECT_SUPERVISOR, UserStatus.APPROVED, password);
        User pendingPartySupervisor = user("pending.party-supervisor@talentbridge.com", "Nadia", "Riaz",
                UserRole.PARTY_SUPERVISOR, UserStatus.PENDING, password);
        List<User> students = List.of(
                user("alice.student@talentbridge.com", "Alice", "Ali", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("bob.student@talentbridge.com", "Bob", "Raza", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("carol.student@talentbridge.com", "Carol", "Iqbal", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("dan.student@talentbridge.com", "Dan", "Malik", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("erin.student@talentbridge.com", "Erin", "Shah", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("farah.student@talentbridge.com", "Farah", "Noor", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("gibran.student@talentbridge.com", "Gibran", "Saeed", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("hana.student@talentbridge.com", "Hana", "Yusuf", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("imran.student@talentbridge.com", "Imran", "Qureshi", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("javed.student@talentbridge.com", "Javed", "Iqbal", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("pending.student@talentbridge.com", "Pending", "Student", UserRole.STUDENT, UserStatus.PENDING, password),
                user("rejected.student@talentbridge.com", "Rejected", "Student", UserRole.STUDENT, UserStatus.REJECTED, password),
                user("suspended.student@talentbridge.com", "Suspended", "Student", UserRole.STUDENT, UserStatus.SUSPENDED, password));

        userRepository.saveAll(Stream.concat(
                Stream.of(company, partySupervisor, projectSupervisor, pendingPartySupervisor), students.stream()).toList());

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
        supervisorProfileRepository.saveAll(List.of(
                supervisorProfile(partySupervisor, null, "Faculty Advisor"),
                supervisorProfile(projectSupervisor, companyProfile, "Delivery Lead")));
        invitationRepository.save(ProjectSupervisorInvitation.builder()
                .company(companyProfile).email("pending.project-supervisor@talentbridge.com")
                .tokenHash("e2b7d45387ec4b8a8d27de3c904c8d57c7f7dced2756f7afbb85b42aec4e7d26")
                .status(ProjectSupervisorInvitationStatus.PENDING).expiresAt(LocalDateTime.now().plusDays(7)).build());

        List<Project> projects = buildProjects(company, companyProfile, projectSupervisor, coordinator);
        projectRepository.saveAll(projects);
        List<Party> parties = buildParties(students, partySupervisor, projects);
        partyRepository.saveAll(parties);
        applicationRepository.saveAll(buildApplications(parties, projects));
        List<Submission> submissions = buildSubmissions(parties, projects);
        submissionRepository.saveAll(submissions);
        EvaluationReport evaluation = EvaluationReport.builder().submission(submissions.get(2))
                .codeQualityScore(86.0).functionalityScore(90.0).scopeAlignmentScore(88.0)
                .teamCollaborationScore(92.0).aiDetectionScore(4.0).totalScore(89.0)
                .overallSummary("Representative evaluated demo work.").triggeredBy(coordinator)
                .evaluatedAt(LocalDateTime.now().minusDays(1)).finalized(true).build();
        evaluationReportRepository.save(evaluation);
        Scorecard scorecard = Scorecard.builder().student(students.get(4)).averageScore(89.0)
                .totalProjects(1).build();
        ScorecardEntry entry = ScorecardEntry.builder().scorecard(scorecard)
                .project(projects.get(6)).evaluationReport(evaluation).score(89.0)
                .semester("Fall").academicYear(2026).build();
        scorecard.getEntries().add(entry);
        scorecardRepository.save(scorecard);
        notificationRepository.saveAll(buildNotifications(coordinator));
        notificationPreferenceRepository.save(NotificationPreference.builder()
                .user(coordinator).type(NotificationType.DEADLINE_REMINDER).emailEnabled(false).build());
        settings.setDemoDataVersion(DEMO_DATA_VERSION);
        applicationSettingsRepository.save(settings);
        log.info("Representative TalentBridge demo data created.");
    }

    private SupervisorProfile supervisorProfile(User user, CompanyProfile company, String title) {
        return SupervisorProfile.builder().user(user).company(company).jobTitle(title)
                .department("Engineering").bio("Representative TalentBridge supervisor profile.")
                .linkedinUrl("https://linkedin.com/in/" + user.getFirstName().toLowerCase()).build();
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
                        profile, null, null, now.plusDays(2)),
                project("Cloud Cost Optimiser", ProjectStatus.ASSIGNED, company, profile,
                        supervisor, coordinator, now.minusDays(1)),
                project("Partner Portal Refresh", ProjectStatus.DRAFT, company,
                        profile, null, null, null),
                project("Legacy Process Digitisation", ProjectStatus.CLOSED, company,
                        profile, supervisor, coordinator, now.minusDays(7)),
                project("Coordinator Sandbox Project", ProjectStatus.ARCHIVED, company,
                        profile, null, coordinator, null),
                project("Evaluated Marketplace Prototype", ProjectStatus.CLOSED, company,
                        profile, supervisor, coordinator, now.minusDays(14)),
                project("Infrastructure Monitor", ProjectStatus.OPEN, company,
                        profile, supervisor, coordinator, now.plusDays(7)));
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
                party("Team Alpha Demo", List.of(students.get(0), students.get(5), students.get(8)), supervisor,
                        PartyStatus.ASSIGNED, projects.get(2)),
                party("Team Beta Demo", List.of(students.get(1), students.get(6)), supervisor,
                        PartyStatus.SUBMITTED, projects.get(4)),
                party("Team Gamma Demo", List.of(students.get(2), students.get(7)), null,
                        PartyStatus.ACTIVE, null),
                party("Team Delta Demo", List.of(students.get(3)), null,
                        PartyStatus.FORMING, null),
                party("Team Epsilon Demo", List.of(students.get(4), students.get(9)), null,
                        PartyStatus.COMPLETED, projects.get(6)));
    }

    private Party party(String name, List<User> members, User supervisor, PartyStatus status,
                        Project project) {
        return Party.builder()
                .name(name)
                .leader(members.get(0))
                .members(new HashSet<>(members))
                .supervisor(supervisor)
                .status(status)
                .semester("Fall")
                .academicYear(2026)
                .assignedProject(project)
                .build();
    }

    private void clearDemoFixtures() {
        List<User> demoUsers = Stream.of(
                        MARKER_EMAIL, "demo.party-supervisor@talentbridge.com",
                        "demo.project-supervisor@talentbridge.com", "pending.party-supervisor@talentbridge.com",
                        "alice.student@talentbridge.com",
                        "bob.student@talentbridge.com", "carol.student@talentbridge.com", "dan.student@talentbridge.com",
                        "erin.student@talentbridge.com", "farah.student@talentbridge.com", "gibran.student@talentbridge.com",
                        "hana.student@talentbridge.com", "imran.student@talentbridge.com", "javed.student@talentbridge.com", "pending.student@talentbridge.com",
                        "rejected.student@talentbridge.com", "suspended.student@talentbridge.com")
                .map(userRepository::findByEmail).flatMap(java.util.Optional::stream).toList();
        if (demoUsers.isEmpty()) return;
        List<java.util.UUID> userIds = demoUsers.stream().map(User::getId).filter(java.util.Objects::nonNull).toList();
        List<Party> parties = partyRepository.findAll().stream()
                .filter(p -> DEMO_PARTY_NAMES.contains(p.getName())).toList();
        List<Submission> submissions = parties.stream().map(Party::getSubmission)
                .filter(java.util.Objects::nonNull).toList();
        submissions.forEach(submission -> evaluationReportRepository.findBySubmissionId(submission.getId())
                .ifPresent(evaluationReportRepository::delete));
        notificationRepository.findAll().stream().filter(n -> userIds.contains(n.getRecipient().getId()))
                .forEach(notificationRepository::delete);
        userIds.forEach(notificationPreferenceRepository::deleteByUserId);
        applicationRepository.findAll().stream().filter(a -> parties.contains(a.getParty()))
                .forEach(applicationRepository::delete);
        submissions.forEach(submissionRepository::delete);
        parties.forEach(partyRepository::delete);
        demoUsers.forEach(user -> {
            scorecardRepository.findByStudentId(user.getId()).ifPresent(scorecardRepository::delete);
            supervisorProfileRepository.findByUserId(user.getId()).ifPresent(supervisorProfileRepository::delete);
            studentProfileRepository.findByUserId(user.getId()).ifPresent(studentProfileRepository::delete);
        });
        demoUsers.stream().filter(user -> MARKER_EMAIL.equals(user.getEmail())).findFirst()
                .flatMap(user -> companyProfileRepository.findByUserId(user.getId()))
                .ifPresent(company -> {
                    invitationRepository.findByCompanyIdOrderByCreatedAtDesc(company.getId()).forEach(invitationRepository::delete);
                    projectRepository.findByCreatedById(company.getUser().getId()).forEach(projectRepository::delete);
                    companyProfileRepository.delete(company);
                });
        userRepository.deleteAll(demoUsers);
    }

    private List<Application> buildApplications(List<Party> parties, List<Project> projects) {
        return List.of(
                application(parties.get(0), projects.get(2), 1, ApplicationStatus.ASSIGNED),
                application(parties.get(2), projects.get(0), 1, ApplicationStatus.PENDING),
                application(parties.get(2), projects.get(7), 2, ApplicationStatus.PENDING),
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
                        .build(),
                Submission.builder()
                        .party(parties.get(4))
                        .project(projects.get(6))
                        .repoUrl("https://github.com/talentbridge-demo/marketplace-prototype")
                        .repoBranch("main")
                        .status(SubmissionStatus.EVALUATED)
                        .submittedAt(LocalDateTime.now().minusDays(10))
                        .notes("Evaluated demo work.")
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
