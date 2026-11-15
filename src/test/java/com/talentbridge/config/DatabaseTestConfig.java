package com.talentbridge.config;

import com.talentbridge.entity.User;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.security.JwtTokenProvider;
import com.talentbridge.service.AuthService;
import com.talentbridge.service.NotificationService;
import com.talentbridge.service.FileStorageService;
import com.talentbridge.service.UserService;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.util.Map;

@TestConfiguration(proxyBeanMethods = false)
@EnableTransactionManagement
@EnableJpaAuditing
@EnableJpaRepositories(basePackageClasses = UserRepository.class)
@Import({DemoDataSeeder.class, AuthService.class, JwtTokenProvider.class, UserService.class})
public class DatabaseTestConfig {
    @Bean
    DataSource dataSource() {
        String url = System.getenv("TALENTBRIDGE_TEST_DB_URL");
        if (url == null || !url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/talentbridge_test"))
            throw new IllegalArgumentException("Integration tests require an explicitly named loopback-only talentbridge_test database");
        return new DriverManagerDataSource(url, System.getenv("TALENTBRIDGE_TEST_DB_USER"),
                System.getenv("TALENTBRIDGE_TEST_DB_PASSWORD"));
    }

    @Bean(initMethod = "migrate")
    Flyway flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
    }

    @Bean
    @DependsOn("flyway")
    LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan(User.class.getPackageName());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        return factory;
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(4); }

    @Bean
    NotificationService notificationService() { return org.mockito.Mockito.mock(NotificationService.class); }

    @Bean
    FileStorageService fileStorageService() { return org.mockito.Mockito.mock(FileStorageService.class); }
}
