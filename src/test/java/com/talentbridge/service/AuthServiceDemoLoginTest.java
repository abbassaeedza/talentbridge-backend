package com.talentbridge.service;

import com.talentbridge.dto.response.AuthResponse;
import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.exception.ResourceNotFoundException;
import com.talentbridge.repository.CompanyProfileRepository;
import com.talentbridge.repository.ScorecardRepository;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceDemoLoginTest {

    @Mock private UserRepository userRepository;
    @Mock private CompanyProfileRepository companyProfileRepository;
    @Mock private ScorecardRepository scorecardRepository;
    @Mock private JwtTokenProvider tokenProvider;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private NotificationService notificationService;
    @InjectMocks private AuthService service;

    private User coordinator;

    @BeforeEach
    void setUp() {
        coordinator = User.builder()
                .email("coordinator@talentbridge.com")
                .password("stored-hash")
                .firstName("Demo")
                .lastName("Coordinator")
                .role(UserRole.COORDINATOR)
                .status(UserStatus.APPROVED)
                .build();
        coordinator.setId(UUID.randomUUID());
    }

    @Test
    void logsInConfiguredCoordinatorWhenDemoModeIsEnabled() {
        ReflectionTestUtils.setField(service, "demoMode", true);
        ReflectionTestUtils.setField(service, "coordinatorEmail", coordinator.getEmail());
        ReflectionTestUtils.setField(service, "coordinatorPassword", "demo-password");
        when(userRepository.findByEmail(coordinator.getEmail())).thenReturn(Optional.of(coordinator));
        when(passwordEncoder.matches("demo-password", coordinator.getPassword())).thenReturn(true);
        when(tokenProvider.generateAccessToken(any(), any(), any())).thenReturn("access");
        when(tokenProvider.generateRefreshToken(any())).thenReturn("refresh");

        AuthResponse result = service.demoLogin();

        assertEquals("access", result.getAccessToken());
        assertEquals("refresh", result.getRefreshToken());
        verify(passwordEncoder).matches("demo-password", coordinator.getPassword());
    }

    @Test
    void hidesDemoLoginWhenDemoModeIsDisabled() {
        ReflectionTestUtils.setField(service, "demoMode", false);

        assertThrows(ResourceNotFoundException.class, service::demoLogin);
        verifyNoInteractions(userRepository);
    }
}
