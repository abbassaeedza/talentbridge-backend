package com.talentbridge.controller;

import com.talentbridge.config.SecurityConfig;
import com.talentbridge.config.DemoAccounts;
import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.repository.CompanyProfileRepository;
import com.talentbridge.repository.ScorecardRepository;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.security.JwtAuthenticationFilter;
import com.talentbridge.security.JwtTokenProvider;
import com.talentbridge.service.AuthService;
import com.talentbridge.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, AuthService.class})
@TestPropertySource(properties = {"app.frontend-url=http://localhost:3000", "app.demo-mode=true",
        "app.seed.demo-password=SyntheticDemoPassword!", "app.seed.coordinator-email=operator@example.test"})
class AuthControllerSecurityTest {
    @Autowired private MockMvc mvc;
    @Autowired private AuthService authService;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockBean private JwtTokenProvider tokenProvider;
    @MockBean private UserRepository userRepository;
    @MockBean private CompanyProfileRepository companyProfileRepository;
    @MockBean private ScorecardRepository scorecardRepository;
    @MockBean private NotificationService notificationService;

    @BeforeEach
    void enabled() {
        ReflectionTestUtils.setField(authService, "demoMode", true);
    }

    private User approvedDemo() {
        User demo = User.builder().email(DemoAccounts.COORDINATOR_EMAIL)
                .password(passwordEncoder.encode("SyntheticDemoPassword!"))
                .firstName("Rowan").lastName("Hale")
                .role(UserRole.COORDINATOR).status(UserStatus.APPROVED).tokenVersion(7).build();
        demo.setId(UUID.randomUUID());
        return demo;
    }

    @Test
    void publicConfigContainsOnlyTheFlagAndCannotBeCached() throws Exception {
        mvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"demoMode\":true}", true));
        ReflectionTestUtils.setField(authService, "demoMode", false);
        mvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"demoMode\":false}", true));

        verifyNoInteractions(userRepository, tokenProvider);
    }

    @Test
    void disabledDemoLoginBlocksDirectAndTamperedRequestsBeforeAnyLookup() throws Exception {
        ReflectionTestUtils.setField(authService, "demoMode", false);
        mvc.perform(post("/api/auth/demo-login")).andExpect(status().isNotFound());
        mvc.perform(post("/api/auth/demo-login").param("email", "operator@example.test")
                        .contentType("application/json").content("{\"role\":\"COORDINATOR\"}"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(userRepository, tokenProvider);
    }

    @Test
    void emptyDemoRequestAuthenticatesOnlyTheDedicatedApprovedCoordinator() throws Exception {
        User demo = approvedDemo();
        when(userRepository.findByEmail(DemoAccounts.COORDINATOR_EMAIL)).thenReturn(Optional.of(demo));
        when(tokenProvider.generateAccessToken(any(), any(), any(), anyInt())).thenReturn("access");
        when(tokenProvider.generateRefreshToken(any(), anyInt())).thenReturn("refresh");

        mvc.perform(post("/api/auth/demo-login"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access"))
                .andExpect(jsonPath("$.refreshToken").value("refresh"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.id").value(demo.getId().toString()))
                .andExpect(jsonPath("$.user.email").value(DemoAccounts.COORDINATOR_EMAIL))
                .andExpect(jsonPath("$.user.role").value("COORDINATOR"))
                .andExpect(jsonPath("$.user.status").value("APPROVED"))
                .andExpect(jsonPath("$.user.onboardingComplete").value(true))
                .andExpect(content().string(not(containsString("SyntheticDemoPassword!"))))
                .andExpect(content().string(not(containsString(demo.getPassword()))));

        verify(tokenProvider).generateAccessToken(demo.getId(), demo.getEmail(), "COORDINATOR", 7);
        verify(tokenProvider).generateRefreshToken(demo.getId(), 7);
        verify(userRepository).findByEmail(DemoAccounts.COORDINATOR_EMAIL);
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void submittedIdentityCannotSelectTheOperatorOrAnotherDemoAccount() throws Exception {
        User demo = approvedDemo();
        when(userRepository.findByEmail(DemoAccounts.COORDINATOR_EMAIL)).thenReturn(Optional.of(demo));
        when(tokenProvider.generateAccessToken(any(), any(), any(), anyInt())).thenReturn("access");
        when(tokenProvider.generateRefreshToken(any(), anyInt())).thenReturn("refresh");
        mvc.perform(post("/api/auth/demo-login").param("email", "operator@example.test")
                        .contentType("application/json")
                        .content("{\"email\":\"avery.quinn@example.test\",\"role\":\"COMPANY\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(DemoAccounts.COORDINATOR_EMAIL))
                .andExpect(jsonPath("$.user.role").value("COORDINATOR"));
        verify(userRepository).findByEmail(DemoAccounts.COORDINATOR_EMAIL);
        verifyNoMoreInteractions(userRepository);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
    void nonApprovedFixturesReturnForbiddenWithoutTokens(UserStatus status) throws Exception {
        User demo = approvedDemo();
        demo.setStatus(status);
        when(userRepository.findByEmail(DemoAccounts.COORDINATOR_EMAIL)).thenReturn(Optional.of(demo));
        mvc.perform(post("/api/auth/demo-login"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Demo login unavailable"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist());
        verifyNoInteractions(tokenProvider);
        verify(userRepository).findByEmail(DemoAccounts.COORDINATOR_EMAIL);
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void wrongFixtureRoleReturnsForbiddenWithoutTokens() throws Exception {
        User demo = approvedDemo();
        demo.setRole(UserRole.COMPANY);
        when(userRepository.findByEmail(DemoAccounts.COORDINATOR_EMAIL)).thenReturn(Optional.of(demo));
        mvc.perform(post("/api/auth/demo-login"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Demo login unavailable"));
        verifyNoInteractions(tokenProvider);
        verify(userRepository).findByEmail(DemoAccounts.COORDINATOR_EMAIL);
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void missingFixtureReturnsGenericUnavailableWithoutTokensOrIdentityDetails() throws Exception {
        mvc.perform(post("/api/auth/demo-login"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Demo login unavailable"))
                .andExpect(content().string(not(containsString("SyntheticDemoPassword!"))))
                .andExpect(content().string(not(containsString(DemoAccounts.COORDINATOR_EMAIL))));
        verifyNoInteractions(tokenProvider);
        verify(userRepository).findByEmail(DemoAccounts.COORDINATOR_EMAIL);
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void normalLoginWithoutCredentialsCannotIssueCoordinatorTokens() throws Exception {
        mvc.perform(post("/api/auth/login")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(userRepository, tokenProvider);
    }
}
