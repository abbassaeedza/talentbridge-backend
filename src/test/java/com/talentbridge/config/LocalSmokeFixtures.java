package com.talentbridge.config;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.Map;

/** Test-classpath utility; DatabaseTestConfig rejects production and backup databases. */
public final class LocalSmokeFixtures {
    private LocalSmokeFixtures() {}

    public static void main(String[] args) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-smoke", Map.of(
                    "jwt.secret", "synthetic-local-smoke-signing-key-not-a-production-secret",
                    "jwt.expiration-ms", "60000", "jwt.refresh-expiration-ms", "120000")));
            context.register(DatabaseTestConfig.class);
            context.refresh();
            var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
            LegacyDemoFixtures.reset(jdbc);
            var seeder = context.getBean(DemoDataSeeder.class);
            ReflectionTestUtils.setField(seeder, "demoPassword", "SyntheticLocalSmoke!");
            ReflectionTestUtils.setField(seeder, "resetDemoData", true);
            var operator = context.getBean(com.talentbridge.repository.UserRepository.class)
                    .findByEmail("coordinator@talentbridge.com").orElseThrow();
            seeder.seed(operator);
            if (!Integer.valueOf(6).equals(jdbc.queryForObject("SELECT demo_data_version FROM application_settings WHERE id=1", Integer.class)))
                throw new IllegalStateException("Synthetic smoke fixtures were not prepared");
            System.out.println("Synthetic local smoke fixtures ready.");
        }
    }
}
