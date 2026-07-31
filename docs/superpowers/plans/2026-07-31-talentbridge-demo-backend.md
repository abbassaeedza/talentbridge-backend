# TalentBridge Demo Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add atomic demo-only representative data and make chatbot failures safe and diagnosable.

**Architecture:** The existing startup seeder becomes a transactional Spring `ApplicationRunner` and delegates the representative graph to one focused `DemoDataSeeder` only when demo mode is enabled.
The existing JDK HTTP client remains the OpenAI boundary, while upstream failures become a safe HTTP 503 through Spring's native `ResponseStatusException` handling.

**Tech Stack:** Java 17, Spring Boot 3.2, Spring Data JPA, PostgreSQL, JUnit 5, Mockito, Jackson, and JDK `HttpClient`.

## Global Constraints

The OpenAI key must remain an environment-only secret and must never be committed.
Demo records must be created only when `app.demo-mode=true`.
Repeated application starts must not duplicate demo records.
No test may contact a real database or external network service.
No new dependency may be added.

---

### Task 1: Atomic Representative Demo Data

**Files:**

- Create: `src/main/java/com/talentbridge/config/DemoDataSeeder.java`
- Create: `src/test/java/com/talentbridge/config/DemoDataSeederTest.java`
- Modify: `src/main/java/com/talentbridge/config/DataSeeder.java`
- Modify: `src/test/java/com/talentbridge/config/DataSeederTest.java`

**Interfaces:**

- Consumes: Existing JPA entities and repositories, the persisted coordinator `User`, and the coordinator's encoded password.
- Produces: `DemoDataSeeder.seed(User coordinator)`, plus a transactional `DataSeeder.run(ApplicationArguments args)` startup entry point.

The representative dataset is fixed to these records:

| Area | Records |
| --- | --- |
| Users | One approved company, one approved party supervisor, one approved project supervisor, five approved students, and one student in each of pending, rejected, and suspended states. |
| Profiles | One company profile and one student profile for every demo student. |
| Projects | AI Support Copilot in `OPEN`, Hiring Analytics Dashboard in `PENDING_REVIEW`, Cloud Cost Optimiser in `ASSIGNED`, Partner Portal Refresh in `DRAFT`, Legacy Process Digitisation in `CLOSED`, and Coordinator Sandbox Project in `ARCHIVED`. |
| Parties | Team Alpha Demo in `ASSIGNED`, Team Beta Demo in `SUBMITTED`, Team Gamma Demo in `ACTIVE`, Team Delta Demo in `FORMING`, and Team Epsilon Demo in `COMPLETED`. |
| Applications | One `ASSIGNED`, one `PENDING`, and one `REJECTED` application connected to the matching parties and projects. |
| Submissions | One `DRAFT` submission and one `SUBMITTED` submission. |
| Notifications | Three unread or read coordinator notifications covering registration, project review, and deadline activity. |

- [ ] **Step 1: Write failing demo-mode delegation tests**

Extend `DataSeederTest` with a `DemoDataSeeder` mock and verify both branches:

```java
@Mock private DemoDataSeeder demoDataSeeder;

@Test
void seedsRepresentativeDataInDemoMode() {
    ReflectionTestUtils.setField(seeder, "demoMode", true);
    when(userRepository.findByEmail(coordinator.getEmail())).thenReturn(Optional.of(coordinator));
    when(passwordEncoder.matches("new-demo-password", "old-hash")).thenReturn(true);

    seeder.seed();

    verify(demoDataSeeder).seed(coordinator);
}

@Test
void skipsRepresentativeDataOutsideDemoMode() {
    ReflectionTestUtils.setField(seeder, "demoMode", false);
    when(userRepository.findByEmail(coordinator.getEmail())).thenReturn(Optional.of(coordinator));

    seeder.seed();

    verify(demoDataSeeder, never()).seed(any());
}
```

- [ ] **Step 2: Run the delegation tests and verify failure**

Run: `./mvnw -Dtest=DataSeederTest test`

Expected: Compilation fails because `DemoDataSeeder` and its injected dependency do not exist.

- [ ] **Step 3: Add the transactional startup boundary and demo gate**

Change `DataSeeder` from `@PostConstruct` initialization to Spring's runner boundary:

```java
public class DataSeeder implements ApplicationRunner {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DemoDataSeeder demoDataSeeder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed();
    }

    public void seed() {
        User coordinator = userRepository.findByEmail(coordinatorEmail)
                .orElseGet(this::createCoordinator);

        if (demoMode && !passwordEncoder.matches(coordinatorPassword, coordinator.getPassword())) {
            coordinator.setPassword(passwordEncoder.encode(coordinatorPassword));
            userRepository.save(coordinator);
        }

        if (demoMode) {
            demoDataSeeder.seed(coordinator);
        }
    }
}
```

Keep coordinator creation in a private `createCoordinator()` method using the current fields and defaults.
Do not return early when the coordinator already exists because the representative demo seed must still run.

- [ ] **Step 4: Run the delegation tests and verify success**

Run: `./mvnw -Dtest=DataSeederTest test`

Expected: All `DataSeederTest` tests pass.

- [ ] **Step 5: Write failing representative graph tests**

Create `DemoDataSeederTest` with mocks for the eight repositories used by the graph.
Use the marker email `demo.company@talentbridge.com` and capture each `saveAll` argument.

```java
@Test
void skipsDatasetWhenMarkerUserExists() {
    when(userRepository.existsByEmail("demo.company@talentbridge.com")).thenReturn(true);

    seeder.seed(coordinator);

    verify(userRepository, never()).saveAll(any());
    verify(projectRepository, never()).saveAll(any());
}

@Test
void createsRepresentativeDatasetOnce() {
    when(userRepository.existsByEmail("demo.company@talentbridge.com")).thenReturn(false);

    seeder.seed(coordinator);

    ArgumentCaptor<List<User>> users = ArgumentCaptor.forClass(List.class);
    verify(userRepository).saveAll(users.capture());
    assertEquals(11, users.getValue().size());
    assertEquals(Set.of(PENDING, APPROVED, REJECTED, SUSPENDED),
            users.getValue().stream().map(User::getStatus).collect(Collectors.toSet()));

    ArgumentCaptor<List<Project>> projects = ArgumentCaptor.forClass(List.class);
    verify(projectRepository).saveAll(projects.capture());
    assertEquals(Set.of(DRAFT, PENDING_REVIEW, OPEN, ASSIGNED, CLOSED, ARCHIVED),
            projects.getValue().stream().map(Project::getStatus).collect(Collectors.toSet()));

    ArgumentCaptor<List<Party>> parties = ArgumentCaptor.forClass(List.class);
    verify(partyRepository).saveAll(parties.capture());
    assertEquals(Set.of(FORMING, ACTIVE, ASSIGNED, SUBMITTED, COMPLETED),
            parties.getValue().stream().map(Party::getStatus).collect(Collectors.toSet()));

    verify(applicationRepository).saveAll(argThat(items -> ((Collection<?>) items).size() == 3));
    verify(submissionRepository).saveAll(argThat(items -> ((Collection<?>) items).size() == 2));
    verify(notificationRepository).saveAll(argThat(items -> ((Collection<?>) items).size() == 3));
}
```

- [ ] **Step 6: Run the graph tests and verify failure**

Run: `./mvnw -Dtest=DemoDataSeederTest test`

Expected: Compilation fails because `DemoDataSeeder` does not exist.

- [ ] **Step 7: Implement the minimal representative graph**

Create `DemoDataSeeder` with final repository dependencies and one public entry point:

```java
@Component
@RequiredArgsConstructor
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
        if (userRepository.existsByEmail(MARKER_EMAIL)) return;

        String password = coordinator.getPassword();
        User company = user(MARKER_EMAIL, "Nexa", "Systems", COMPANY, APPROVED, password);
        User partySupervisor = user("demo.party-supervisor@talentbridge.com", "Sara", "Ahmed", PARTY_SUPERVISOR, APPROVED, password);
        User projectSupervisor = user("demo.project-supervisor@talentbridge.com", "Omar", "Khan", PROJECT_SUPERVISOR, APPROVED, password);
        List<User> students = List.of(
                user("alice.student@talentbridge.com", "Alice", "Ali", STUDENT, APPROVED, password),
                user("bob.student@talentbridge.com", "Bob", "Raza", STUDENT, APPROVED, password),
                user("carol.student@talentbridge.com", "Carol", "Iqbal", STUDENT, APPROVED, password),
                user("dan.student@talentbridge.com", "Dan", "Malik", STUDENT, APPROVED, password),
                user("erin.student@talentbridge.com", "Erin", "Shah", STUDENT, APPROVED, password),
                user("pending.student@talentbridge.com", "Pending", "Student", STUDENT, PENDING, password),
                user("rejected.student@talentbridge.com", "Rejected", "Student", STUDENT, REJECTED, password),
                user("suspended.student@talentbridge.com", "Suspended", "Student", STUDENT, SUSPENDED, password));
        userRepository.saveAll(Stream.concat(Stream.of(company, partySupervisor, projectSupervisor), students.stream()).toList());

        CompanyProfile companyProfile = CompanyProfile.builder()
                .user(company).companyName("Nexa Systems").industry("Software")
                .description("Product engineering and analytics company.")
                .country("Pakistan").city("Islamabad").build();
        companyProfileRepository.save(companyProfile);
        studentProfileRepository.saveAll(students.stream().map(this::studentProfile).toList());

        List<Project> projects = buildProjects(company, companyProfile, projectSupervisor, coordinator);
        projectRepository.saveAll(projects);
        List<Party> parties = buildParties(students, partySupervisor, projects);
        partyRepository.saveAll(parties);
        applicationRepository.saveAll(buildApplications(parties, projects));
        submissionRepository.saveAll(buildSubmissions(parties, projects));
        notificationRepository.saveAll(buildNotifications(coordinator));
    }
}
```

Implement only the private builders required by this entry point.
Each project, party, application, submission, and notification must match the fixed dataset table above and connect by the list positions defined in the method.
Use `LocalDateTime.now().plusDays(14)` and `plusDays(30)` only for active deadlines so a newly seeded demo stays usable.

Use these exact builders so the graph stays small and readable:

```java
private User user(String email, String firstName, String lastName, UserRole role,
                  UserStatus status, String password) {
    return User.builder()
            .email(email).password(password).firstName(firstName).lastName(lastName)
            .role(role).status(status).emailVerified(status != UserStatus.PENDING)
            .rejectionReason(status == UserStatus.REJECTED ? "Demo rejection example" : null)
            .build();
}

private StudentProfile studentProfile(User user) {
    return StudentProfile.builder()
            .user(user).age(22).university("National University")
            .yearOfStudy("Final Year").major("Computer Science")
            .skills(List.of("Java", "React", "PostgreSQL"))
            .bio("Demo student profile for TalentBridge testing.").gpa(3.5)
            .build();
}

private List<Project> buildProjects(User company, CompanyProfile profile,
                                    User supervisor, User coordinator) {
    LocalDateTime now = LocalDateTime.now();
    return List.of(
            project("AI Support Copilot", ProjectStatus.OPEN, company, profile, supervisor, coordinator, now.plusDays(14)),
            project("Hiring Analytics Dashboard", ProjectStatus.PENDING_REVIEW, company, profile, null, null, null),
            project("Cloud Cost Optimiser", ProjectStatus.ASSIGNED, company, profile, supervisor, coordinator, now.plusDays(30)),
            project("Partner Portal Refresh", ProjectStatus.DRAFT, company, profile, null, null, null),
            project("Legacy Process Digitisation", ProjectStatus.CLOSED, company, profile, supervisor, coordinator, now.minusDays(7)),
            project("Coordinator Sandbox Project", ProjectStatus.ARCHIVED, company, profile, null, coordinator, null));
}

private Project project(String title, ProjectStatus status, User owner,
                        CompanyProfile company, User supervisor, User approver,
                        LocalDateTime deadline) {
    return Project.builder()
            .title(title).internalName("Demo " + title).projectField("Software")
            .description("Representative TalentBridge demo project.")
            .scope("Plan, build, test, and present the requested product.")
            .deliverables("Source code, documentation, tests, and final presentation.")
            .evaluationCriteria("Functionality, quality, usability, and collaboration.")
            .tools(List.of("React", "Spring Boot", "PostgreSQL"))
            .status(status).createdBy(owner).company(company)
            .projectSupervisor(supervisor).approvedBy(approver).deadline(deadline)
            .build();
}

private List<Party> buildParties(List<User> students, User supervisor,
                                 List<Project> projects) {
    return List.of(
            party("Team Alpha Demo", students.get(0), supervisor, PartyStatus.ASSIGNED, projects.get(2)),
            party("Team Beta Demo", students.get(1), supervisor, PartyStatus.SUBMITTED, projects.get(4)),
            party("Team Gamma Demo", students.get(2), supervisor, PartyStatus.ACTIVE, null),
            party("Team Delta Demo", students.get(3), null, PartyStatus.FORMING, null),
            party("Team Epsilon Demo", students.get(4), supervisor, PartyStatus.COMPLETED, null));
}

private Party party(String name, User leader, User supervisor, PartyStatus status,
                    Project project) {
    return Party.builder()
            .name(name).leader(leader).members(new HashSet<>(Set.of(leader)))
            .supervisor(supervisor).status(status).semester("Fall")
            .academicYear(2026).assignedProject(project).build();
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
            .party(party).project(project).rankPosition(rank)
            .proposalText("Representative demo proposal for " + project.getTitle())
            .status(status).build();
}

private List<Submission> buildSubmissions(List<Party> parties, List<Project> projects) {
    return List.of(
            Submission.builder().party(parties.get(0)).project(projects.get(2))
                    .repoUrl("https://github.com/talentbridge-demo/cloud-cost-optimiser")
                    .repoBranch("main").status(SubmissionStatus.DRAFT)
                    .notes("Draft demo submission.").build(),
            Submission.builder().party(parties.get(1)).project(projects.get(4))
                    .repoUrl("https://github.com/talentbridge-demo/process-digitisation")
                    .repoBranch("main").status(SubmissionStatus.SUBMITTED)
                    .submittedAt(LocalDateTime.now().minusDays(2))
                    .notes("Submitted demo work.").build());
}

private List<Notification> buildNotifications(User coordinator) {
    return List.of(
            notification(coordinator, NotificationType.GENERAL, "Demo data ready", "Representative demo records are available.", true),
            notification(coordinator, NotificationType.NEW_PROJECT_POSTED, "Project awaiting review", "Hiring Analytics Dashboard needs review.", false),
            notification(coordinator, NotificationType.DEADLINE_REMINDER, "Deadline scheduled", "AI Support Copilot has an active deadline.", false));
}

private Notification notification(User recipient, NotificationType type, String title,
                                  String message, boolean read) {
    return Notification.builder()
            .recipient(recipient).type(type).title(title).message(message).read(read)
            .build();
}
```

- [ ] **Step 8: Run all seeder tests and verify success**

Run: `./mvnw -Dtest=DataSeederTest,DemoDataSeederTest test`

Expected: All seeder tests pass without a database connection.

- [ ] **Step 9: Commit the demo dataset**

```bash
git add src/main/java/com/talentbridge/config/DataSeeder.java src/main/java/com/talentbridge/config/DemoDataSeeder.java src/test/java/com/talentbridge/config/DataSeederTest.java src/test/java/com/talentbridge/config/DemoDataSeederTest.java
git commit -m "feat: seed representative demo data"
```

### Task 2: Safe Chatbot Upstream Handling

**Files:**

- Modify: `src/main/java/com/talentbridge/service/OpenAIService.java`
- Create: `src/test/java/com/talentbridge/service/OpenAIServiceTest.java`
- Verify: `src/main/java/com/talentbridge/exception/GlobalExceptionHandler.java`

**Interfaces:**

- Consumes: `OpenAIService.chat(ChatRequest)` and the existing `/api/chat` controller.
- Produces: The same successful assistant message string, or `ResponseStatusException` with status 503 and safe reason `AI service is temporarily unavailable`.

- [ ] **Step 1: Write failing JDK HTTP boundary tests**

Create an ephemeral localhost `HttpServer` in `OpenAIServiceTest`.
Set `baseUrl`, models, token limit, timeout, and a non-secret test key with `ReflectionTestUtils`.

```java
@Test
void returnsAssistantMessageFromChatCompletion() {
    server.createContext("/chat/completions", exchange -> respond(exchange, 200,
            "{\"choices\":[{\"message\":{\"content\":\"Demo reply\"}}]}"));

    String result = service.chat(ChatRequest.builder()
            .message("Explain the scope")
            .context("STUDENT_PROJECT_INQUIRY")
            .build());

    assertEquals("Demo reply", result);
}

@Test
void convertsUpstreamAuthenticationFailureToSafeServiceUnavailable() {
    server.createContext("/chat/completions", exchange -> respond(exchange, 401,
            "{\"error\":{\"message\":\"invalid test credential\"}}"));

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.chat(ChatRequest.builder().message("Hello").build()));

    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    assertEquals("AI service is temporarily unavailable", error.getReason());
    assertFalse(error.getReason().contains("credential"));
}
```

- [ ] **Step 2: Run the chatbot tests and verify failure**

Run: `./mvnw -Dtest=OpenAIServiceTest test`

Expected: The success test passes and the failure test reports the current generic runtime exception.

- [ ] **Step 3: Implement safe native HTTP error propagation**

Replace body logging and generic nested runtime messages in `OpenAIService.call`:

```java
if (response.statusCode() < 200 || response.statusCode() >= 300) {
    log.error("OpenAI request failed with status {}", response.statusCode());
    throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "AI service is temporarily unavailable");
}
```

Preserve an existing `ResponseStatusException` and wrap transport, timeout, and malformed-response failures once:

```java
} catch (ResponseStatusException e) {
    throw e;
} catch (Exception e) {
    log.error("OpenAI call failed", e);
    throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "AI service is temporarily unavailable",
            e);
}
```

- [ ] **Step 4: Run focused chatbot and exception tests**

Run: `./mvnw -Dtest=OpenAIServiceTest,GlobalExceptionHandlerTest test`

Expected: All tests pass and the existing generic handler maps the exception to HTTP 503 with the safe reason.

- [ ] **Step 5: Commit chatbot handling**

```bash
git add src/main/java/com/talentbridge/service/OpenAIService.java src/test/java/com/talentbridge/service/OpenAIServiceTest.java
git commit -m "fix: report chatbot service failures"
```

### Task 3: Backend Verification

**Files:**

- Verify only: `pom.xml`
- Verify only: `.env.example`

**Interfaces:**

- Consumes: The completed backend changes.
- Produces: A passing backend build and a confirmed secret-free diff.

- [ ] **Step 1: Run the complete backend test suite**

Run: `./mvnw test`

Expected: Maven exits with `BUILD SUCCESS` and no test failures.

- [ ] **Step 2: Verify the repository contains no supplied secret**

Run: `git diff HEAD~2 -- . ':!docs/superpowers' | rg 'sk-proj-|OPENAI_API_KEY='`

Expected: No output.

- [ ] **Step 3: Inspect the final backend diff**

Run: `git status --short && git diff HEAD~2 --check`

Expected: The worktree is clean and `git diff --check` produces no output.
