package com.talentbridge.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.time.*;

@Service
public class AssessmentQuotaService {
    private static final ZoneId KARACHI=ZoneId.of("Asia/Karachi");
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int allowance;
    private final TransactionTemplate transactions;

    public AssessmentQuotaService(JdbcTemplate jdbc,PlatformTransactionManager manager,Clock clock,
                                  @Value("${assessment.ai.daily-limit:50}") int allowance) {
        if (allowance<1) throw new IllegalArgumentException("Assessment daily limit must be positive");
        this.jdbc=jdbc; this.clock=clock; this.allowance=allowance;
        transactions=new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); transactions.setTimeout(5);
    }
    /** Charge immediately before the provider attempt; failures are not refunded. */
    public void reserve() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Assessment quota requires a nontransactional caller");
        try {
            transactions.executeWithoutResult(status -> {
                var rows=jdbc.query("SELECT quota_day,attempt_count FROM assessment_ai_quota WHERE id=1 FOR UPDATE",
                        (rs,row) -> new Usage(rs.getDate(1).toLocalDate(),rs.getInt(2)));
                if (rows.size()!=1) throw new IllegalStateException("Assessment quota unavailable");
                Usage stored=rows.get(0); LocalDate today=clock.instant().atZone(KARACHI).toLocalDate();
                if (today.isBefore(stored.day())) throw new AssessmentAIService.AssessmentException("QUOTA_CLOCK_REGRESSION");
                int used=today.equals(stored.day())?stored.count():0;
                if (used>=allowance) throw new QuotaExceeded(today.plusDays(1).atStartOfDay(KARACHI).toInstant());
                jdbc.update("UPDATE assessment_ai_quota SET quota_day=?,attempt_count=? WHERE id=1",today,used+1);
            });
        } catch (DataAccessException | TransactionException e) {
            throw new AssessmentAIService.AssessmentException("QUOTA_UNAVAILABLE");
        }
    }
    private record Usage(LocalDate day,int count) { }
    public static final class QuotaExceeded extends RuntimeException {
        private final Instant resetAt;
        public QuotaExceeded(Instant resetAt) { super("ASSESSMENT_DAILY_LIMIT"); this.resetAt=resetAt; }
        public Instant resetAt() { return resetAt; }
    }
}
