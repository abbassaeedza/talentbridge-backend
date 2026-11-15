package com.talentbridge.service;

import com.talentbridge.config.ClockConfig;
import com.talentbridge.entity.User;
import com.talentbridge.exception.AiQuotaExceededException;
import com.talentbridge.exception.AiQuotaExceededException.Reason;
import com.talentbridge.exception.ResourceNotFoundException;
import com.talentbridge.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiQuotaServiceTest {
    @Mock UserRepository users;
    @Mock JdbcTemplate jdbc;
    @Mock PlatformTransactionManager transactions;
    @Mock Clock clock;
    final UUID userId = UUID.randomUUID();
    final List<Attempt> attempts = new ArrayList<>();
    Instant now = Instant.parse("2026-11-16T12:00:00Z");
    LocalDate storedDay = LocalDate.of(2026, 11, 16);
    int used;
    AiQuotaService service;

    @BeforeEach
    void setup() {
        service = new AiQuotaService(users, jdbc, transactions, clock);
        lenient().when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(User.builder().build()));
        lenient().when(clock.instant()).thenAnswer(call -> now);
        lenient().when(jdbc.queryForObject(contains("ai_quota_global"), ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(call -> {
            ResultSet row = mock(ResultSet.class);
            when(row.getDate("quota_day")).thenReturn(Date.valueOf(storedDay));
            when(row.getInt("accepted_count")).thenReturn(used);
            return ((RowMapper<?>) call.getArgument(1)).mapRow(row, 0);
        });
        lenient().when(jdbc.query(contains("SELECT accepted_at"), ArgumentMatchers.<RowMapper<Object>>any(), eq(userId), any(Timestamp.class)))
                .thenAnswer(call -> {
                    RowMapper<?> mapper = call.getArgument(1);
                    List<Object> mapped = new ArrayList<>();
                    for (Attempt attempt : attempts) {
                        ResultSet row = mock(ResultSet.class);
                        when(row.getTimestamp("accepted_at")).thenReturn(Timestamp.from(attempt.at()));
                        when(row.getBoolean("released")).thenReturn(attempt.released());
                        mapped.add(mapper.mapRow(row, mapped.size()));
                    }
                    return mapped;
                });
    }

    @Test
    void reservesAtomicallyWithClockReadAfterBothLocks() {
        UUID lease = service.reserve(userId);
        assertNotNull(lease);
        var order = inOrder(users, jdbc, clock, transactions);
        order.verify(users).findByIdForUpdate(userId);
        order.verify(jdbc).queryForObject(contains("FOR UPDATE"), ArgumentMatchers.<RowMapper<Object>>any());
        order.verify(clock).instant();
        order.verify(jdbc).query(contains("SELECT accepted_at"), ArgumentMatchers.<RowMapper<Object>>any(),
                eq(userId), eq(Timestamp.from(now.minusSeconds(90))));
        order.verify(jdbc).update(startsWith("DELETE FROM ai_quota_attempts"),
                eq(userId), eq(Timestamp.from(now.minusSeconds(90))));
        order.verify(jdbc).update(startsWith("UPDATE ai_quota_global"), eq(storedDay), eq(1));
        order.verify(jdbc).update(startsWith("INSERT INTO ai_quota_attempts"),
                eq(lease), eq(userId), eq(Timestamp.from(now)));
        order.verify(transactions).commit(any());
        verify(transactions).getTransaction(argThat(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW
                        && definition.getIsolationLevel() == TransactionDefinition.ISOLATION_READ_COMMITTED
                        && definition.getTimeout() == 5));
    }

    @Test
    void rollsBackRejectionWithoutCharging() {
        used = 50;
        var failure = assertThrows(AiQuotaExceededException.class, () -> service.reserve(userId));
        assertEquals(Reason.GLOBAL_DAILY_LIMIT, failure.getReason());
        assertEquals(7 * 60 * 60, failure.getRetryAfterSeconds());
        verify(transactions).rollback(any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void activeLeaseBlocksForNinetySeconds() {
        attempts.add(new Attempt(now, false));
        assertLimit(Reason.ACCOUNT_IN_FLIGHT, 90);
    }

    @Test
    void roundsRetryUpRatherThanRetryingBeforeExpiry() {
        attempts.add(new Attempt(now.minusSeconds(89).plusMillis(100), false));
        assertLimit(Reason.ACCOUNT_IN_FLIGHT, 2);
    }

    @Test
    void releaseDoesNotRefundTheRollingMinute() {
        for (int offset : new int[]{50, 40, 30, 20, 10}) attempts.add(new Attempt(now.minusSeconds(offset), true));
        assertLimit(Reason.ACCOUNT_RATE_LIMIT, 10);
    }

    @Test
    void exactlySixtySecondsOldIsOutsideTheRollingWindow() {
        for (int offset : new int[]{60, 40, 30, 20, 10}) attempts.add(new Attempt(now.minusSeconds(offset), true));
        assertNotNull(service.reserve(userId));
    }

    @Test
    void exactlyNinetySecondsOldLeaseHasExpired() {
        attempts.add(new Attempt(now.minusSeconds(90), false));
        assertNotNull(service.reserve(userId));
    }

    @Test
    void futureReleasedAttemptsStillCountDuringWithinDayClockRegression() {
        for (int offset : new int[]{10, 20, 30, 40, 50}) attempts.add(new Attempt(now.plusSeconds(offset), true));
        assertLimit(Reason.ACCOUNT_RATE_LIMIT, 70);
    }

    @Test
    void futureUnreleasedLeaseStillBlocksDuringClockRegression() {
        attempts.add(new Attempt(now.plusSeconds(30), false));
        assertLimit(Reason.ACCOUNT_IN_FLIGHT, 120);
    }

    @Test
    void latestConstraintDeterminesTheRetryReason() {
        now = Instant.parse("2026-11-16T18:59:50Z");
        used = 50;
        attempts.add(new Attempt(now, false));
        assertLimit(Reason.ACCOUNT_IN_FLIGHT, 90);
    }

    @Test
    void nextKarachiDayAdvancesCounterWithoutUsingUtcDate() {
        now = Instant.parse("2026-11-16T19:00:00Z");
        used = 50;
        assertNotNull(service.reserve(userId));
        verify(jdbc).update(startsWith("UPDATE ai_quota_global"), eq(LocalDate.of(2026, 11, 17)), eq(1));
    }

    @Test
    void newDayDoesNotDiscardAnActiveLease() {
        now = Instant.parse("2026-11-16T19:00:00Z");
        used = 50;
        attempts.add(new Attempt(now.minusSeconds(1), false));
        assertLimit(Reason.ACCOUNT_IN_FLIGHT, 89);
    }

    @Test
    void clockCannotResetCounterToAnEarlierDay() {
        storedDay = storedDay.plusDays(1);
        var failure = assertThrows(ResponseStatusException.class, () -> service.reserve(userId));
        assertEquals(503, failure.getStatusCode().value());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void missingAccountCannotBeCharged() {
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.reserve(userId));
        verifyNoInteractions(jdbc, clock);
    }

    @Test
    void ambientTransactionIsRejectedBeforeAcquiringLocks() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThrows(IllegalStateException.class, () -> service.reserve(userId));
            verifyNoInteractions(users, jdbc, transactions, clock);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void insertionFailureRollsBackTheCounter() {
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(call -> {
            if (call.<String>getArgument(0).startsWith("INSERT INTO ai_quota_attempts"))
                throw new DataAccessResourceFailureException("synthetic failure");
            return 1;
        });
        assertThrows(DataAccessResourceFailureException.class, () -> service.reserve(userId));
        verify(transactions).rollback(any());
        verify(transactions, never()).commit(any());
    }

    @Test
    void releaseIsAnIdempotentExactTokenUpdateAndNeverRefunds() {
        UUID lease = UUID.randomUUID();
        service.release(lease);
        service.release(lease);
        verify(jdbc, times(2)).update(
                "UPDATE ai_quota_attempts SET released = TRUE WHERE lease_id = ? AND released = FALSE", lease);
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(users, clock);
    }

    @Test
    void releaseFailureCannotMaskTheProviderOutcome() {
        when(jdbc.update(anyString(), any(UUID.class)))
                .thenThrow(new DataAccessResourceFailureException("synthetic release failure"));
        assertDoesNotThrow(() -> service.release(UUID.randomUUID()));
        verify(transactions).rollback(any());
    }

    @Test
    void releaseTransactionStartFailureAlsoLeavesExpiryAsRecovery() {
        when(transactions.getTransaction(any())).thenThrow(new CannotCreateTransactionException("synthetic failure"));
        assertDoesNotThrow(() -> service.release(UUID.randomUUID()));
        verifyNoInteractions(jdbc);
    }

    @Test
    void publicClockBeanUsesUtc() {
        assertEquals(ZoneOffset.UTC, new ClockConfig().clock().getZone());
    }

    private void assertLimit(Reason reason, long retryAfterSeconds) {
        var failure = assertThrows(AiQuotaExceededException.class, () -> service.reserve(userId));
        assertEquals(reason, failure.getReason());
        assertEquals(retryAfterSeconds, failure.getRetryAfterSeconds());
    }

    private record Attempt(Instant at, boolean released) {}
}
