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
import com.talentbridge.repository.UserModerationEventRepository;
import com.talentbridge.repository.EvaluationReportRepository;
import com.talentbridge.repository.ScorecardRepository;
import com.talentbridge.repository.ProjectSupervisorInvitationRepository;
import com.talentbridge.repository.NotificationPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

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

    static final String MARKER_EMAIL = "avery.quinn@example.test";
    static final String DEMO_COORDINATOR_EMAIL = "rowan.hale@example.test";
    static final int DEMO_DATA_VERSION = 6;
    private static final java.util.Map<String, Set<String>> DEMO_NOTIFICATIONS = java.util.Map.of(
            "Demo data ready", Set.of("Representative demo records are available."),
            "New demo workspace ready", Set.of("The version 3 demo dataset is available.",
                    "The version 4 demo dataset is available.", "The version 5 demo dataset is available.",
                    "The version 6 demo dataset is available."),
            "Project awaiting review", Set.of("Hiring Analytics Dashboard needs review.",
                    "Graduate Hiring Insights needs review."),
            "Deadline scheduled", Set.of("AI Support Copilot has an active deadline.",
                    "Campus Helpdesk Copilot has an active deadline."));
    static final List<String> LEGACY_USER_EMAILS = List.of(
            "demo.company@talentbridge.com", "demo.company.second@talentbridge.com", "pending.company@talentbridge.com",
            "rejected.company@talentbridge.com", "suspended.company@talentbridge.com",
            "demo.party-supervisor@talentbridge.com", "pending.party-supervisor@talentbridge.com",
            "demo.project-supervisor@talentbridge.com", "demo.project-supervisor.second@talentbridge.com",
            "alice.student@talentbridge.com", "bob.student@talentbridge.com", "carol.student@talentbridge.com",
            "dan.student@talentbridge.com", "erin.student@talentbridge.com", "farah.student@talentbridge.com",
            "gibran.student@talentbridge.com", "hana.student@talentbridge.com", "imran.student@talentbridge.com",
            "javed.student@talentbridge.com", "pending.student@talentbridge.com",
            "rejected.student@talentbridge.com", "suspended.student@talentbridge.com");
    static final List<String> DEMO_USER_EMAILS = List.of(
            DEMO_COORDINATOR_EMAIL, MARKER_EMAIL, "ellis.wren@example.test", "hazel.reed@example.test",
            "cedar.lane@example.test", "sylvie.moss@example.test", "leona.briar@example.test",
            "tessa.grove@example.test", "orion.finch@example.test", "lyra.brook@example.test",
            "mira.shah@example.test", "felix.arden@example.test", "nora.ellis@example.test",
            "theo.marlow@example.test", "iris.vale@example.test", "jasper.willow@example.test",
            "elara.stone@example.test", "soren.ash@example.test", "freya.bell@example.test",
            "milo.hart@example.test", "ada.blair@example.test", "evan.rose@example.test",
            "lena.frost@example.test");

    @Value("${app.seed.demo-password:}")
    private String demoPassword;

    @Value("${app.seed.reset-demo-data:false}")
    private boolean resetDemoData;

    private final PasswordEncoder passwordEncoder;

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
    private final UserModerationEventRepository moderationEventRepository;

    @Transactional
    public void seed(User operator) {
        ApplicationSettings settings = applicationSettingsRepository.findByIdForUpdate(ApplicationSettings.ID)
                .orElse(null);
        if (settings == null) {
            log.warn("Demo seed skipped: singleton application settings row is missing; restore schema before retrying.");
            return;
        }
        if (LEGACY_USER_EMAILS.contains(operator.getEmail()) || DEMO_USER_EMAILS.contains(operator.getEmail())) {
            log.warn("Demo seed skipped: operator identity overlaps the demo fixture allowlist.");
            return;
        }
        if (demoPassword == null || demoPassword.isBlank()) {
            log.warn("Demo seed skipped: APP_SEED_DEMO_PASSWORD is not configured.");
            return;
        }
        if (Integer.valueOf(DEMO_DATA_VERSION).equals(settings.getDemoDataVersion())) {
            DEMO_USER_EMAILS.stream().map(userRepository::findByEmail).flatMap(java.util.Optional::stream)
                    .filter(user -> !passwordEncoder.matches(demoPassword, user.getPassword()))
                    .forEach(user -> {
                        user.setPassword(passwordEncoder.encode(demoPassword));
                        userRepository.save(user);
                    });
            return;
        }
        if (!resetDemoData || settings.getDemoDataVersion() != null
                && settings.getDemoDataVersion() > DEMO_DATA_VERSION) {
            log.warn("Demo data version mismatch (stored {}, expected {}): replacement skipped. "
                    + "Backup and reset approval are required before APP_SEED_RESET_DEMO_DATA=true.",
                    settings.getDemoDataVersion(), DEMO_DATA_VERSION);
            return;
        }
        if (!clearDemoFixtures(operator)) return;

        String password = passwordEncoder.encode(demoPassword);
        User coordinator = user(DEMO_COORDINATOR_EMAIL, "Rowan", "Hale",
                UserRole.COORDINATOR, UserStatus.APPROVED, password);
        User company = user(MARKER_EMAIL, "Avery", "Quinn",
                UserRole.COMPANY, UserStatus.APPROVED, password);
        User secondCompany = user("ellis.wren@example.test", "Ellis", "Wren",
                UserRole.COMPANY, UserStatus.APPROVED, password);
        User pendingCompany = user("hazel.reed@example.test", "Hazel", "Reed",
                UserRole.COMPANY, UserStatus.PENDING, password);
        User rejectedCompany = user("cedar.lane@example.test", "Cedar", "Lane",
                UserRole.COMPANY, UserStatus.REJECTED, password);
        User suspendedCompany = user("sylvie.moss@example.test", "Sylvie", "Moss",
                UserRole.COMPANY, UserStatus.SUSPENDED, password);
        User partySupervisor = user("leona.briar@example.test", "Leona", "Briar",
                UserRole.PARTY_SUPERVISOR, UserStatus.APPROVED, password);
        User projectSupervisor = user("orion.finch@example.test", "Orion", "Finch",
                UserRole.PROJECT_SUPERVISOR, UserStatus.APPROVED, password);
        User secondProjectSupervisor = user("lyra.brook@example.test", "Lyra", "Brook",
                UserRole.PROJECT_SUPERVISOR, UserStatus.APPROVED, password);
        User pendingPartySupervisor = user("tessa.grove@example.test", "Tessa", "Grove",
                UserRole.PARTY_SUPERVISOR, UserStatus.PENDING, password);
        List<User> students = List.of(
                user("mira.shah@example.test", "Mira", "Shah", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("felix.arden@example.test", "Felix", "Arden", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("nora.ellis@example.test", "Nora", "Ellis", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("theo.marlow@example.test", "Theo", "Marlow", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("iris.vale@example.test", "Iris", "Vale", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("jasper.willow@example.test", "Jasper", "Willow", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("elara.stone@example.test", "Elara", "Stone", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("soren.ash@example.test", "Soren", "Ash", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("freya.bell@example.test", "Freya", "Bell", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("milo.hart@example.test", "Milo", "Hart", UserRole.STUDENT, UserStatus.APPROVED, password),
                user("ada.blair@example.test", "Ada", "Blair", UserRole.STUDENT, UserStatus.PENDING, password),
                user("evan.rose@example.test", "Evan", "Rose", UserRole.STUDENT, UserStatus.REJECTED, password),
                user("lena.frost@example.test", "Lena", "Frost", UserRole.STUDENT, UserStatus.SUSPENDED, password));

        userRepository.saveAll(Stream.concat(
                Stream.of(coordinator, company, secondCompany, pendingCompany, rejectedCompany, suspendedCompany,
                        partySupervisor, projectSupervisor, secondProjectSupervisor, pendingPartySupervisor),
                students.stream()).toList());

        CompanyProfile companyProfile = companyProfile(company, "Vertex Labs", "Software", "Islamabad");
        CompanyProfile secondCompanyProfile = companyProfile(secondCompany, "Harbor Analytics", "Data Analytics", "Karachi");
        companyProfileRepository.saveAll(List.of(
                companyProfile, secondCompanyProfile,
                companyProfile(pendingCompany, "Nova Robotics", "Robotics", "Lahore"),
                companyProfile(rejectedCompany, "Legacy Ventures", "Consulting", "Islamabad"),
                companyProfile(suspendedCompany, "Summit Digital", "Software", "Karachi")));
        studentProfileRepository.saveAll(students.stream().map(this::studentProfile).toList());
        supervisorProfileRepository.saveAll(List.of(
                supervisorProfile(partySupervisor, null, "Faculty Advisor"),
                supervisorProfile(projectSupervisor, companyProfile, "Delivery Lead"),
                supervisorProfile(secondProjectSupervisor, secondCompanyProfile, "Data Programme Manager")));
        invitationRepository.saveAll(buildInvitations(companyProfile, secondCompanyProfile, projectSupervisor));

        List<Project> projects = buildProjects(company, companyProfile, projectSupervisor,
                secondCompany, secondCompanyProfile, secondProjectSupervisor, coordinator);
        projectRepository.saveAll(projects);
        List<Party> parties = buildParties(students, partySupervisor, projects);
        partyRepository.saveAll(parties);
        applicationRepository.saveAll(buildApplications(parties, projects));
        List<Submission> submissions = buildSubmissions(parties, projects);
        submissionRepository.saveAll(submissions);
        EvaluationReport evaluation = EvaluationReport.builder().submission(submissions.get(2))
                .codeQualityScore(86.0).functionalityScore(90.0).scopeAlignmentScore(88.0)
                .teamCollaborationScore(92.0).aiDetectionScore(4.0).totalScore(89.0)
                .overallSummary("The marketplace met its scope and demonstrated strong team delivery.").triggeredBy(coordinator)
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
        NotificationPreference deadlinePreference = notificationPreferenceRepository
                .findByUserIdAndType(coordinator.getId(), NotificationType.DEADLINE_REMINDER)
                .orElseGet(() -> NotificationPreference.builder()
                        .user(coordinator).type(NotificationType.DEADLINE_REMINDER).build());
        deadlinePreference.setEmailEnabled(false);
        notificationPreferenceRepository.save(deadlinePreference);
        settings.setDemoDataVersion(DEMO_DATA_VERSION);
        applicationSettingsRepository.save(settings);
        log.info("Representative TalentBridge demo data created.");
    }

    private CompanyProfile companyProfile(User user, String name, String industry, String city) {
        return CompanyProfile.builder().user(user).companyName(name).industry(industry)
                .description("Representative TalentBridge partner company.")
                .website("https://example.com/" + name.toLowerCase().replace(' ', '-'))
                .country("Pakistan").city(city).build();
    }

    private SupervisorProfile supervisorProfile(User user, CompanyProfile company, String title) {
        return SupervisorProfile.builder().user(user).company(company).jobTitle(title)
                .department("Engineering").bio("Representative TalentBridge supervisor profile.")
                .linkedinUrl("https://linkedin.com/in/" + user.getFirstName().toLowerCase()).build();
    }

    private List<ProjectSupervisorInvitation> buildInvitations(CompanyProfile primary,
                                                                 CompanyProfile secondary,
                                                                 User acceptedSupervisor) {
        LocalDateTime now = LocalDateTime.now();
        return List.of(
                invitation(primary, "piper.dale@example.test", "1", ProjectSupervisorInvitationStatus.PENDING, now.plusDays(7), null),
                invitation(primary, "silas.fern@example.test", "2", ProjectSupervisorInvitationStatus.EXPIRED, now.minusDays(1), null),
                invitation(primary, acceptedSupervisor.getEmail(), "3", ProjectSupervisorInvitationStatus.ACCEPTED, now.plusDays(7), now.minusDays(2)),
                invitation(secondary, "clara.lark@example.test", "4", ProjectSupervisorInvitationStatus.REVOKED, now.plusDays(7), null));
    }

    private ProjectSupervisorInvitation invitation(CompanyProfile company, String email, String hashDigit,
                                                    ProjectSupervisorInvitationStatus status,
                                                    LocalDateTime expiresAt, LocalDateTime acceptedAt) {
        return ProjectSupervisorInvitation.builder().company(company).email(email)
                .tokenHash(hashDigit.repeat(64)).status(status).expiresAt(expiresAt).acceptedAt(acceptedAt).build();
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

    private List<Project> buildProjects(User company, CompanyProfile profile, User supervisor,
                                        User secondCompany, CompanyProfile secondProfile,
                                        User secondSupervisor, User coordinator) {
        LocalDateTime now = LocalDateTime.now();
        return List.of(
                project("Campus Helpdesk Copilot", ProjectStatus.OPEN, company, profile,
                        supervisor, coordinator, now.plusDays(14)),
                project("Graduate Hiring Insights", ProjectStatus.PENDING_REVIEW, company,
                        profile, null, null, now.plusDays(2)),
                project("Smart Energy Optimiser", ProjectStatus.ASSIGNED, company, profile,
                        supervisor, coordinator, now.plusHours(36)),
                project("Supplier Portal Rebuild", ProjectStatus.DRAFT, company,
                        profile, null, null, null),
                project("Document Workflow Modernisation", ProjectStatus.CLOSED, company,
                        profile, supervisor, coordinator, now.minusDays(7)),
                project("Accessibility Audit Platform", ProjectStatus.ARCHIVED, company,
                        profile, null, coordinator, null),
                project("Community Marketplace", ProjectStatus.CLOSED, company,
                        profile, supervisor, coordinator, now.minusDays(14)),
                project("Network Health Monitor", ProjectStatus.OPEN, company,
                        profile, supervisor, coordinator, now.plusDays(7)),
                project("Customer Feedback Intelligence", ProjectStatus.OPEN, secondCompany,
                        secondProfile, secondSupervisor, coordinator, now.plusDays(30)),
                project("Fleet Planning Dashboard", ProjectStatus.PENDING_REVIEW, secondCompany,
                        secondProfile, null, null, now.plusDays(5)));
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
                party("Team Orbit Demo", List.of(students.get(0), students.get(5), students.get(8)), supervisor,
                        PartyStatus.ASSIGNED, projects.get(2)),
                party("Team Northstar Demo", List.of(students.get(1), students.get(6)), supervisor,
                        PartyStatus.SUBMITTED, projects.get(4)),
                party("Team Catalyst Demo", List.of(students.get(2), students.get(7)), null,
                        PartyStatus.ACTIVE, null),
                party("Team Launchpad Demo", List.of(students.get(3)), null,
                        PartyStatus.FORMING, null),
                party("Team Summit Demo", List.of(students.get(4), students.get(9)), null,
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

    private boolean clearDemoFixtures(User operator) {
        List<User> demoUsers = Stream.concat(LEGACY_USER_EMAILS.stream(), DEMO_USER_EMAILS.stream())
                .map(userRepository::findByEmail).flatMap(java.util.Optional::stream).toList();
        Set<java.util.UUID> userIds = demoUsers.stream().map(User::getId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if (userIds.contains(operator.getId())) return blockedReset("operator identity");
        List<CompanyProfile> companies = companyProfileRepository.findAll().stream()
                .filter(profile -> userIds.contains(profile.getUser().getId())).toList();
        Set<java.util.UUID> companyIds = companies.stream().map(CompanyProfile::getId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        List<Project> projects = projectRepository.findAll().stream()
                .filter(project -> userIds.contains(project.getCreatedBy().getId())
                        || project.getApprovedBy() != null && userIds.contains(project.getApprovedBy().getId())
                        || project.getProjectSupervisor() != null && userIds.contains(project.getProjectSupervisor().getId())
                        || project.getCompany() != null && companyIds.contains(project.getCompany().getId()))
                .toList();
        if (projects.stream().anyMatch(project -> !userIds.contains(project.getCreatedBy().getId())
                || project.getCompany() != null && !companyIds.contains(project.getCompany().getId())
                || project.getProjectSupervisor() != null && !userIds.contains(project.getProjectSupervisor().getId())
                || project.getApprovedBy() != null && !userIds.contains(project.getApprovedBy().getId())
                    && !operator.getId().equals(project.getApprovedBy().getId()))) {
            return blockedReset("projects shared with non-demo users");
        }
        List<Party> parties = partyRepository.findAll().stream()
                .filter(party -> party.getLeader() != null && userIds.contains(party.getLeader().getId())
                        || party.getSupervisor() != null && userIds.contains(party.getSupervisor().getId())
                        || party.getMembers().stream().anyMatch(member -> userIds.contains(member.getId()))
                        || party.getAssignedProject() != null && projects.contains(party.getAssignedProject()))
                .toList();
        if (parties.stream().anyMatch(party -> party.getLeader() == null
                || !userIds.contains(party.getLeader().getId())
                || party.getMembers().stream().anyMatch(member -> !userIds.contains(member.getId()))
                || party.getSupervisor() != null && !userIds.contains(party.getSupervisor().getId())
                || party.getAssignedProject() != null && !projects.contains(party.getAssignedProject()))) {
            return blockedReset("parties shared with non-demo users or projects");
        }
        List<Application> applications = applicationRepository.findAll().stream()
                .filter(application -> parties.contains(application.getParty())
                        || projects.contains(application.getProject())).toList();
        if (applications.stream().anyMatch(application -> !parties.contains(application.getParty())
                || !projects.contains(application.getProject()))) {
            return blockedReset("applications linking demo and non-demo work");
        }
        List<Submission> submissions = submissionRepository.findAll().stream()
                .filter(submission -> parties.contains(submission.getParty()) || projects.contains(submission.getProject()))
                .toList();
        if (submissions.stream().anyMatch(submission -> !parties.contains(submission.getParty())
                || !projects.contains(submission.getProject()))) {
            return blockedReset("submissions linking demo and non-demo work");
        }
        List<EvaluationReport> evaluations = evaluationReportRepository.findAll().stream()
                .filter(evaluation -> submissions.contains(evaluation.getSubmission())
                        || evaluation.getTriggeredBy() != null && userIds.contains(evaluation.getTriggeredBy().getId())
                        || evaluation.getStudentScores().stream()
                            .anyMatch(score -> userIds.contains(score.getStudent().getId())))
                .toList();
        if (evaluations.stream().anyMatch(evaluation -> !submissions.contains(evaluation.getSubmission())
                || evaluation.getStudentScores().stream().anyMatch(score -> !userIds.contains(score.getStudent().getId()))
                || evaluation.getTriggeredBy() != null && !userIds.contains(evaluation.getTriggeredBy().getId())
                    && !operator.getId().equals(evaluation.getTriggeredBy().getId()))) {
            return blockedReset("evaluations linked to non-demo work or students");
        }
        List<Scorecard> scorecards = scorecardRepository.findAll().stream()
                .filter(scorecard -> userIds.contains(scorecard.getStudent().getId())
                        || scorecard.getEntries().stream().anyMatch(entry -> projects.contains(entry.getProject())
                        || evaluations.contains(entry.getEvaluationReport())))
                .toList();
        if (scorecards.stream().anyMatch(scorecard -> !userIds.contains(scorecard.getStudent().getId())
                || scorecard.getEntries().stream().anyMatch(entry -> !projects.contains(entry.getProject())
                    || entry.getEvaluationReport() != null && !evaluations.contains(entry.getEvaluationReport())))) {
            return blockedReset("scorecards linking demo and non-demo work");
        }
        List<SupervisorProfile> supervisors = supervisorProfileRepository.findAll().stream()
                .filter(profile -> userIds.contains(profile.getUser().getId())
                        || profile.getCompany() != null && companyIds.contains(profile.getCompany().getId())).toList();
        if (supervisors.stream().anyMatch(profile -> !userIds.contains(profile.getUser().getId())
                || profile.getCompany() != null && !companyIds.contains(profile.getCompany().getId()))) {
            return blockedReset("supervisor profiles shared with non-demo accounts");
        }
        var moderationEvents = moderationEventRepository.findAll().stream()
                .filter(event -> userIds.contains(event.getCoordinatorId())).toList();
        if (moderationEvents.stream().anyMatch(event -> !LEGACY_USER_EMAILS.contains(event.getNormalizedEmail())
                && !DEMO_USER_EMAILS.contains(event.getNormalizedEmail()))) {
            return blockedReset("moderation history for non-demo accounts");
        }

        // Validate the entire deletion graph before the first write; ownership never expands the user allowlist.
        // Break inverse cascades so deleting parents cannot resurrect children already removed and flushed.
        submissions.forEach(submission -> submission.setEvaluationReport(null));
        parties.forEach(party -> {
            party.setSubmission(null);
            party.getApplications().clear();
        });
        projects.forEach(project -> project.getApplications().clear());
        demoUsers.forEach(user -> {
            user.setStudentProfile(null);
            user.setCompanyProfile(null);
            user.setSupervisorProfile(null);
        });
        scorecards.forEach(scorecardRepository::delete);
        scorecardRepository.flush();
        evaluations.forEach(evaluationReportRepository::delete);
        evaluationReportRepository.flush();
        notificationRepository.findAll().stream().filter(n -> userIds.contains(n.getRecipient().getId())
                        || operator.getId().equals(n.getRecipient().getId())
                        && n.getMessage() != null
                        && DEMO_NOTIFICATIONS.getOrDefault(n.getTitle(), Set.of()).contains(n.getMessage()))
                .forEach(notificationRepository::delete);
        userIds.forEach(notificationPreferenceRepository::deleteByUserId);
        moderationEvents.forEach(moderationEventRepository::delete);
        applications.forEach(applicationRepository::delete);
        applicationRepository.flush();
        submissions.forEach(submissionRepository::delete);
        submissionRepository.flush();
        parties.forEach(partyRepository::delete);
        partyRepository.flush();
        supervisors.forEach(supervisorProfileRepository::delete);
        studentProfileRepository.findAll().stream()
                .filter(profile -> userIds.contains(profile.getUser().getId()))
                .forEach(studentProfileRepository::delete);
        invitationRepository.findAll().stream()
                .filter(invitation -> companyIds.contains(invitation.getCompany().getId()))
                .forEach(invitationRepository::delete);
        invitationRepository.flush();
        projects.forEach(projectRepository::delete);
        projectRepository.flush();
        companies.forEach(companyProfileRepository::delete);
        companyProfileRepository.flush();
        userRepository.deleteAll(demoUsers);
        userRepository.flush();
        return true;
    }

    private boolean blockedReset(String reason) {
        log.warn("Demo reset blocked: {}. No demo records changed; review shared work before retrying.", reason);
        return false;
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
                        .repoUrl("https://github.com/talentbridge-demo/smart-energy-optimiser")
                        .repoBranch("main")
                        .status(SubmissionStatus.DRAFT)
                        .notes("Draft demo submission.")
                        .build(),
                Submission.builder()
                        .party(parties.get(1))
                        .project(projects.get(4))
                        .repoUrl("https://github.com/talentbridge-demo/document-workflow")
                        .repoBranch("main")
                        .status(SubmissionStatus.SUBMITTED)
                        .submittedAt(LocalDateTime.now().minusDays(2))
                        .notes("Submitted demo work.")
                        .build(),
                Submission.builder()
                        .party(parties.get(4))
                        .project(projects.get(6))
                        .repoUrl("https://github.com/talentbridge-demo/community-marketplace")
                        .repoBranch("main")
                        .status(SubmissionStatus.EVALUATED)
                        .submittedAt(LocalDateTime.now().minusDays(10))
                        .notes("Evaluated demo work.")
                        .build());
    }

    private List<Notification> buildNotifications(User coordinator) {
        return List.of(
                notification(coordinator, NotificationType.GENERAL,
                        "New demo workspace ready", "The version 6 demo dataset is available.", true),
                notification(coordinator, NotificationType.NEW_PROJECT_POSTED,
                        "Project awaiting review", "Graduate Hiring Insights needs review.", false),
                notification(coordinator, NotificationType.DEADLINE_REMINDER,
                        "Deadline scheduled", "Campus Helpdesk Copilot has an active deadline.", false));
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
