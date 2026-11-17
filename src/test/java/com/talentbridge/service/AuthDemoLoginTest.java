package com.talentbridge.service;

import com.talentbridge.config.DemoAccounts;
import com.talentbridge.dto.request.LoginRequest;
import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.exception.ForbiddenException;
import com.talentbridge.repository.CompanyProfileRepository;
import com.talentbridge.repository.ScorecardRepository;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.security.JwtTokenProvider;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthDemoLoginTest {
    private static final String SECRET = " SyntheticDemoPassword! ";
    @Mock private UserRepository users;
    @Mock private CompanyProfileRepository companies;
    @Mock private ScorecardRepository scorecards;
    @Mock private NotificationService notifications;
    @Spy private JwtTokenProvider tokens = new JwtTokenProvider();
    @Spy private PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    @InjectMocks private AuthService service;
    private User demo;

    @BeforeEach
    void configureSyntheticAccount() {
        ReflectionTestUtils.setField(service, "demoMode", true);
        ReflectionTestUtils.setField(service, "demoPassword", SECRET);
        ReflectionTestUtils.setField(service, "coordinatorEmail", "operator@example.test");
        ReflectionTestUtils.setField(tokens, "jwtSecret", "synthetic-demo-signing-key-not-a-production-secret");
        ReflectionTestUtils.setField(tokens, "jwtExpiration", 60000L);
        ReflectionTestUtils.setField(tokens, "refreshExpiration", 120000L);
        demo = User.builder().email(DemoAccounts.COORDINATOR_EMAIL)
                .password(new BCryptPasswordEncoder(4).encode(SECRET))
                .firstName("Rowan").lastName("Hale").role(UserRole.COORDINATOR)
                .status(UserStatus.APPROVED).tokenVersion(11).build();
        demo.setId(UUID.randomUUID());
    }

    @AfterEach
    void noWritesOrSecondaryLookups() {
        verify(users, atMostOnce()).findByEmail(DemoAccounts.COORDINATOR_EMAIL);
        verifyNoMoreInteractions(users);
        verifyNoInteractions(companies, scorecards, notifications);
    }

    private void loadDemo() {
        when(users.findByEmail(DemoAccounts.COORDINATOR_EMAIL)).thenReturn(Optional.of(demo));
    }

    private void unavailable() {
        var error = assertThrows(ResponseStatusException.class, service::demoLogin);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
        assertEquals("Demo login unavailable", error.getReason());
        verifyNoInteractions(tokens);
    }

    @Test
    void disabledFlagIsCheckedBeforeSecretsDatabaseOrPasswordWork() {
        ReflectionTestUtils.setField(service, "demoMode", false);
        ReflectionTestUtils.setField(service, "demoPassword", null);
        ReflectionTestUtils.setField(service, "coordinatorEmail", DemoAccounts.COORDINATOR_EMAIL);
        assertFalse(service.isDemoMode());
        var error = assertThrows(ResponseStatusException.class, service::demoLogin);
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(users, encoder, tokens);
    }

    @Test
    void dedicatedAccountUsesRawSecretAndCurrentVersionWithNormalTypedTokens() {
        loadDemo();
        assertTrue(service.isDemoMode());
        var response = service.demoLogin();
        assertEquals(new JwtTokenProvider.TokenIdentity(demo.getId(), 11),
                tokens.parseAccessToken(response.getAccessToken()));
        assertEquals(new JwtTokenProvider.TokenIdentity(demo.getId(), 11),
                tokens.parseRefreshToken(response.getRefreshToken()));
        assertThrows(JwtException.class, () -> tokens.parseAccessToken(response.getRefreshToken()));
        assertThrows(JwtException.class, () -> tokens.parseRefreshToken(response.getAccessToken()));
        assertEquals(demo.getId(), response.getUser().getId());
        assertEquals(DemoAccounts.COORDINATOR_EMAIL, response.getUser().getEmail());
        assertEquals(UserRole.COORDINATOR, response.getUser().getRole());
        assertEquals(UserStatus.APPROVED, response.getUser().getStatus());
        assertTrue(response.getUser().isOnboardingComplete());
        assertEquals("Bearer", response.getTokenType());
        verify(encoder).matches(SECRET, demo.getPassword());
        verify(tokens).generateAccessToken(demo.getId(), demo.getEmail(), "COORDINATOR", 11);
        verify(tokens).generateRefreshToken(demo.getId(), 11);
        assertEquals(11, demo.getTokenVersion());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void missingOrBlankSecretCannotLookUpAccountsOrIssueTokens(String secret) {
        ReflectionTestUtils.setField(service, "demoPassword", secret);
        unavailable();
        verifyNoInteractions(users, encoder);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rowan.hale@example.test", "ROWAN.HALE@EXAMPLE.TEST", "  RoWaN.HaLe@Example.Test  "})
    void operatorIdentityOverlapIsForbiddenEvenWithCaseAndWhitespace(String operatorEmail) {
        ReflectionTestUtils.setField(service, "coordinatorEmail", operatorEmail);
        unavailable();
        verifyNoInteractions(users, encoder);
    }

    @Test
    void missingFixtureCannotFallbackToTheOperator() {
        unavailable();
        verify(users).findByEmail(DemoAccounts.COORDINATOR_EMAIL);
        verifyNoInteractions(encoder);
    }

    @Test
    void mismatchedSecretCannotIssueTokensOrChangeTheStoredHash() {
        loadDemo();
        String storedHash = demo.getPassword();
        ReflectionTestUtils.setField(service, "demoPassword", "WrongSyntheticPassword!");
        unavailable();
        assertEquals(storedHash, demo.getPassword());
        assertEquals(11, demo.getTokenVersion());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"invalid-stored-hash"})
    void missingOrMalformedHashFailsClosed(String hash) {
        demo.setPassword(hash);
        loadDemo();
        unavailable();
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
    void onlyApprovedDemoAccountsCanReceiveTokens(UserStatus status) {
        demo.setStatus(status);
        loadDemo();
        var error = assertThrows(ForbiddenException.class, service::demoLogin);
        assertEquals("Demo login unavailable", error.getMessage());
        verifyNoInteractions(tokens, encoder);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = "COORDINATOR", mode = EnumSource.Mode.EXCLUDE)
    void onlyCoordinatorFixturesCanReceiveDemoTokens(UserRole role) {
        demo.setRole(role);
        loadDemo();
        var error = assertThrows(ForbiddenException.class, service::demoLogin);
        assertEquals("Demo login unavailable", error.getMessage());
        verifyNoInteractions(tokens, encoder);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"APPROVED", "PENDING", "REJECTED"})
    void absentDemoSecretDoesNotChangeNormalCredentialLogin(UserStatus status) {
        ReflectionTestUtils.setField(service, "demoPassword", null);
        demo.setStatus(status);
        loadDemo();
        var request = new LoginRequest();
        request.setEmail(demo.getEmail());
        request.setPassword(SECRET);
        var response = service.login(request);
        assertEquals(status, response.getUser().getStatus());
        assertEquals(new JwtTokenProvider.TokenIdentity(demo.getId(), 11),
                tokens.parseAccessToken(response.getAccessToken()));
    }
}
