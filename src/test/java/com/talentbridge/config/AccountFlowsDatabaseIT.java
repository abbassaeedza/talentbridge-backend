package com.talentbridge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.dto.request.*;
import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.exception.BadRequestException;
import com.talentbridge.exception.ForbiddenException;
import com.talentbridge.repository.*;
import com.talentbridge.security.JwtTokenProvider;
import com.talentbridge.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringJUnitConfig({DatabaseTestConfig.class, AccountFlowsDatabaseIT.InvitationConfig.class})
@TestPropertySource(properties = {"jwt.secret=synthetic-integration-signing-key-not-a-production-secret",
        "jwt.expiration-ms=60000", "jwt.refresh-expiration-ms=120000", "app.frontend-url=http://localhost:3000"})
class AccountFlowsDatabaseIT {
    @TestConfiguration(proxyBeanMethods = false)
    @Import(ProjectSupervisorInvitationService.class)
    static class InvitationConfig {
        @Bean EmailService emailService() { return mock(EmailService.class); }
    }

    @Autowired UserService service;
    @Autowired AuthService auth;
    @Autowired ProjectSupervisorInvitationService invitationService;
    @Autowired UserRepository users;
    @Autowired StudentProfileRepository students;
    @Autowired CompanyProfileRepository companies;
    @Autowired SupervisorProfileRepository supervisors;
    @Autowired ProjectSupervisorInvitationRepository invitations;
    @Autowired JwtTokenProvider tokens;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;
    @Autowired FileStorageService storage;
    @Autowired DataSource source;
    JdbcTemplate jdbc;
    User student;
    User owner;
    CompanyProfile company;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void fixture() {
        jdbc = new JdbcTemplate(source);
        jdbc.execute("TRUNCATE TABLE users CASCADE");
        reset(storage);
        student = account(UserRole.STUDENT, UserStatus.APPROVED, "student@example.test");
        students.save(StudentProfile.builder().user(student).age(22).university("Original University")
                .yearOfStudy("3rd Year").major("Computer Science").skills(new ArrayList<>(List.of("Java", "SQL")))
                .gpa(3.5).bio("Original bio").pastExperience("Original experience")
                .linkedinUrl("https://example.test/in/student").portfolioUrl("https://example.test/student").build());
        owner = account(UserRole.COMPANY, UserStatus.APPROVED, "company@example.test");
        company = companies.save(CompanyProfile.builder().user(owner).companyName("Acme").industry("Software")
                .description("Original description").website("https://example.test").country("Pakistan").city("Karachi")
                .logoUrl("https://example.test/logo.png").registrationNumber("SYNTHETIC-REG").build());
    }

    User account(UserRole role, UserStatus status, String email) {
        return users.save(User.builder().email(email).firstName("Original").lastName("Fixture").phoneNumber("123")
                .password(encoder.encode("InitialPassword!")).role(role).status(status)
                .githubUsername("original-github").githubAccessToken("synthetic-oauth").build());
    }

    UpdateProfileRequest update(String json) throws Exception {
        return mapper.readValue(json, UpdateProfileRequest.class);
    }

    @Test
    void studentChangesPersistAndNullClearsDoNotAffectAnotherAccountOrCredentials() throws Exception {
        User other = account(UserRole.STUDENT, UserStatus.APPROVED, "other@example.test");
        students.save(StudentProfile.builder().user(other).skills(new ArrayList<>(List.of("Python"))).bio("Other bio").build());
        String hash = student.getPassword();
        var changed = service.updateProfile(student.getId(), update("{\"firstName\":\"Updated\",\"bio\":\"New bio\"}"));
        assertEquals("Updated", changed.getFirstName());
        assertEquals("Original University", changed.getStudentProfile().getUniversity());
        assertEquals(List.of("Java", "SQL"), changed.getStudentProfile().getSkills());
        assertEquals("New bio", service.getMe(student.getId()).getStudentProfile().getBio());
        service.updateProfile(student.getId(), update("{\"gpa\":null,\"age\":null,\"bio\":null,\"phoneNumber\":null,"
                + "\"linkedinUrl\":null,\"portfolioUrl\":\"\",\"pastExperience\":null,\"skills\":[]}"));
        var saved = service.getMe(student.getId());
        assertNull(saved.getStudentProfile().getGpa());
        assertNull(saved.getStudentProfile().getAge());
        assertNull(saved.getStudentProfile().getBio());
        assertNull(saved.getStudentProfile().getPastExperience());
        assertNull(saved.getPhoneNumber());
        assertNull(saved.getStudentProfile().getLinkedinUrl());
        assertEquals("", saved.getStudentProfile().getPortfolioUrl());
        assertEquals(List.of(), saved.getStudentProfile().getSkills());
        assertEquals("Original University", saved.getStudentProfile().getUniversity());
        assertEquals("Computer Science", saved.getStudentProfile().getMajor());
        assertEquals("Other bio", service.getMe(other.getId()).getStudentProfile().getBio());
        assertEquals(List.of("Python"), service.getMe(other.getId()).getStudentProfile().getSkills());
        User identity = users.findById(student.getId()).orElseThrow();
        assertEquals(student.getEmail(), identity.getEmail());
        assertEquals(student.getRole(), identity.getRole());
        assertEquals(student.getStatus(), identity.getStatus());
        assertEquals(hash, identity.getPassword());
        assertEquals(0, identity.getTokenVersion());
        assertEquals("original-github", identity.getGithubUsername());
        assertEquals("synthetic-oauth", identity.getGithubAccessToken());
        String json = mapper.writeValueAsString(saved);
        assertFalse(json.contains("synthetic-oauth"));
        assertFalse(json.contains("password"));
    }

    @Test
    void companyAndCoordinatorEditsPersistWithoutChangingOwnershipOrCompanyCredentials() throws Exception {
        service.updateProfile(owner.getId(), update("{\"companyName\":\"Updated Acme\",\"industry\":null,"
                + "\"description\":null,\"website\":null,\"city\":null,\"country\":\"\"}"));
        CompanyProfile saved = companies.findByUserId(owner.getId()).orElseThrow();
        assertEquals("Updated Acme", saved.getCompanyName());
        assertNull(saved.getIndustry());
        assertNull(saved.getDescription());
        assertNull(saved.getWebsite());
        assertNull(saved.getCity());
        assertEquals("", saved.getCountry());
        assertEquals(owner.getId(), saved.getUser().getId());
        assertEquals("SYNTHETIC-REG", saved.getRegistrationNumber());
        assertEquals("https://example.test/logo.png", saved.getLogoUrl());
        User coordinator = account(UserRole.COORDINATOR, UserStatus.APPROVED, "coordinator@example.test");
        service.updateProfile(coordinator.getId(), update("{\"lastName\":\"Updated\",\"phoneNumber\":null}"));
        assertEquals("Updated", service.getMe(coordinator.getId()).getLastName());
        assertNull(service.getMe(coordinator.getId()).getPhoneNumber());
        assertEquals("Original", service.getMe(student.getId()).getFirstName());
    }

    StudentOnboardingRequest studentOnboarding() {
        var request = new StudentOnboardingRequest();
        request.setUniversity("New University"); request.setYearOfStudy("4th Year"); request.setMajor("CS");
        request.setSkills(List.of("Java"));
        return request;
    }

    SupervisorOnboardingRequest supervisorOnboarding() {
        var request = new SupervisorOnboardingRequest();
        request.setJobTitle("Mentor"); request.setDepartment("Engineering"); request.setBio("Experienced mentor");
        return request;
    }

    @Test
    void onboardingRequiresApprovalMatchingRoleAndMissingProfileAndReturnsDetachedDtos() throws Exception {
        assertThrows(BadRequestException.class, () -> service.completeOnboarding(student.getId(), studentOnboarding()));
        for (UserStatus status : List.of(UserStatus.PENDING, UserStatus.REJECTED, UserStatus.SUSPENDED)) {
            User pending = account(UserRole.STUDENT, status, status + "-student@example.test");
            assertThrows(ForbiddenException.class, () -> service.completeOnboarding(pending.getId(), studentOnboarding()));
            User supervisor = account(UserRole.PARTY_SUPERVISOR, status, status + "-supervisor@example.test");
            assertThrows(ForbiddenException.class, () -> service.completeSupervisorOnboarding(supervisor.getId(), supervisorOnboarding()));
            assertFalse(service.getMe(pending.getId()).isOnboardingComplete());
            assertTrue(students.findByUserId(pending.getId()).isEmpty());
            assertTrue(supervisors.findByUserId(supervisor.getId()).isEmpty());
        }
        User fresh = account(UserRole.STUDENT, UserStatus.APPROVED, "fresh@example.test");
        assertThrows(BadRequestException.class, () -> service.updateProfile(fresh.getId(), new UpdateProfileRequest()));
        var response = service.completeOnboarding(fresh.getId(), studentOnboarding());
        assertEquals("New University", response.getUniversity());
        assertTrue(service.getMe(fresh.getId()).isOnboardingComplete());
        assertEquals(List.of("Java"), response.getSkills());
        assertFalse(mapper.writeValueAsString(response).contains("\"user\""));
        assertThrows(BadRequestException.class, () -> service.completeOnboarding(fresh.getId(), studentOnboarding()));
        User supervisor = account(UserRole.PARTY_SUPERVISOR, UserStatus.APPROVED, "supervisor@example.test");
        var professional = service.completeSupervisorOnboarding(supervisor.getId(), supervisorOnboarding());
        assertEquals("Mentor", professional.getJobTitle());
        assertTrue(service.getMe(supervisor.getId()).isOnboardingComplete());
        assertFalse(mapper.writeValueAsString(professional).contains("\"user\""));
        assertThrows(BadRequestException.class, () -> service.completeSupervisorOnboarding(supervisor.getId(), supervisorOnboarding()));
        assertThrows(ForbiddenException.class, () -> service.completeOnboarding(supervisor.getId(), studentOnboarding()));
        assertThrows(ForbiddenException.class, () -> service.completeSupervisorOnboarding(fresh.getId(), supervisorOnboarding()));
    }

    @Test
    void profileUpdateWaitsForPasswordChangeAndCannotRestoreOldCredentials() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var password = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                users.findByIdForUpdate(student.getId()).orElseThrow();
                locked.countDown();
                await(release);
                var request = new ChangePasswordRequest();
                request.setCurrentPassword("InitialPassword!"); request.setNewPassword("ReplacementPassword!");
                auth.changePassword(student.getId(), request);
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var request = update("{\"firstName\":\"Updated\",\"bio\":\"New bio\"}");
            var profile = executor.submit(() -> service.updateProfile(student.getId(), request));
            assertDatabaseLockWait();
            release.countDown();
            password.get(10, TimeUnit.SECONDS);
            assertEquals("Updated", profile.get(10, TimeUnit.SECONDS).getFirstName());
            User saved = users.findById(student.getId()).orElseThrow();
            assertEquals(1, saved.getTokenVersion());
            assertTrue(encoder.matches("ReplacementPassword!", saved.getPassword()));
            assertFalse(encoder.matches("InitialPassword!", saved.getPassword()));
            assertEquals("New bio", service.getMe(student.getId()).getStudentProfile().getBio());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void concurrentOnboardingCreatesOnlyOneProfile() throws Exception {
        User fresh = account(UserRole.STUDENT, UserStatus.APPROVED, "fresh@example.test");
        var executor = Executors.newFixedThreadPool(2);
        var created = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                service.completeOnboarding(fresh.getId(), studentOnboarding());
                created.countDown();
                await(release);
            }));
            assertTrue(created.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> assertThrows(BadRequestException.class,
                    () -> service.completeOnboarding(fresh.getId(), studentOnboarding())));
            assertDatabaseLockWait();
            release.countDown();
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM student_profiles WHERE user_id=?", Integer.class, fresh.getId()));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    ProjectSupervisorInvitation invitation(String token, ProjectSupervisorInvitationStatus status, LocalDateTime expires) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        return invitations.save(ProjectSupervisorInvitation.builder().company(company).email(token + "@example.test")
                .tokenHash(hash).status(status).expiresAt(expires).build());
    }

    ProjectSupervisorInvitationAcceptRequest acceptRequest() {
        var request = new ProjectSupervisorInvitationAcceptRequest();
        request.setFirstName("New"); request.setLastName("Mentor"); request.setPassword("SyntheticPassword!");
        request.setJobTitle("Mentor"); request.setDepartment("Engineering"); request.setBio("Experienced mentor");
        return request;
    }

    @Test
    void invitationReadDtosResolveLazyAssociationsAndExpiryWithoutMutatingRows() throws Exception {
        for (var status : ProjectSupervisorInvitationStatus.values()) {
            var entity = invitation(status.name(), status, LocalDateTime.now().minusSeconds(1));
            var response = invitationService.validate(status.name());
            assertEquals(status == ProjectSupervisorInvitationStatus.PENDING ? ProjectSupervisorInvitationStatus.EXPIRED : status,
                    response.getStatus());
            assertEquals("Acme", response.getCompanyName());
            assertEquals(status, invitations.findById(entity.getId()).orElseThrow().getStatus());
            String json = mapper.writeValueAsString(response);
            assertFalse(json.contains("tokenHash"));
            assertFalse(json.contains("\"company\""));
        }
        var list = invitationService.list(owner.getId());
        assertEquals(4, list.size());
        assertTrue(list.stream().allMatch(i -> i.getCompanyName().equals("Acme")));
        assertEquals(4, mapper.readTree(mapper.writeValueAsString(list)).size());
        User mentor = account(UserRole.PROJECT_SUPERVISOR, UserStatus.APPROVED, "mentor@example.test");
        supervisors.save(SupervisorProfile.builder().user(mentor).company(company).jobTitle("Mentor").department("Engineering").bio("Experienced mentor").build());
        User otherOwner = account(UserRole.COMPANY, UserStatus.APPROVED, "other-company@example.test");
        companies.save(CompanyProfile.builder().user(otherOwner).companyName("Other company").build());
        var accepted = invitationService.listAcceptedSupervisors(owner.getId(), service);
        assertEquals(List.of(mentor.getId()), accepted.stream().map(com.talentbridge.dto.response.UserResponse::getId).toList());
        assertEquals("Mentor", mapper.readTree(mapper.writeValueAsString(accepted)).get(0).path("supervisorProfile").path("jobTitle").asText());
        assertTrue(invitationService.list(otherOwner.getId()).isEmpty());
        assertTrue(invitationService.listAcceptedSupervisors(otherOwner.getId(), service).isEmpty());
    }

    @Test
    void concurrentAcceptanceCreatesOneAccountAndLostResponseReconcilesAsAccepted() throws Exception {
        invitation("accept-once", ProjectSupervisorInvitationStatus.PENDING, LocalDateTime.now().plusDays(1));
        var executor = Executors.newFixedThreadPool(2);
        var accepted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                var response = invitationService.accept("accept-once", acceptRequest());
                accepted.countDown();
                await(release);
                return response;
            }));
            assertTrue(accepted.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> assertThrows(BadRequestException.class,
                    () -> invitationService.accept("accept-once", acceptRequest())));
            assertDatabaseLockWait();
            release.countDown();
            var response = first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertEquals(UserRole.PROJECT_SUPERVISOR, response.getUser().getRole());
            assertEquals(UserStatus.APPROVED, response.getUser().getStatus());
            assertTrue(response.getUser().isOnboardingComplete());
            assertEquals(0, tokens.parseAccessToken(response.getAccessToken()).version());
            assertEquals(0, tokens.parseRefreshToken(response.getRefreshToken()).version());
            assertEquals(ProjectSupervisorInvitationStatus.ACCEPTED, invitationService.validate("accept-once").getStatus());
            assertThrows(BadRequestException.class, () -> invitationService.accept("accept-once", acceptRequest()));
            User saved = users.findByEmail("accept-once@example.test").orElseThrow();
            assertEquals(response.getUser().getId(), saved.getId());
            assertEquals(company.getId(), supervisors.findByUserId(saved.getId()).orElseThrow().getCompany().getId());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE email='accept-once@example.test'", Integer.class));
            assertEquals(1, invitationService.listAcceptedSupervisors(owner.getId(), service).size());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void invalidExpiredRevokedAndAlreadyRegisteredInvitationsCannotCreateAccounts() throws Exception {
        long count = users.count();
        assertThrows(BadRequestException.class, () -> invitationService.accept("unknown", acceptRequest()));
        for (var status : ProjectSupervisorInvitationStatus.values()) {
            var entity = invitation(status.name(), status, LocalDateTime.now().minusSeconds(1));
            assertThrows(BadRequestException.class, () -> invitationService.accept(status.name(), acceptRequest()));
            assertEquals(status, invitations.findById(entity.getId()).orElseThrow().getStatus());
        }
        invitation("registered", ProjectSupervisorInvitationStatus.PENDING, LocalDateTime.now().plusDays(1));
        account(UserRole.STUDENT, UserStatus.APPROVED, "registered@example.test");
        assertThrows(BadRequestException.class, () -> invitationService.accept("registered", acceptRequest()));
        assertEquals(count + 1, users.count());
    }

    @Test
    void photoUploadDoesNotRestoreUserCredentialsChangedDuringStorageIO() {
        User mentor = account(UserRole.PROJECT_SUPERVISOR, UserStatus.APPROVED, "photo@example.test");
        supervisors.save(SupervisorProfile.builder().user(mentor).company(company).jobTitle("Mentor").department("Engineering").bio("Experienced mentor").build());
        when(storage.upload(any(), any())).thenAnswer(call -> {
            var executor = Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> {
                    var request = new ChangePasswordRequest();
                    request.setCurrentPassword("InitialPassword!"); request.setNewPassword("ReplacementPassword!");
                    auth.changePassword(mentor.getId(), request);
                }).get(10, TimeUnit.SECONDS);
            } finally { executor.shutdownNow(); }
            return "https://example.test/photo.png";
        });
        var response = service.uploadSupervisorProfilePhoto(mentor.getId(),
                new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3}));
        assertEquals("https://example.test/photo.png", response.getProfilePhotoUrl());
        User saved = users.findById(mentor.getId()).orElseThrow();
        assertEquals(1, saved.getTokenVersion());
        assertTrue(encoder.matches("ReplacementPassword!", saved.getPassword()));
        assertEquals("https://example.test/photo.png", service.getMe(mentor.getId()).getSupervisorProfile().getProfilePhotoUrl());
    }

    void assertDatabaseLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean waiting;
        do {
            waiting = jdbc.queryForObject("SELECT count(*) > 0 FROM pg_stat_activity WHERE datname=current_database() "
                    + "AND wait_event_type='Lock' AND pid<>pg_backend_pid()", Boolean.class);
            if (!waiting) Thread.sleep(20);
        } while (!waiting && System.nanoTime() < deadline);
        assertTrue(waiting, "The competing transaction must actually overlap the first transaction");
    }

    void await(CountDownLatch latch) {
        try { assertTrue(latch.await(15, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
    }
}
