package com.talentbridge.config;

import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.seed.coordinator-email:coordinator@talentbridge.com}")
    private String coordinatorEmail;

    @Value("${app.seed.coordinator-password:Admin1234!}")
    private String coordinatorPassword;

    @PostConstruct
    public void seed() {
        if (userRepository.existsByEmail(coordinatorEmail)) {
            log.info("Coordinator account already exists — skipping seed.");
            return;
        }

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
    }
}
