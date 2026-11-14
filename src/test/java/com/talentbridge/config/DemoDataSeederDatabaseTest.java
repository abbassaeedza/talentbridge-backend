package com.talentbridge.config;

import com.talentbridge.entity.ApplicationSettings;
import com.talentbridge.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

// Opt-in only: a disposable public-schema restore, never the application's configured database.
@EnabledIfEnvironmentVariable(named = "STAGE_ONE_TEST_DB_URL",
        matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z_][a-zA-Z0-9_]*")
@SpringJUnitConfig(DemoDataSeederDatabaseTest.DatabaseConfig.class)
class DemoDataSeederDatabaseTest {
    @Autowired private DemoDataSeeder seeder;
    @Autowired private UserRepository users;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @jakarta.persistence.PersistenceContext private EntityManager entityManager;

    private JdbcTemplate jdbc;

    @BeforeEach
    void configureIsolatedSeed() {
        jdbc = new JdbcTemplate(dataSource);
        ReflectionTestUtils.setField(seeder, "demoPassword", UUID.randomUUID().toString());
        ReflectionTestUtils.setField(seeder, "resetDemoData", true);
        Integer version = jdbc.queryForObject("select demo_data_version from application_settings where id = 1", Integer.class);
        assertTrue(version == null || version < DemoDataSeeder.DEMO_DATA_VERSION,
                "Use a disposable pre-Stage-1 restore to exercise deletion, not just password sync");
    }

    @Test
    void forcedLateFailureRestoresEveryApplicationRowAndSeedMarker() {
        Map<String, String> before = snapshot();
        String trigger = "stage_one_late_" + UUID.randomUUID().toString().replace("-", "");
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(seeder), "Exercise the actual Spring transaction proxy");
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                "The test must not supply the transaction being verified");
        try {
            jdbc.execute("create function public." + trigger + "() returns trigger language plpgsql as $$ begin "
                    + "if new.demo_data_version = 6 then raise exception 'forced_stage_one_late_failure'; "
                    + "end if; return new; end; $$");
            jdbc.execute("create trigger " + trigger + " before update on public.application_settings "
                    + "for each row execute function public." + trigger + "()");
            var operator = users.findByEmail("coordinator@talentbridge.com").orElseThrow();

            RuntimeException failure = assertThrows(RuntimeException.class, () -> seeder.seed(operator));

            assertTrue(isForcedSqlFailure(failure), "Failure must come from the late marker-update trigger, not earlier cleanup");
            assertTrue(before.equals(snapshot()), "The seeder proxy must roll back every original row and the old seed marker");
        } finally {
            jdbc.execute("drop trigger if exists " + trigger + " on public.application_settings");
            jdbc.execute("drop function if exists public." + trigger + "()");
        }
    }

    @Test
    void replacementPreservesOperatorAndUnlistedUsersAndUsesRealForeignKeys() {
        Map<String, String> before = snapshot();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var preserved = jdbc.queryForList("select id, email, password from users where email <> all (?)",
                    emailArray(java.util.stream.Stream.concat(DemoDataSeeder.LEGACY_USER_EMAILS.stream(),
                            DemoDataSeeder.DEMO_USER_EMAILS.stream()).toList()));
            seedAndFlush();

            assertEquals(23L, jdbc.queryForObject("select count(*) from users where email = any (?)", Long.class,
                    emailArray(DemoDataSeeder.DEMO_USER_EMAILS)));
            assertEquals(0L, jdbc.queryForObject("select count(*) from users where email = any (?)", Long.class,
                    emailArray(DemoDataSeeder.LEGACY_USER_EMAILS)));
            for (var user : preserved) {
                var actual = jdbc.queryForMap("select id, email, password from users where id = ?", user.get("id"));
                assertTrue(user.equals(actual), "Non-demo identities and credentials must remain unchanged");
            }
            status.setRollbackOnly();
        });
        assertTrue(before.equals(snapshot()), "The integration rehearsal must leave its restored database unchanged");
    }

    private void seedAndFlush() {
        var operator = users.findByEmail("coordinator@talentbridge.com").orElseThrow();
        seeder.seed(operator);
        entityManager.flush();
    }

    private SqlArrayValue emailArray(List<String> emails) {
        return new SqlArrayValue("text", emails.toArray());
    }

    private Map<String, String> snapshot() {
        Map<String, String> rows = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("select tablename from pg_tables where schemaname = 'public' order by tablename", String.class)) {
            String quoted = table.replace("\"", "\"\"");
            rows.put(table, jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by to_jsonb(t)::text), '[]'::jsonb)::text "
                    + "from public.\"" + quoted + "\" t", String.class));
        }
        return rows;
    }

    private boolean isForcedSqlFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && "P0001".equals(sql.getSQLState())
                    && sql.getMessage().contains("forced_stage_one_late_failure")) return true;
        }
        return false;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaAuditing
    @EnableJpaRepositories(basePackageClasses = UserRepository.class)
    @Import(DemoDataSeeder.class)
    static class DatabaseConfig {
        @Bean
        DataSource dataSource() {
            String url = System.getenv("STAGE_ONE_TEST_DB_URL");
            if (url == null || !url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[a-zA-Z_][a-zA-Z0-9_]*")) {
                throw new IllegalArgumentException("Database rehearsal requires an explicit loopback-only disposable database URL");
            }
            return new DriverManagerDataSource(url, System.getenv("STAGE_ONE_TEST_DB_USER"),
                    System.getenv("STAGE_ONE_TEST_DB_PASSWORD"));
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(ApplicationSettings.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }
}
