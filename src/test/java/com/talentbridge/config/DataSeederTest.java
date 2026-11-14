package com.talentbridge.config;

import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataSeederTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private DemoDataSeeder demoDataSeeder;
    @InjectMocks private DataSeeder seeder;

    private User coordinator;

    @BeforeEach
    void setUp() {
        coordinator = User.builder()
                .email("coordinator@talentbridge.com")
                .password("old-hash")
                .firstName("Demo")
                .lastName("Coordinator")
                .role(UserRole.COORDINATOR)
                .status(UserStatus.APPROVED)
                .build();
        ReflectionTestUtils.setField(seeder, "coordinatorEmail", coordinator.getEmail());
        ReflectionTestUtils.setField(seeder, "coordinatorPassword", "new-demo-password");
    }

    @Test
    void preservesExistingOperatorPasswordAndIdentityInDemoMode() {
        ReflectionTestUtils.setField(seeder, "demoMode", true);
        when(userRepository.findByEmail(coordinator.getEmail())).thenReturn(Optional.of(coordinator));

        seeder.seed();

        assertEquals("old-hash", coordinator.getPassword());
        assertEquals("coordinator@talentbridge.com", coordinator.getEmail());
        verify(userRepository, never()).save(any());
    }

    @Test
    void preservesExistingCoordinatorPasswordOutsideDemoMode() {
        ReflectionTestUtils.setField(seeder, "demoMode", false);
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(coordinator));

        seeder.seed();

        assertEquals("old-hash", coordinator.getPassword());
        verify(userRepository, never()).save(any());
        verify(demoDataSeeder, never()).seed(any());
    }

    @Test
    void seedsRepresentativeDataInDemoMode() {
        ReflectionTestUtils.setField(seeder, "demoMode", true);
        when(userRepository.findByEmail(coordinator.getEmail())).thenReturn(Optional.of(coordinator));

        seeder.seed();

        verify(demoDataSeeder).seed(coordinator);
    }

    @Test
    void existingOperatorNeedsNoBootstrapPassword() {
        ReflectionTestUtils.setField(seeder, "coordinatorPassword", "");
        when(userRepository.findByEmail(coordinator.getEmail())).thenReturn(Optional.of(coordinator));

        seeder.seed();

        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesToBootstrapWithMissingPassword() {
        ReflectionTestUtils.setField(seeder, "coordinatorPassword", "");

        assertThrows(IllegalStateException.class, seeder::seed);

        verify(userRepository, never()).save(any());
    }

    @Test
    void bootstrapNeverReusesAnotherRole() {
        ReflectionTestUtils.setField(seeder, "demoMode", true);
        coordinator.setRole(UserRole.STUDENT);
        when(userRepository.findByEmail(coordinator.getEmail())).thenReturn(Optional.of(coordinator));

        seeder.seed();

        verify(demoDataSeeder, never()).seed(any());
        verify(userRepository, never()).save(any());
    }
}
