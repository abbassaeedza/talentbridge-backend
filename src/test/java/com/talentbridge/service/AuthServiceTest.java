package com.talentbridge.service;

import com.talentbridge.dto.response.AuthResponse;
import com.talentbridge.dto.request.RegisterRequest;
import com.talentbridge.dto.request.LoginRequest;
import com.talentbridge.entity.User;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.exception.BadRequestException;
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

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

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
    void coordinatorLoginRequiresSubmittedPassword() {
        when(userRepository.findByEmail(coordinator.getEmail())).thenReturn(Optional.of(coordinator));
        when(passwordEncoder.matches("demo-password", coordinator.getPassword())).thenReturn(true);
        when(tokenProvider.generateAccessToken(any(), any(), any(), anyInt())).thenReturn("access");
        when(tokenProvider.generateRefreshToken(any(), anyInt())).thenReturn("refresh");

        LoginRequest request = new LoginRequest();
        request.setEmail(coordinator.getEmail());
        request.setPassword("demo-password");
        AuthResponse result = service.login(request);

        assertEquals("access", result.getAccessToken());
        assertEquals("refresh", result.getRefreshToken());
        verify(passwordEncoder).matches("demo-password", coordinator.getPassword());
    }

    @Test
    void rejectsIncorrectCoordinatorPasswordWithoutIssuingTokens() {
        when(userRepository.findByEmail(coordinator.getEmail())).thenReturn(Optional.of(coordinator));
        LoginRequest request = new LoginRequest();
        request.setEmail(coordinator.getEmail());
        request.setPassword("incorrect-test-password");

        assertThrows(BadRequestException.class, () -> service.login(request));
        verifyNoInteractions(tokenProvider);
    }

    @Test
    void rejectsPublicProjectSupervisorRegistration() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("supervisor@example.com");
        request.setPassword("password123");
        request.setFirstName("Project");
        request.setLastName("Supervisor");
        request.setRole(UserRole.PROJECT_SUPERVISOR);

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.register(request));

        assertEquals("Project supervisors must register with an invitation", error.getMessage());
        verifyNoInteractions(passwordEncoder, tokenProvider, notificationService,
                companyProfileRepository, scorecardRepository);
    }

    @Test
    void rejectsPublicCoordinatorRegistration() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("coordinator@example.com");
        request.setPassword("password123");
        request.setFirstName("Coordinator");
        request.setLastName("User");
        request.setRole(UserRole.COORDINATOR);

        BadRequestException error = assertThrows(BadRequestException.class,
                () -> service.register(request));

        assertEquals("This role cannot register publicly", error.getMessage());
        verifyNoInteractions(passwordEncoder, tokenProvider, notificationService,
                companyProfileRepository, scorecardRepository);
    }
}
