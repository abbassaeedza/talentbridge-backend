package com.talentbridge.controller;

import com.talentbridge.config.SecurityConfig;
import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.repository.*;
import com.talentbridge.security.JwtAuthenticationFilter;
import com.talentbridge.security.JwtTokenProvider;
import com.talentbridge.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({UserController.class, ProjectSupervisorInvitationController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, UserService.class, ProjectSupervisorInvitationService.class})
@TestPropertySource(properties = "app.frontend-url=http://localhost:3000")
class AccountFlowsHttpTest {
    @Autowired MockMvc mvc;
    @MockBean JwtTokenProvider tokens;
    @MockBean UserRepository users;
    @MockBean StudentProfileRepository students;
    @MockBean SupervisorProfileRepository supervisors;
    @MockBean ScorecardRepository scorecards;
    @MockBean UserModerationEventRepository moderation;
    @MockBean PartyRepository parties;
    @MockBean ApplicationRepository applications;
    @MockBean NotificationService notifications;
    @MockBean FileStorageService files;
    @MockBean NotificationPreferenceRepository preferences;
    @MockBean ScorecardService scorecardService;
    @MockBean GitHubService github;
    @MockBean CompanyProfileRepository companies;
    @MockBean ProjectSupervisorInvitationRepository invitations;
    @MockBean EmailService email;
    User user;

    @BeforeEach
    void fixture() {
        user = User.builder().email("student@example.test").firstName("Mira").lastName("Fixture")
                .role(UserRole.STUDENT).status(UserStatus.APPROVED).password("synthetic-hash").build();
        user.setId(UUID.randomUUID());
        user.setStudentProfile(StudentProfile.builder().user(user).university("Original University")
                .skills(List.of("Java")).gpa(3.5).age(22).bio("Original bio").build());
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(users.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.with(authentication(new UsernamePasswordAuthenticationToken(user.getId(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()),
                        new SimpleGrantedAuthority("APPROVED_USER")))));
    }

    @Test
    void studentCanSaveTheirOwnProfileWithoutReplacingOmittedFields() throws Exception {
        mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json")
                        .content("{\"firstName\":\"Updated\",\"bio\":\"New bio\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("id").value(user.getId().toString()))
                .andExpect(jsonPath("firstName").value("Updated"))
                .andExpect(jsonPath("studentProfile.bio").value("New bio"))
                .andExpect(jsonPath("studentProfile.university").value("Original University"))
                .andExpect(jsonPath("studentProfile.gpa").value(3.5))
                .andExpect(jsonPath("password").doesNotExist())
                .andExpect(jsonPath("tokenVersion").doesNotExist());
    }

    @Test
    void explicitNullAndEmptyValuesClearOptionalFields() throws Exception {
        mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json")
                        .content("{\"gpa\":null,\"age\":null,\"bio\":null,\"phoneNumber\":null,\"linkedinUrl\":\"\",\"skills\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("studentProfile.gpa").isEmpty())
                .andExpect(jsonPath("studentProfile.age").isEmpty())
                .andExpect(jsonPath("studentProfile.bio").isEmpty())
                .andExpect(jsonPath("phoneNumber").isEmpty())
                .andExpect(jsonPath("studentProfile.linkedinUrl").value(""))
                .andExpect(jsonPath("studentProfile.skills").isEmpty())
                .andExpect(jsonPath("studentProfile.university").value("Original University"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"university\":null}", "{\"university\":\"\"}", "{\"university\":\"   \"}",
            "{\"yearOfStudy\":null}", "{\"yearOfStudy\":\"\"}", "{\"yearOfStudy\":\"   \"}",
            "{\"major\":null}", "{\"major\":\"\"}", "{\"major\":\"   \"}"})
    void rejectsClearingRequiredAcademicFields(String body) throws Exception {
        user.getStudentProfile().setYearOfStudy("3rd Year");
        user.getStudentProfile().setMajor("Computer Science");
        mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        assertEquals("Original University", user.getStudentProfile().getUniversity());
        assertEquals("3rd Year", user.getStudentProfile().getYearOfStudy());
        assertEquals("Computer Science", user.getStudentProfile().getMajor());
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "userId", "email", "role", "status", "password", "tokenVersion",
            "githubUsername", "githubAccessToken", "emailVerified", "emailVerificationToken", "user",
            "studentProfile", "companyProfile", "supervisorProfile", "companyId", "logoUrl", "registrationNumber",
            "unknown", "supplied", "fields"})
    void rejectsUnknownAndProtectedPropertiesEvenWhenNull(String field) throws Exception {
        mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json")
                        .content("{\"firstName\":\"Must not save\",\"" + field + "\":null}"))
                .andExpect(status().isBadRequest());
        assertEquals("Mira", user.getFirstName());
        assertEquals(0, user.getTokenVersion());
        assertEquals("synthetic-hash", user.getPassword());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"firstName\":null}", "{\"lastName\":\" \"}", "{\"gpa\":4.01}",
            "{\"gpa\":-0.01}", "{\"gpa\":\"NaN\"}", "{\"age\":-1}", "{\"age\":151}",
            "{\"skills\":[\"\"]}", "{\"skills\":[null]}", "{\"skills\":\"Java\"}",
            "{\"linkedinUrl\":\"javascript:alert(1)\"}", "{\"portfolioUrl\":\"ftp://example.test\"}",
            "{\"linkedinUrl\":\"https://\"}", "{\"portfolioUrl\":\"https://example.test/has space\"}"})
    void rejectsInvalidProfileValues(String body) throws Exception {
        mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void enforcesProfileLengthsAndSkillBounds() throws Exception {
        for (var limit : java.util.Map.of("firstName", 100, "lastName", 100, "phoneNumber", 50,
                "university", 255, "major", 255, "yearOfStudy", 50, "bio", 2000, "pastExperience", 3000).entrySet()) {
            mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json")
                            .content("{\"" + limit.getKey() + "\":\"" + "a".repeat(limit.getValue() + 1) + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        for (String skills : List.of("[\"" + "a".repeat(101) + "\"]",
                "[" + String.join(",", java.util.Collections.nCopies(21, "\"Java\"")) + "]")) {
            mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json")
                            .content("{\"skills\":" + skills + "}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void zeroGpaAndHttpUrlsAreAccepted() throws Exception {
        mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json")
                        .content("{\"gpa\":0,\"linkedinUrl\":\"https://example.test/in/mira\",\"portfolioUrl\":\"http://example.test\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("studentProfile.gpa").value(0));
    }

    @Test
    void rejectsCrossRoleFieldsIncludingNullAndEmptyValues() throws Exception {
        for (String body : List.of("{\"companyName\":\"Other\"}", "{\"industry\":null}", "{\"city\":\"\"}")) {
            mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        user.setRole(UserRole.COMPANY);
        user.setCompanyProfile(CompanyProfile.builder().user(user).companyName("Acme").build());
        mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json").content("{\"gpa\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void companyCanEditExistingFieldsAndClearOptionalValues() throws Exception {
        user.setRole(UserRole.COMPANY);
        user.setStudentProfile(null);
        user.setCompanyProfile(CompanyProfile.builder().user(user).companyName("Acme").industry("Software")
                .website("https://example.test").country("Pakistan").build());
        mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json")
                        .content("{\"companyName\":\"New Acme\",\"industry\":null,\"website\":\"\",\"city\":\"Karachi\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("companyProfile.companyName").value("New Acme"))
                .andExpect(jsonPath("companyProfile.industry").isEmpty())
                .andExpect(jsonPath("companyProfile.website").value(""))
                .andExpect(jsonPath("companyProfile.country").value("Pakistan"));
        for (String body : List.of("{\"companyName\":null}", "{\"companyName\":\" \"}",
                "{\"website\":\"javascript:alert(1)\"}")) {
            mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        for (String field : List.of("industry", "city", "country")) {
            mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json")
                            .content("{\"" + field + "\":\"" + "a".repeat(101) + "\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void coordinatorCanEditOnlyCommonFields() throws Exception {
        user.setRole(UserRole.COORDINATOR);
        user.setStudentProfile(null);
        mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json")
                        .content("{\"firstName\":\"Coordinator\",\"phoneNumber\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("firstName").value("Coordinator"));
        mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json").content("{\"bio\":null}"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"PARTY_SUPERVISOR", "PROJECT_SUPERVISOR"})
    void supervisorEditingRemainsUnsupported(UserRole role) throws Exception {
        user.setRole(role);
        mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json").content("{\"firstName\":\"New\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void profileEditingCannotCreateAMissingProfileOrTargetAnotherUser() throws Exception {
        user.setStudentProfile(null);
        mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json").content("{\"firstName\":\"New\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(signedIn(put("/api/users/{id}/profile", UUID.randomUUID())).contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        verify(students, never()).save(any());
    }

    @Test
    void anonymousProfileWritesRequireAuthentication() throws Exception {
        mvc.perform(put("/api/users/me/profile").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    static final String STUDENT_ONBOARDING = "{\"university\":\"University\",\"yearOfStudy\":\"3rd Year\",\"major\":\"CS\"}";
    static final String SUPERVISOR_ONBOARDING = "{\"jobTitle\":\"Mentor\",\"department\":\"Engineering\",\"bio\":\"Experience\"}";

    @Test
    void studentOnboardingValidatesBeforeCreatingAProfile() throws Exception {
        mvc.perform(signedIn(post("/api/users/onboarding")).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(signedIn(post("/api/users/onboarding")).contentType("application/json")
                        .content("{\"university\":\"U\",\"yearOfStudy\":\"3rd Year\",\"major\":\"CS\",\"gpa\":5}"))
                .andExpect(status().isBadRequest());
        verify(students, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"PENDING", "REJECTED", "SUSPENDED"})
    void serviceChecksApprovalEvenIfAnAuthorityIsStale(UserStatus status) throws Exception {
        user.setStatus(status);
        mvc.perform(signedIn(put("/api/users/me/profile")).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(signedIn(post("/api/users/onboarding")).contentType("application/json").content(STUDENT_ONBOARDING))
                .andExpect(status().isForbidden());
        user.setRole(UserRole.PARTY_SUPERVISOR);
        mvc.perform(signedIn(post("/api/users/supervisor-onboarding")).contentType("application/json").content(SUPERVISOR_ONBOARDING))
                .andExpect(status().isForbidden());
    }

    @Test
    void onboardingIsRoleSpecificAndCannotReplaceAnExistingProfile() throws Exception {
        when(students.findByUserId(user.getId())).thenReturn(Optional.of(user.getStudentProfile()));
        mvc.perform(signedIn(post("/api/users/onboarding")).contentType("application/json").content(STUDENT_ONBOARDING))
                .andExpect(status().isBadRequest());
        mvc.perform(signedIn(post("/api/users/supervisor-onboarding")).contentType("application/json").content(SUPERVISOR_ONBOARDING))
                .andExpect(status().isForbidden());
        user.setRole(UserRole.PARTY_SUPERVISOR);
        when(supervisors.findByUserId(user.getId())).thenReturn(Optional.of(SupervisorProfile.builder().user(user).build()));
        mvc.perform(signedIn(post("/api/users/supervisor-onboarding")).contentType("application/json").content(SUPERVISOR_ONBOARDING))
                .andExpect(status().isBadRequest());
        mvc.perform(signedIn(post("/api/users/onboarding")).contentType("application/json").content(STUDENT_ONBOARDING))
                .andExpect(status().isForbidden());
        verify(students, never()).save(any());
        verify(supervisors, never()).save(any());
    }

    @Test
    void approvedMissingProfilesCanOnboardAndReceiveOnlyProfileDtos() throws Exception {
        user.setStudentProfile(null);
        when(students.save(any())).thenAnswer(call -> call.getArgument(0));
        mvc.perform(signedIn(post("/api/users/onboarding")).contentType("application/json").content(STUDENT_ONBOARDING))
                .andExpect(status().isOk()).andExpect(jsonPath("university").value("University"))
                .andExpect(jsonPath("user").doesNotExist());
        user.setRole(UserRole.PARTY_SUPERVISOR);
        user.setStudentProfile(null);
        when(supervisors.save(any())).thenAnswer(call -> call.getArgument(0));
        mvc.perform(signedIn(post("/api/users/supervisor-onboarding")).contentType("application/json").content(SUPERVISOR_ONBOARDING))
                .andExpect(status().isOk()).andExpect(jsonPath("jobTitle").value("Mentor"))
                .andExpect(jsonPath("user").doesNotExist());
    }

    @Test
    void photoUploadReturnsADtoWithoutLockingOrDirtyingTheUser() throws Exception {
        user.setRole(UserRole.PROJECT_SUPERVISOR);
        var profile = SupervisorProfile.builder().user(user).jobTitle("Mentor").department("Engineering").build();
        when(supervisors.findByUserId(user.getId())).thenReturn(Optional.of(profile));
        when(supervisors.save(any())).thenAnswer(call -> call.getArgument(0));
        when(files.upload(any(), any())).thenReturn("https://example.test/photo.png");
        mvc.perform(signedIn(multipart("/api/users/supervisor-profile-photo")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1, 2, 3}))))
                .andExpect(status().isOk()).andExpect(jsonPath("profilePhotoUrl").value("https://example.test/photo.png"))
                .andExpect(jsonPath("user").doesNotExist());
        verify(users, never()).findByIdForUpdate(any());
        verify(users, never()).save(any());
        assertNull(user.getSupervisorProfile());
        user.setStatus(UserStatus.PENDING);
        mvc.perform(signedIn(multipart("/api/users/supervisor-profile-photo")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", new byte[]{1}))))
                .andExpect(status().isForbidden());
    }

    ProjectSupervisorInvitation invitation(ProjectSupervisorInvitationStatus state, LocalDateTime expiry) throws Exception {
        var invitation = ProjectSupervisorInvitation.builder().company(CompanyProfile.builder().companyName("Acme").build())
                .email("mentor@example.test").status(state).expiresAt(expiry).build();
        invitation.setId(UUID.randomUUID());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("known".getBytes(StandardCharsets.UTF_8)));
        when(invitations.findByTokenHash(hash)).thenReturn(Optional.of(invitation));
        return invitation;
    }

    @ParameterizedTest
    @EnumSource(ProjectSupervisorInvitationStatus.class)
    void publicValidationReturnsKnownStatesInsteadOfTreatingThemAsInvalid(ProjectSupervisorInvitationStatus state) throws Exception {
        var invitation = invitation(state, LocalDateTime.now().plusDays(1));
        mvc.perform(get("/api/project-supervisor-invitations/known"))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value(state.name()))
                .andExpect(jsonPath("companyName").value("Acme"))
                .andExpect(jsonPath("tokenHash").doesNotExist())
                .andExpect(jsonPath("company").doesNotExist());
        assertEquals(state, invitation.getStatus());
        verify(invitations, never()).save(any());
    }

    @Test
    void publicValidationDerivesExpiryOnlyForPendingTokensWithoutWrites() throws Exception {
        for (var state : ProjectSupervisorInvitationStatus.values()) {
            var invitation = invitation(state, LocalDateTime.now());
            String expected = state == ProjectSupervisorInvitationStatus.PENDING ? "EXPIRED" : state.name();
            mvc.perform(get("/api/project-supervisor-invitations/known"))
                    .andExpect(status().isOk()).andExpect(jsonPath("status").value(expected));
            assertEquals(state, invitation.getStatus());
        }
        verify(invitations, never()).save(any());
    }

    @Test
    void publicValidationDistinguishesUnknownTokensFromTemporaryServerFailures() throws Exception {
        mvc.perform(get("/api/project-supervisor-invitations/unknown"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("message").value("Invitation is invalid"));
        when(invitations.findByTokenHash(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Synthetic outage"));
        mvc.perform(get("/api/project-supervisor-invitations/unknown"))
                .andExpect(status().is5xxServerError()).andExpect(jsonPath("message").value("An unexpected error occurred"));
    }
}
