package com.talentbridge.service;

import com.talentbridge.dto.request.ProjectSupervisorInvitationAcceptRequest;
import com.talentbridge.dto.request.ProjectSupervisorInvitationRequest;
import com.talentbridge.dto.response.AuthResponse;
import com.talentbridge.dto.response.ProjectSupervisorInvitationResponse;
import com.talentbridge.dto.response.UserResponse;
import com.talentbridge.dto.response.SupervisorProfileResponse;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ProjectSupervisorInvitationService {
    private final ProjectSupervisorInvitationRepository invitationRepository;
    private final CompanyProfileRepository companyProfileRepository;
    private final UserRepository userRepository;
    private final SupervisorProfileRepository supervisorProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final EmailService emailService;
    @Value("${app.frontend-url}") private String frontendUrl;

    @Transactional
    public ProjectSupervisorInvitationResponse create(UUID companyUserId, ProjectSupervisorInvitationRequest request) {
        CompanyProfile company = company(companyUserId);
        String email = normalize(request.getEmail());
        if (userRepository.existsByEmailIgnoreCase(email)) throw new BadRequestException("Email already registered");
        if (!invitationRepository.findByEmailAndStatus(email, ProjectSupervisorInvitationStatus.PENDING).isEmpty())
            throw new BadRequestException("A pending invitation already exists for this email. Ask the owning company to resend it.");
        return createAndSend(company, email);
    }

    @Transactional
    public ProjectSupervisorInvitationResponse resend(UUID companyUserId, UUID invitationId) {
        CompanyProfile company = company(companyUserId);
        ProjectSupervisorInvitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation", invitationId.toString()));
        if (!invitation.getCompany().getId().equals(company.getId())) throw new ForbiddenException("You cannot resend this invitation");
        if (invitation.getStatus() != ProjectSupervisorInvitationStatus.PENDING) throw new BadRequestException("Only pending invitations can be resent");
        invitation.setStatus(ProjectSupervisorInvitationStatus.REVOKED);
        return createAndSend(company, invitation.getEmail());
    }

    @Transactional
    public void revoke(UUID companyUserId, UUID invitationId) {
        CompanyProfile company = company(companyUserId);
        ProjectSupervisorInvitation invitation = invitationRepository.findById(invitationId)
                .orElseThrow(() -> new ResourceNotFoundException("Invitation", invitationId.toString()));
        if (!invitation.getCompany().getId().equals(company.getId())) throw new ForbiddenException("You cannot revoke this invitation");
        if (invitation.getStatus() != ProjectSupervisorInvitationStatus.PENDING) throw new BadRequestException("Only pending invitations can be revoked");
        invitation.setStatus(ProjectSupervisorInvitationStatus.REVOKED);
    }

    public List<ProjectSupervisorInvitationResponse> list(UUID companyUserId) {
        CompanyProfile company = company(companyUserId);
        return invitationRepository.findByCompanyIdOrderByCreatedAtDesc(company.getId()).stream().map(this::toResponse).toList();
    }

    public List<UserResponse> listAcceptedSupervisors(UUID companyUserId, UserService userService) {
        CompanyProfile company = company(companyUserId);
        return supervisorProfileRepository.findByCompanyId(company.getId()).stream()
                .map(SupervisorProfile::getUser)
                .filter(user -> user.getRole() == UserRole.PROJECT_SUPERVISOR && user.getStatus() == UserStatus.APPROVED)
                .map(userService::toResponse).toList();
    }

    @Transactional
    public ProjectSupervisorInvitationResponse validate(String token) {
        ProjectSupervisorInvitation invitation = invitationRepository.findByTokenHash(hash(token))
                .orElseThrow(() -> new BadRequestException("Invitation is invalid"));
        verifyValid(invitation);
        return toResponse(invitation);
    }

    @Transactional
    public AuthResponse accept(String token, ProjectSupervisorInvitationAcceptRequest request) {
        ProjectSupervisorInvitation invitation = validForUpdate(token);
        if (userRepository.existsByEmailIgnoreCase(invitation.getEmail())) throw new BadRequestException("Email already registered");
        User user = userRepository.save(User.builder().email(invitation.getEmail())
                .password(passwordEncoder.encode(request.getPassword())).firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim()).phoneNumber(request.getPhoneNumber())
                .role(UserRole.PROJECT_SUPERVISOR).status(UserStatus.APPROVED).emailVerified(false).build());
        SupervisorProfile profile = supervisorProfileRepository.save(SupervisorProfile.builder().user(user).company(invitation.getCompany())
                .jobTitle(request.getJobTitle().trim()).department(request.getDepartment().trim()).bio(request.getBio().trim())
                .linkedinUrl(request.getLinkedinUrl()).build());
        invitation.setStatus(ProjectSupervisorInvitationStatus.ACCEPTED);
        invitation.setAcceptedAt(LocalDateTime.now());
        return AuthResponse.builder().accessToken(tokenProvider.generateAccessToken(user.getId(), user.getEmail(), user.getRole().name()))
                .refreshToken(tokenProvider.generateRefreshToken(user.getId()))
                .user(AuthResponse.UserDto.builder().id(user.getId()).email(user.getEmail()).firstName(user.getFirstName())
                        .lastName(user.getLastName()).role(user.getRole()).status(user.getStatus()).onboardingComplete(true)
                        .supervisorProfile(SupervisorProfileResponse.builder().id(profile.getId()).jobTitle(profile.getJobTitle())
                                .department(profile.getDepartment()).bio(profile.getBio()).linkedinUrl(profile.getLinkedinUrl())
                                .profilePhotoUrl(profile.getProfilePhotoUrl()).build()).build()).build();
    }

    private ProjectSupervisorInvitationResponse createAndSend(CompanyProfile company, String email) {
        String token = token();
        ProjectSupervisorInvitation invitation = invitationRepository.save(ProjectSupervisorInvitation.builder()
                .company(company).email(email).tokenHash(hash(token)).status(ProjectSupervisorInvitationStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusDays(7)).build());
        emailService.send(email, "TalentBridge project supervisor invitation",
                "You were invited by " + company.getCompanyName() + ". Accept within 7 days: "
                        + frontendUrl + "/project-supervisor-invitations/" + token);
        return toResponse(invitation);
    }

    private ProjectSupervisorInvitation validForUpdate(String token) {
        ProjectSupervisorInvitation invitation = invitationRepository.findByTokenHashForUpdate(hash(token))
                .orElseThrow(() -> new BadRequestException("Invitation is invalid"));
        verifyValid(invitation);
        return invitation;
    }
    private void verifyValid(ProjectSupervisorInvitation invitation) {
        if (invitation.getStatus() != ProjectSupervisorInvitationStatus.PENDING) throw new BadRequestException("Invitation is no longer available");
        if (!invitation.getExpiresAt().isAfter(LocalDateTime.now())) {
            invitation.setStatus(ProjectSupervisorInvitationStatus.EXPIRED);
            throw new BadRequestException("Invitation has expired");
        }
    }
    private CompanyProfile company(UUID userId) { return companyProfileRepository.findByUserId(userId).orElseThrow(() -> new ForbiddenException("A company account is required")); }
    private ProjectSupervisorInvitationResponse toResponse(ProjectSupervisorInvitation invitation) { return ProjectSupervisorInvitationResponse.builder().id(invitation.getId()).email(invitation.getEmail()).companyName(invitation.getCompany().getCompanyName()).status(invitation.getStatus()).expiresAt(invitation.getExpiresAt()).build(); }
    private String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private String token() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
}
