package com.talentbridge.service;

import com.talentbridge.exception.AiQuotaExceededException;
import com.talentbridge.exception.AiQuotaExceededException.Reason;
import com.talentbridge.exception.ResourceNotFoundException;
import com.talentbridge.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@Slf4j
public class AiQuotaService {
    private static final ZoneId KARACHI = ZoneId.of("Asia/Karachi");
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public AiQuotaService(UserRepository users, JdbcTemplate jdbc,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.users = users;
        this.jdbc = jdbc;
        this.clock = clock;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(5);
    }

    public UUID reserve(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("AI quota reservation requires a nontransactional caller");
        }
        return transactions.execute(status -> {
            users.findByIdForUpdate(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
            GlobalQuota global = jdbc.queryForObject(
                    "SELECT quota_day, accepted_count FROM ai_quota_global WHERE id = 1 FOR UPDATE",
                    (rs, row) -> new GlobalQuota(rs.getDate("quota_day").toLocalDate(), rs.getInt("accepted_count")));
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            LocalDate today = now.atZone(KARACHI).toLocalDate();
            if (today.isBefore(global.day())) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI quota clock is behind its stored day");
            }
            int used = today.equals(global.day()) ? global.used() : 0;
            List<Attempt> attempts = jdbc.query(
                    "SELECT accepted_at, released FROM ai_quota_attempts "
                            + "WHERE user_id = ? AND accepted_at > ? ORDER BY accepted_at, lease_id",
                    (rs, row) -> new Attempt(rs.getTimestamp("accepted_at").toInstant(), rs.getBoolean("released")),
                    userId, Timestamp.from(now.minusSeconds(90)));

            Instant retryAt = now;
            Reason reason = null;
            for (Attempt attempt : attempts) {
                Instant expiry = attempt.acceptedAt().plusSeconds(90);
                if (!attempt.released() && expiry.isAfter(retryAt)) {
                    retryAt = expiry;
                    reason = Reason.ACCOUNT_IN_FLIGHT;
                }
            }
            // Future timestamps still cost quota if the wall clock moves backwards.
            List<Attempt> recent = attempts.stream()
                    .filter(attempt -> attempt.acceptedAt().isAfter(now.minusSeconds(60))).toList();
            if (recent.size() >= 5) {
                Instant minuteRetry = recent.get(recent.size() - 5).acceptedAt().plusSeconds(60);
                if (!minuteRetry.isBefore(retryAt)) {
                    retryAt = minuteRetry;
                    reason = Reason.ACCOUNT_RATE_LIMIT;
                }
            }
            if (used >= 50) {
                Instant midnight = today.plusDays(1).atStartOfDay(KARACHI).toInstant();
                if (!midnight.isBefore(retryAt)) {
                    retryAt = midnight;
                    reason = Reason.GLOBAL_DAILY_LIMIT;
                }
            }
            if (reason != null) {
                Duration remaining = Duration.between(now, retryAt);
                throw new AiQuotaExceededException(reason,
                        remaining.getSeconds() + (remaining.getNano() == 0 ? 0 : 1));
            }

            jdbc.update("DELETE FROM ai_quota_attempts WHERE user_id = ? AND accepted_at <= ?",
                    userId, Timestamp.from(now.minusSeconds(90)));
            UUID leaseId = UUID.randomUUID();
            jdbc.update("UPDATE ai_quota_global SET quota_day = ?, accepted_count = ? WHERE id = 1", today, used + 1);
            jdbc.update("INSERT INTO ai_quota_attempts (lease_id, user_id, accepted_at) VALUES (?, ?, ?)",
                    leaseId, userId, Timestamp.from(now));
            return leaseId;
        });
    }

    public void release(UUID leaseId) {
        Objects.requireNonNull(leaseId, "leaseId");
        try {
            transactions.executeWithoutResult(status -> jdbc.update(
                    "UPDATE ai_quota_attempts SET released = TRUE WHERE lease_id = ? AND released = FALSE", leaseId));
        } catch (DataAccessException | TransactionException e) {
            log.warn("AI quota lease release failed; the lease will expire", e);
        }
    }

    private record GlobalQuota(LocalDate day, int used) {}
    private record Attempt(Instant acceptedAt, boolean released) {}
}
