package com.talentbridge.config;

import com.talentbridge.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(DatabaseTestConfig.class)
@TestPropertySource(properties = {"jwt.secret=synthetic-assessment-integration-not-production", "jwt.expiration-ms=60000", "jwt.refresh-expiration-ms=120000"})
class AssessmentQuotaDatabaseIT {
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager transactions;
    JdbcTemplate jdbc;
    AssessmentQuotaService quota;
    final AtomicReference<Instant> now = new AtomicReference<>();
    final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(source);
        jdbc.update("UPDATE assessment_ai_quota SET quota_day=DATE '1970-01-01', attempt_count=0 WHERE id=1");
        now.set(Instant.parse("2026-11-21T18:59:59Z"));
        quota = new AssessmentQuotaService(jdbc, transactions, clock, 50);
    }
    int used() { return jdbc.queryForObject("SELECT attempt_count FROM assessment_ai_quota WHERE id=1", Integer.class); }
    @Test void independentFiftyAttemptsAndExactKarachiReset() {
        int chat = jdbc.queryForObject("SELECT accepted_count FROM ai_quota_global WHERE id=1", Integer.class);
        for (int i=0; i<50; i++) quota.reserve();
        var failure = assertThrows(AssessmentQuotaService.QuotaExceeded.class, () -> quota.reserve());
        assertEquals(Instant.parse("2026-11-21T19:00:00Z"), failure.resetAt());
        assertEquals(50, used());
        assertEquals(chat, jdbc.queryForObject("SELECT accepted_count FROM ai_quota_global WHERE id=1", Integer.class));
        now.set(failure.resetAt()); quota.reserve(); assertEquals(1, used());
        assertEquals("2026-11-22", jdbc.queryForObject("SELECT quota_day::text FROM assessment_ai_quota WHERE id=1", String.class));
    }
    @Test void concurrentReservationsCannotCrossAllowance() throws Exception {
        jdbc.update("UPDATE assessment_ai_quota SET quota_day=DATE '2026-11-21',attempt_count=49 WHERE id=1");
        var executor = Executors.newFixedThreadPool(8); var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i=0; i<8; i++) results.add(executor.submit(() -> {
                start.await(); try { quota.reserve(); return true; } catch (AssessmentQuotaService.QuotaExceeded e) { return false; }
            }));
            start.countDown(); int accepted=0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted); assertEquals(50, used());
        } finally { executor.shutdownNow(); }
    }
    @Test void configurableAllowanceFailedWorkAndReconstructionDoNotRefund() {
        quota = new AssessmentQuotaService(jdbc, transactions, clock, 2);
        assertThrows(IllegalStateException.class, () -> { quota.reserve(); throw new IllegalStateException("synthetic provider failure"); });
        new AssessmentQuotaService(jdbc, transactions, clock, 2).reserve();
        assertThrows(AssessmentQuotaService.QuotaExceeded.class, () -> quota.reserve()); assertEquals(2, used());
    }
    @Test void invalidConfigurationAmbientTransactionsAndBackwardsDaysDoNotCharge() {
        assertThrows(IllegalArgumentException.class, () -> new AssessmentQuotaService(jdbc, transactions, clock, 0));
        new TransactionTemplate(transactions).executeWithoutResult(s -> assertThrows(IllegalStateException.class, () -> quota.reserve()));
        assertEquals(0, used()); quota.reserve(); now.set(now.get().minusSeconds(86400));
        assertThrows(AssessmentAIService.AssessmentException.class, () -> quota.reserve()); assertEquals(1, used());
    }
    @Test void migrationEnforcesIndependentSingletonAndNonnegativeCounterWithoutChatCap() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO assessment_ai_quota(id,quota_day,attempt_count) VALUES (2,DATE '2026-11-21',0)"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE assessment_ai_quota SET attempt_count=-1 WHERE id=1"));
        jdbc.update("UPDATE assessment_ai_quota SET attempt_count=51 WHERE id=1");
        assertEquals(51, used());
    }
    @Test void databaseFailuresExposeOnlyStaticFailureWithoutCause() {
        JdbcTemplate broken = new JdbcTemplate(source) {
            @Override public <T> List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper) {
                throw new org.springframework.dao.DataAccessResourceFailureException("SENSITIVE-DATABASE-URL-AND-PAYLOAD");
            }
        };
        var failing = new AssessmentQuotaService(broken, transactions, clock, 50);
        var error = assertThrows(AssessmentAIService.AssessmentException.class, () -> failing.reserve());
        assertEquals("QUOTA_UNAVAILABLE", error.getMessage()); assertNull(error.getCause()); assertEquals(0, used());
    }
    @Test void quotaClockIsReadAfterAcquiringTheDatabaseLock() throws Exception {
        jdbc.update("UPDATE assessment_ai_quota SET quota_day=DATE '2026-11-21',attempt_count=50 WHERE id=1");
        var executor=Executors.newFixedThreadPool(2); var locked=new CountDownLatch(1); var unlock=new CountDownLatch(1);
        try {
            var holder=executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
                jdbc.queryForObject("SELECT id FROM assessment_ai_quota WHERE id=1 FOR UPDATE",Integer.class);
                locked.countDown(); try { assertTrue(unlock.await(5,TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new IllegalStateException(e); }
            }));
            assertTrue(locked.await(5,TimeUnit.SECONDS)); var attempt=executor.submit(() -> quota.reserve());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3); boolean waiting=false;
            while (System.nanoTime()<deadline) {
                waiting=Boolean.TRUE.equals(jdbc.queryForObject("SELECT count(*)>0 FROM pg_stat_activity WHERE datname=current_database() "
                        + "AND wait_event_type='Lock' AND query LIKE '%SELECT quota_day,attempt_count FROM assessment_ai_quota%'",Boolean.class));
                if (waiting) break; Thread.sleep(10);
            }
            assertTrue(waiting,"Reservation must actually wait on the singleton lock");
            now.set(Instant.parse("2026-11-21T19:00:00Z")); unlock.countDown();
            attempt.get(5,TimeUnit.SECONDS); holder.get(5,TimeUnit.SECONDS); assertEquals(1,used());
        } finally { unlock.countDown(); executor.shutdownNow(); }
    }
    @Test void invalidPreparationCostsNothingButFailedLocalRelayCostsOneWithoutHoldingQuotaLocks() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var calls=new java.util.concurrent.atomic.AtomicInteger(); var unlocked=new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/relay", e -> {
            calls.incrementAndGet();
            try { jdbc.queryForObject("SELECT id FROM assessment_ai_quota WHERE id=1 FOR UPDATE NOWAIT",Integer.class); unlocked.set(true); }
            catch (org.springframework.dao.DataAccessException ignored) { unlocked.set(false); }
            e.sendResponseHeaders(502,-1); e.close();
        }); server.start();
        try {
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper(); var transport=new OpenAIService(mapper);
            org.springframework.test.util.ReflectionTestUtils.setField(transport,"n8nWebhookUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/relay");
            org.springframework.test.util.ReflectionTestUtils.setField(transport,"n8nWebhookSecret","synthetic-local-only");
            org.springframework.test.util.ReflectionTestUtils.setField(transport,"evalModel","gpt-4o");
            var ai=new AssessmentAIService(mapper,transport,quota,new GitHubEvidenceService(mapper));
            var evidence=new GitHubEvidence(new GitHubEvidence.Resolution(null,null,"main","a".repeat(40),"b".repeat(40)),Instant.EPOCH,List.of(),
                    List.of(new GitHubEvidence.FileEvidence(GitHubEvidence.Kind.SOURCE,"Engine.java","c".repeat(40),"d".repeat(64),15,1,1,1,"class Engine {}",false)),
                    new GitHubEvidence.ContributorSnapshot(Instant.EPOCH,false,false,List.of()),List.of(),null);
            var ids=java.util.stream.IntStream.range(0,5).mapToObj(i -> UUID.randomUUID()).toList(); var member=UUID.randomUUID();
            assertThrows(AssessmentAIService.AssessmentException.class, () -> ai.prepareGeneration("x".repeat(64000),evidence,member,ids,List.of()));
            assertEquals(0,used()); assertEquals(0,calls.get());
            var prepared=ai.prepareGeneration("Build a safe Engine",evidence,member,ids,List.of());
            assertThrows(AssessmentAIService.AssessmentException.class, () -> ai.generate(prepared));
            assertEquals(1,used()); assertEquals(1,calls.get()); assertTrue(unlocked.get());
            jdbc.update("UPDATE assessment_ai_quota SET attempt_count=50 WHERE id=1");
            assertThrows(AssessmentQuotaService.QuotaExceeded.class, () -> ai.generate(prepared)); assertEquals(1,calls.get());
        } finally { server.stop(0); }
    }
}
