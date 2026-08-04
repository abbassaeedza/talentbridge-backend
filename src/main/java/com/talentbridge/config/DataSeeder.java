package com.talentbridge.config;

import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DemoDataSeeder demoDataSeeder;

    @Value("${app.seed.coordinator-email:coordinator@talentbridge.com}")
    private String coordinatorEmail;

    @Value("${app.seed.coordinator-password:AdminTest123!!}")
    private String coordinatorPassword;

    @Value("${app.demo-mode:false}")
    private boolean demoMode;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed();
    }

    public void seed() {
        User coordinator = userRepository.findByEmail(coordinatorEmail)
                .orElseGet(this::createCoordinator);

        if (demoMode && !passwordEncoder.matches(coordinatorPassword, coordinator.getPassword())) {
            coordinator.setPassword(passwordEncoder.encode(coordinatorPassword));
            userRepository.save(coordinator);
            log.info("Demo coordinator password synchronized.");
        }

        if (demoMode) {
            demoDataSeeder.seed(coordinator);
        }
    }

    private User createCoordinator() {
        User coordinator = User.builder()
                .email(coordinatorEmail)
                .password(passwordEncoder.encode(coordinatorPassword))
                .firstName("System")
                .lastName("Coordinator")
                .role(UserRole.COORDINATOR)
                .status(UserStatus.APPROVED)
                .emailVerified(true)
                .build();

        userRepository.save(coordinator);
        log.info("Default coordinator account created: {}", coordinatorEmail);
        return coordinator;
    }
}
