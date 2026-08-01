package com.talentbridge.service;

import com.talentbridge.dto.request.*;
import com.talentbridge.dto.response.AuthResponse;
import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.exception.*;
import com.talentbridge.repository.*;
import com.talentbridge.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final ScorecardRepository scorecardRepository;
    private final JwtTokenProvider tokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final NotificationService notificationService;

    @Value("${app.demo-mode:false}")
    private boolean demoMode;

    @Value("${app.seed.coordinator-email:coordinator@talentbridge.com}")
    private String coordinatorEmail;

    @Value("${app.seed.coordinator-password:Admin1234!}")
    private String coordinatorPassword;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (req.getRole() == UserRole.PROJECT_SUPERVISOR)
            throw new BadRequestException("Project supervisors must register with an invitation");
        if (req.getRole() != UserRole.STUDENT && req.getRole() != UserRole.COMPANY
                && req.getRole() != UserRole.PARTY_SUPERVISOR)
            throw new BadRequestException("This role cannot register publicly");
        if (userRepository.existsByEmailIgnoreCase(req.getEmail()))
            throw new BadRequestException("Email already registered");

        User user = User.builder()
                .email(req.getEmail().toLowerCase().trim())
                .password(passwordEncoder.encode(req.getPassword()))
                .firstName(req.getFirstName().trim())
                .lastName(req.getLastName().trim())
                .role(req.getRole())
                .status(req.getRole() == UserRole.COORDINATOR ? UserStatus.APPROVED : UserStatus.PENDING)
                .phoneNumber(req.getPhoneNumber())
                .emailVerified(false)
                .build();

        user = userRepository.save(user);

        if (req.getRole() == UserRole.COMPANY && req.getCompanyName() != null) {
            companyProfileRepository.save(CompanyProfile.builder()
                    .user(user).companyName(req.getCompanyName())
                    .industry(req.getIndustry()).description(req.getCompanyDescription())
                    .website(req.getWebsite()).registrationNumber(req.getRegistrationNumber())
                    .build());
        }

        if (req.getRole() == UserRole.STUDENT) {
            scorecardRepository.save(Scorecard.builder()
                    .student(user).averageScore(0.0).totalProjects(0).build());
        }

        if (req.getRole() != UserRole.COORDINATOR)
            notificationService.notifyCoordinatorsNewRegistration(user);

        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.getEmail().toLowerCase().trim())
                .orElseThrow(() -> new BadRequestException("Invalid email or password"));

        if (!passwordEncoder.matches(req.getPassword(), user.getPassword()))
            throw new BadRequestException("Invalid email or password");

        // SUSPENDED still blocked — no token
        if (user.getStatus() == UserStatus.SUSPENDED)
            throw new ForbiddenException("Account suspended");

        // PENDING and REJECTED now get a token so the frontend can redirect properly
        return buildAuthResponse(user);
    }

    public AuthResponse demoLogin() {
        if (!demoMode) throw new ResourceNotFoundException("Demo login is disabled");

        LoginRequest request = new LoginRequest();
        request.setEmail(coordinatorEmail);
        request.setPassword(coordinatorPassword);
        return login(request);
    }

    public AuthResponse refresh(RefreshTokenRequest req) {
        if (!tokenProvider.validateToken(req.getRefreshToken()))
            throw new BadRequestException("Invalid or expired refresh token");
        UUID userId = tokenProvider.getUserIdFromToken(req.getRefreshToken());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId.toString()));
        if (user.getStatus() == UserStatus.SUSPENDED)
            throw new ForbiddenException("Account suspended");
        return buildAuthResponse(user);
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId.toString()));
        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPassword()))
            throw new BadRequestException("Current password is incorrect");
        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        return AuthResponse.builder()
                .accessToken(tokenProvider.generateAccessToken(
                        user.getId(), user.getEmail(), user.getRole().name()))
                .refreshToken(tokenProvider.generateRefreshToken(user.getId()))
                .user(AuthResponse.UserDto.builder()
                        .id(user.getId()).email(user.getEmail())
                        .firstName(user.getFirstName()).lastName(user.getLastName())
                        .role(user.getRole()).status(user.getStatus())
                        .onboardingComplete(onboardingComplete(user))
                        .githubUsername(user.getGithubUsername())
                        .build())
                .build();
    }

    private boolean onboardingComplete(User user) {
        return switch (user.getRole()) {
            case STUDENT -> user.getStudentProfile() != null;
            case PARTY_SUPERVISOR -> user.getSupervisorProfile() != null;
            default -> true;
        };
    }
}
