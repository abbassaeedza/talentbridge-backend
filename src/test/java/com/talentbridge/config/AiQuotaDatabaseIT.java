package com.talentbridge.config;

import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.exception.AiQuotaExceededException;
import com.talentbridge.exception.AiQuotaExceededException.Reason;
import com.talentbridge.exception.ResourceNotFoundException;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.service.AiQuotaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(classes = {DatabaseTestConfig.class, AiQuotaDatabaseIT.QuotaTestConfig.class},
        initializers = AiQuotaDatabaseIT.DisposableDatabaseOnly.class)
@TestPropertySource(properties = {"jwt.secret=synthetic-quota-integration-signing-key-not-production",
        "jwt.expiration-ms=60000", "jwt.refresh-expiration-ms=120000"})
class AiQuotaDatabaseIT {
    @Autowired AiQuotaService service;
    @Autowired UserRepository users;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MutableClock clock;
    final List<UUID> fixtureUsers = new ArrayList<>();
    JdbcTemplate jdbc;

    @BeforeEach
    void fixture() {
        jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM ai_quota_attempts");
        jdbc.update("UPDATE ai_quota_global SET quota_day = DATE '1970-01-01', accepted_count = 0 WHERE id = 1");
        clock.set(Instant.parse("2026-11-16T12:00:00Z"));
    }

    @AfterEach
    void removeSyntheticAccounts() {
        for (UUID userId : fixtureUsers) jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void failedAcceptedWorkRemainsChargedAfterFinallyRelease() {
        UUID user = account();
        assertThrows(IllegalStateException.class, () -> {
            UUID lease = service.reserve(user);
            try {
                throw new IllegalStateException("synthetic context failure before any provider call");
            } finally {
                service.release(lease);
            }
        });
        assertEquals(1, used());
        assertEquals(1, attemptCount(user));
        assertTrue(jdbc.queryForObject("SELECT released FROM ai_quota_attempts WHERE user_id = ?", Boolean.class, user));
    }

    @Test
    void fiftiethAcceptedRequestSucceedsAndFiftyFirstIsRejected() {
        UUID user = account();
        for (int i = 0; i < 50; i++) {
            service.release(service.reserve(user));
            clock.advanceSeconds(13);
        }
        var failure = assertThrows(AiQuotaExceededException.class, () -> service.reserve(user));
        assertEquals(Reason.GLOBAL_DAILY_LIMIT, failure.getReason());
        assertEquals(50, used());
    }

    @Test
    void releasedAttemptsRemainInTheRollingWindowUntilExactlySixtySeconds() {
        UUID user = account();
        for (int i = 0; i < 5; i++) {
            service.release(service.reserve(user));
            clock.advanceSeconds(1);
        }
        assertLimit(service, user, Reason.ACCOUNT_RATE_LIMIT, 55);
        clock.advanceSeconds(55);
        assertNotNull(service.reserve(user));
        assertEquals(6, used());
    }

    @Test
    void exactLeaseExpiryAllowsReplacementAndOldReleaseCannotClearIt() {
        UUID user = account();
        UUID oldLease = service.reserve(user);
        clock.set(clock.instant().plusSeconds(90).minusNanos(1_000));
        assertLimit(service, user, Reason.ACCOUNT_IN_FLIGHT, 1);
        clock.set(clock.instant().plusNanos(1_000));
        UUID replacement = service.reserve(user);
        service.release(oldLease);
        service.release(oldLease);
        service.release(UUID.randomUUID());
        assertLimit(service, user, Reason.ACCOUNT_IN_FLIGHT, 90);
        assertEquals(2, used());
        assertEquals(1, attemptCount(user));
        service.release(replacement);
        service.release(replacement);
        assertTrue(jdbc.queryForObject("SELECT released FROM ai_quota_attempts WHERE lease_id = ?",
                Boolean.class, replacement));
        assertEquals(2, used());
    }

    @Test
    void reconstructedServiceReadsPersistedLeasesWindowsAndGlobalUsage() {
        UUID user = account();
        UUID lease = service.reserve(user);
        var restarted = new AiQuotaService(users, new JdbcTemplate(source), transactions,
                Clock.fixed(clock.instant(), ZoneOffset.UTC));
        assertLimit(restarted, user, Reason.ACCOUNT_IN_FLIGHT, 90);
        restarted.release(lease);
        for (int i = 0; i < 4; i++) restarted.release(restarted.reserve(user));
        assertLimit(restarted, user, Reason.ACCOUNT_RATE_LIMIT, 60);
        jdbc.update("UPDATE ai_quota_global SET accepted_count = 50 WHERE id = 1");
        assertLimit(restarted, account(), Reason.GLOBAL_DAILY_LIMIT, 7 * 60 * 60);
    }

    @Test
    void parallelAccountsCannotRacePastGlobalFifty() throws Exception {
        jdbc.update("UPDATE ai_quota_global SET quota_day = DATE '2026-11-16', accepted_count = 49 WHERE id = 1");
        List<UUID> contenders = new ArrayList<>();
        for (int i = 0; i < 8; i++) contenders.add(account());
        assertEquals(1, race(contenders, true));
        assertEquals(50, used());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ai_quota_attempts", Integer.class));
    }

    @Test
    void parallelRequestsForOneAccountCreateExactlyOneLiveLease() throws Exception {
        UUID user = account();
        assertEquals(1, race(Collections.nCopies(8, user), false));
        assertEquals(1, used());
        assertEquals(1, attemptCount(user));
    }

    @Test
    void parallelRequestsCannotRacePastTheFifthRollingAttempt() throws Exception {
        UUID user = account();
        for (int i = 1; i <= 4; i++) seedAttempt(user, clock.instant().minusSeconds(i), true);
        jdbc.update("UPDATE ai_quota_global SET quota_day = DATE '2026-11-16', accepted_count = 4 WHERE id = 1");
        assertEquals(1, race(Collections.nCopies(8, user), true));
        assertEquals(5, used());
        assertEquals(5, attemptCount(user));
    }

    @Test
    void midnightResetsOnlyGlobalCountNotAnOldDaysLease() {
        clock.set(Instant.parse("2026-11-16T18:59:59Z"));
        jdbc.update("UPDATE ai_quota_global SET quota_day = DATE '2026-11-16', accepted_count = 49 WHERE id = 1");
        UUID user = account();
        UUID oldLease = service.reserve(user);
        assertEquals(50, used());
        clock.advanceSeconds(1);
        assertLimit(service, user, Reason.ACCOUNT_IN_FLIGHT, 89);
        service.release(service.reserve(account()));
        assertEquals("2026-11-17", quotaDay());
        assertEquals(1, used());
        service.release(oldLease);
        assertNotNull(service.reserve(user));
        assertEquals(2, used());
    }

    @Test
    void rollingMinuteAlsoCrossesKarachiMidnight() {
        clock.set(Instant.parse("2026-11-16T18:59:59Z"));
        UUID user = account();
        for (int i = 0; i < 5; i++) service.release(service.reserve(user));
        clock.advanceSeconds(1);
        service.release(service.reserve(account()));
        assertLimit(service, user, Reason.ACCOUNT_RATE_LIMIT, 59);
        assertEquals("2026-11-17", quotaDay());
        assertEquals(1, used());
    }

    @Test
    void withinDayClockRegressionDoesNotHideFutureReleasedCosts() {
        UUID user = account();
        Instant acceptedAt = clock.instant();
        for (int i = 0; i < 5; i++) service.release(service.reserve(user));
        clock.set(acceptedAt.minusSeconds(60));
        assertLimit(service, user, Reason.ACCOUNT_RATE_LIMIT, 120);
        clock.set(acceptedAt.plusSeconds(60).minusNanos(1_000));
        assertLimit(service, user, Reason.ACCOUNT_RATE_LIMIT, 1);
        clock.set(acceptedAt.plusSeconds(60));
        assertNotNull(service.reserve(user));
    }

    @Test
    void withinDayClockRegressionDoesNotHideFutureLease() {
        UUID user = account();
        service.reserve(user);
        clock.advanceSeconds(-10);
        assertLimit(service, user, Reason.ACCOUNT_IN_FLIGHT, 100);
        assertEquals(1, used());
    }

    @Test
    void backwardsDayNeverResetsCounter() {
        service.release(service.reserve(account()));
        clock.advanceSeconds(-24 * 60 * 60);
        var failure = assertThrows(ResponseStatusException.class, () -> service.reserve(account()));
        assertEquals(503, failure.getStatusCode().value());
        assertEquals("2026-11-16", quotaDay());
        assertEquals(1, used());
    }

    @Test
    void failedInsertRollsBackRealDatabaseCounter() {
        JdbcTemplate failingJdbc = new JdbcTemplate(source) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.startsWith("INSERT INTO ai_quota_attempts"))
                    throw new DataAccessResourceFailureException("synthetic insert failure");
                return super.update(sql, args);
            }
        };
        UUID user = account();
        var failing = new AiQuotaService(users, failingJdbc, transactions, clock);
        assertThrows(DataAccessResourceFailureException.class, () -> failing.reserve(user));
        assertEquals(0, used());
        assertEquals(0, attemptCount(user));
        assertNotNull(service.reserve(user));
        assertEquals(1, used());
    }

    @Test
    void reserveRejectsAmbientTransactionWithoutCharging() {
        UUID user = account();
        new TransactionTemplate(transactions).executeWithoutResult(status ->
                assertThrows(IllegalStateException.class, () -> service.reserve(user)));
        assertEquals(0, used());
        assertEquals(0, attemptCount(user));
    }

    @Test
    void accountDeletionCascadesAttemptsWithoutRefundingGlobalUsage() {
        UUID user = account();
        UUID lease = service.reserve(user);
        jdbc.update("DELETE FROM users WHERE id = ?", user);
        service.release(lease);
        assertEquals(0, attemptCount(user));
        assertEquals(1, used());
        assertThrows(ResourceNotFoundException.class, () -> service.reserve(user));
        assertEquals(1, used());
    }

    @Test
    void clockIsReadAfterWaitingForTheUserLock() throws Exception {
        assertClockAfterLock(true);
    }

    @Test
    void clockIsReadAfterWaitingForTheGlobalLock() throws Exception {
        assertClockAfterLock(false);
    }

    @Test
    void deletionRacingWithReservationCannotDeadlockOrRefundItsCharge() throws Exception {
        UUID user = account();
        var executor = Executors.newFixedThreadPool(3);
        var locked = new CountDownLatch(1);
        var unlock = new CountDownLatch(1);
        try {
            var holder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM ai_quota_global WHERE id = 1 FOR UPDATE", Integer.class);
                locked.countDown();
                await(unlock);
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var reservation = executor.submit(() -> service.reserve(user));
            awaitDatabaseLock("ai_quota_global");
            var deletion = executor.submit(() -> jdbc.update("DELETE FROM users WHERE id = ?", user));
            awaitDatabaseLock("DELETE FROM users");
            unlock.countDown();
            UUID lease = reservation.get(10, TimeUnit.SECONDS);
            assertEquals(1, deletion.get(10, TimeUnit.SECONDS));
            holder.get(10, TimeUnit.SECONDS);
            service.release(lease);
            assertEquals(0, attemptCount(user));
            assertEquals(1, used());
        } finally {
            unlock.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void migrationEnforcesSingletonCounterAndUserForeignKey() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO ai_quota_global (id, quota_day, accepted_count) VALUES (2, DATE '2026-11-16', 0)"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE ai_quota_global SET accepted_count = 51 WHERE id = 1"));
        assertThrows(DataIntegrityViolationException.class, () -> seedAttempt(UUID.randomUUID(), clock.instant(), false));
        assertEquals(0, used());
    }

    private void assertClockAfterLock(boolean userLock) throws Exception {
        UUID user = account();
        clock.set(Instant.parse("2026-11-16T18:59:59Z"));
        jdbc.update("UPDATE ai_quota_global SET quota_day = DATE '2026-11-16', accepted_count = 50 WHERE id = 1");
        var executor = Executors.newFixedThreadPool(2);
        var locked = new CountDownLatch(1);
        var unlock = new CountDownLatch(1);
        try {
            var holder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                if (userLock) users.findByIdForUpdate(user).orElseThrow();
                else jdbc.queryForObject("SELECT id FROM ai_quota_global WHERE id = 1 FOR UPDATE", Integer.class);
                locked.countDown();
                await(unlock);
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var reservation = executor.submit(() -> service.reserve(user));
            awaitDatabaseLock(userLock ? "users" : "ai_quota_global");
            clock.advanceSeconds(1);
            unlock.countDown();
            assertNotNull(reservation.get(10, TimeUnit.SECONDS));
            holder.get(10, TimeUnit.SECONDS);
            assertEquals("2026-11-17", quotaDay());
            assertEquals(1, used());
        } finally {
            unlock.countDown();
            executor.shutdownNow();
        }
    }

    private int race(List<UUID> contenders, boolean release) throws Exception {
        var executor = Executors.newFixedThreadPool(contenders.size());
        var ready = new CountDownLatch(contenders.size());
        var start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        try {
            for (UUID user : contenders) outcomes.add(executor.submit(() -> {
                ready.countDown();
                await(start);
                try {
                    UUID lease = service.reserve(user);
                    if (release) service.release(lease);
                    return true;
                } catch (AiQuotaExceededException expected) {
                    return false;
                }
            }));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> outcome : outcomes) if (outcome.get(10, TimeUnit.SECONDS)) accepted++;
            return accepted;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private void awaitDatabaseLock(String queryPart) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT count(*) > 0 FROM pg_stat_activity WHERE datname = current_database() "
                            + "AND wait_event_type = 'Lock' AND pid <> pg_backend_pid() AND query ILIKE ?",
                    Boolean.class, "%" + queryPart + "%"))) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("The competing database transaction must actually wait on the expected lock");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private UUID account() {
        UUID id = users.save(User.builder().email(UUID.randomUUID() + "@quota.example.test")
                .firstName("Quota").lastName("Fixture").password("synthetic-unused-password")
                .role(UserRole.STUDENT).status(UserStatus.APPROVED).build()).getId();
        fixtureUsers.add(id);
        return id;
    }

    private void seedAttempt(UUID user, Instant at, boolean released) {
        jdbc.update("INSERT INTO ai_quota_attempts (lease_id, user_id, accepted_at, released) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), user, Timestamp.from(at), released);
    }

    private int used() {
        return jdbc.queryForObject("SELECT accepted_count FROM ai_quota_global WHERE id = 1", Integer.class);
    }

    private String quotaDay() {
        return jdbc.queryForObject("SELECT quota_day::text FROM ai_quota_global WHERE id = 1", String.class);
    }

    private int attemptCount(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM ai_quota_attempts WHERE user_id = ?", Integer.class, user);
    }

    private static void assertLimit(AiQuotaService service, UUID user, Reason reason, long seconds) {
        var failure = assertThrows(AiQuotaExceededException.class, () -> service.reserve(user));
        assertEquals(reason, failure.getReason());
        assertEquals(seconds, failure.getRetryAfterSeconds());
    }

    public static class DisposableDatabaseOnly implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            String url = System.getenv("TALENTBRIDGE_TEST_DB_URL");
            if (url == null || !url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):32772/talentbridge_test"))
                throw new IllegalStateException("Quota database tests require the exclusively approved disposable database on port 32772");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class QuotaTestConfig {
        @Bean
        MutableClock quotaClock() { return new MutableClock(); }

        @Bean
        AiQuotaService aiQuotaService(UserRepository users, DataSource source,
                                      PlatformTransactionManager transactions, MutableClock clock) {
            return new AiQuotaService(users, new JdbcTemplate(source), transactions, clock);
        }
    }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> instant = new AtomicReference<>(Instant.parse("2026-11-16T12:00:00Z"));
        void set(Instant value) { instant.set(value); }
        void advanceSeconds(long seconds) { instant.updateAndGet(value -> value.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            return zone.equals(getZone()) ? this : Clock.fixed(instant(), zone);
        }
        @Override public Instant instant() { return instant.get(); }
    }
}
