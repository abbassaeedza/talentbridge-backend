package com.talentbridge.config;

import com.talentbridge.dto.request.ChangePasswordRequest;
import com.talentbridge.dto.request.LoginRequest;
import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.exception.BadRequestException;
import com.talentbridge.repository.*;
import com.talentbridge.security.JwtTokenProvider;
import com.talentbridge.service.AuthService;
import com.talentbridge.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(DatabaseTestConfig.class)
@TestPropertySource(properties = {"jwt.secret=synthetic-integration-signing-key-not-a-production-secret",
        "jwt.expiration-ms=60000", "jwt.refresh-expiration-ms=120000"})
class AuthSessionDatabaseIT {
    @Autowired AuthService service;
    @Autowired com.talentbridge.service.UserService userService;
    @Autowired UserRepository users;
    @Autowired CompanyProfileRepository companies;
    @Autowired ScorecardRepository scorecards;
    @Autowired JwtTokenProvider tokens;
    @Autowired NotificationService notifications;
    @Autowired PasswordEncoder encoder;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DataSource source;
    User account;
    JdbcTemplate jdbc;

    @BeforeEach
    void fixture() {
        jdbc = new JdbcTemplate(source);
        jdbc.execute("TRUNCATE TABLE users CASCADE");
        account = users.save(User.builder().email("ordinary@example.test").firstName("Ordinary").lastName("Fixture")
                .role(UserRole.STUDENT).status(UserStatus.APPROVED).password(encoder.encode("InitialPassword!")).build());
    }

    ChangePasswordRequest change(String next) {
        var request = new ChangePasswordRequest();
        request.setCurrentPassword("InitialPassword!");
        request.setNewPassword(next);
        return request;
    }

    @Test
    void concurrentChangesVerifyThePasswordAfterAcquiringTheUserLock() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                users.findByIdForUpdate(account.getId()).orElseThrow();
                locked.countDown();
                try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                service.changePassword(account.getId(), change("FirstReplacement!"));
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> assertThrows(BadRequestException.class,
                    () -> service.changePassword(account.getId(), change("SecondReplacement!"))));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting;
            do {
                waiting = jdbc.queryForObject("select count(*) > 0 from pg_stat_activity where datname=current_database() "
                        + "and wait_event_type='Lock' and pid<>pg_backend_pid()", Boolean.class);
                if (!waiting) Thread.sleep(20);
            } while (!waiting && System.nanoTime() < deadline);
            assertTrue(waiting, "The competing transaction must actually overlap the first change");
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            User saved = users.findById(account.getId()).orElseThrow();
            assertEquals(1, saved.getTokenVersion());
            assertTrue(encoder.matches("FirstReplacement!", saved.getPassword()));
            assertFalse(encoder.matches("SecondReplacement!", saved.getPassword()));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void loginRacingWithAChangeCannotIssueTheNewVersionUsingTheOldPassword() {
        PasswordEncoder racingEncoder = new PasswordEncoder() {
            public String encode(CharSequence password) { return encoder.encode(password); }
            public boolean matches(CharSequence password, String hash) {
                boolean matches = encoder.matches(password, hash);
                service.changePassword(account.getId(), change("ConcurrentReplacement!"));
                return matches;
            }
        };
        var racingLogin = new AuthService(users, companies, scorecards, tokens, racingEncoder, notifications);
        var login = new LoginRequest();
        login.setEmail(account.getEmail());
        login.setPassword("InitialPassword!");
        var response = racingLogin.login(login);
        assertEquals(0, tokens.parseAccessToken(response.getAccessToken()).version());
        assertEquals(0, tokens.parseRefreshToken(response.getRefreshToken()).version());
        assertEquals(1, users.findById(account.getId()).orElseThrow().getTokenVersion());
    }

    @Test
    void refreshRacingWithAChangeCannotUpgradeAnInvalidatedSession() {
        UserRepository racingUsers = org.mockito.Mockito.mock(UserRepository.class);
        org.mockito.Mockito.when(racingUsers.findById(account.getId())).thenAnswer(call -> {
            var snapshot = users.findById(account.getId());
            service.changePassword(account.getId(), change("ConcurrentReplacement!"));
            return snapshot;
        });
        var racingRefresh = new AuthService(racingUsers, companies, scorecards, tokens, encoder, notifications);
        var request = new com.talentbridge.dto.request.RefreshTokenRequest();
        request.setRefreshToken(tokens.generateRefreshToken(account.getId(), 0));
        var response = racingRefresh.refresh(request);
        assertEquals(0, tokens.parseAccessToken(response.getAccessToken()).version());
        assertEquals(0, tokens.parseRefreshToken(response.getRefreshToken()).version());
        assertEquals(1, users.findById(account.getId()).orElseThrow().getTokenVersion());
    }

    @Test
    void githubLinkingCannotRestoreTheOldPasswordOrCredentialVersion() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var password = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                users.findByIdForUpdate(account.getId()).orElseThrow();
                locked.countDown();
                try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                service.changePassword(account.getId(), change("ReplacementPassword!"));
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var linking = executor.submit(() -> userService.linkGitHub(account.getId(), "synthetic-github", "synthetic-oauth-token"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting;
            do {
                waiting = jdbc.queryForObject("select count(*) > 0 from pg_stat_activity where datname=current_database() "
                        + "and wait_event_type='Lock' and pid<>pg_backend_pid()", Boolean.class);
                if (!waiting) Thread.sleep(20);
            } while (!waiting && System.nanoTime() < deadline);
            assertTrue(waiting, "The GitHub transaction must overlap the locked password change");
            release.countDown();
            password.get(10, TimeUnit.SECONDS);
            linking.get(10, TimeUnit.SECONDS);
            User saved = users.findById(account.getId()).orElseThrow();
            assertEquals(1, saved.getTokenVersion());
            assertTrue(encoder.matches("ReplacementPassword!", saved.getPassword()));
            assertFalse(encoder.matches("InitialPassword!", saved.getPassword()));
            assertEquals("synthetic-github", saved.getGithubUsername());
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
