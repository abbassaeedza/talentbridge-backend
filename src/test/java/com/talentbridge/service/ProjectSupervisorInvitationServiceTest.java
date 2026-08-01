package com.talentbridge.service;

import com.talentbridge.dto.request.*;
import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.exception.*;
import com.talentbridge.repository.*;
import com.talentbridge.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectSupervisorInvitationServiceTest {
    @Mock private ProjectSupervisorInvitationRepository invitationRepository;
    @Mock private CompanyProfileRepository companyProfileRepository;
    @Mock private UserRepository userRepository;
    @Mock private SupervisorProfileRepository supervisorProfileRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider tokenProvider;
    @Mock private EmailService emailService;
    @InjectMocks private ProjectSupervisorInvitationService service;

    @Test
    void normalizesEmailAndStoresOnlyAHashWhenCreatingAnInvitation() {
        UUID companyUserId = UUID.randomUUID();
        CompanyProfile company = company(companyUserId, UUID.randomUUID());
        ProjectSupervisorInvitationRequest request = new ProjectSupervisorInvitationRequest();
        request.setEmail("  Mentor@Example.COM ");
        when(companyProfileRepository.findByUserId(companyUserId)).thenReturn(Optional.of(company));
        when(invitationRepository.findByEmailAndStatus("mentor@example.com", ProjectSupervisorInvitationStatus.PENDING)).thenReturn(List.of());
        when(invitationRepository.save(any(ProjectSupervisorInvitation.class))).thenAnswer(i -> i.getArgument(0));

        var response = service.create(companyUserId, request);

        ArgumentCaptor<ProjectSupervisorInvitation> stored = ArgumentCaptor.forClass(ProjectSupervisorInvitation.class);
        ArgumentCaptor<String> mail = ArgumentCaptor.forClass(String.class);
        verify(invitationRepository).save(stored.capture());
        verify(emailService).send(eq("mentor@example.com"), anyString(), mail.capture());
        String rawToken = mail.getValue().substring(mail.getValue().lastIndexOf('/') + 1);
        assertEquals("mentor@example.com", response.getEmail());
        assertEquals(64, stored.getValue().getTokenHash().length());
        assertNotEquals(rawToken, stored.getValue().getTokenHash());
        assertFalse(response.toString().contains(rawToken));
    }

    @Test
    void expiresAnExpiredInvitationDuringValidation() {
        String token = "expired-token";
        ProjectSupervisorInvitation invitation = invitation(company(UUID.randomUUID(), UUID.randomUUID()), "mentor@example.com", token,
                ProjectSupervisorInvitationStatus.PENDING, LocalDateTime.now().minusSeconds(1));
        when(invitationRepository.findByTokenHash(hash(token))).thenReturn(Optional.of(invitation));

        assertThrows(BadRequestException.class, () -> service.validate(token));

        assertEquals(ProjectSupervisorInvitationStatus.EXPIRED, invitation.getStatus());
    }

    @Test
    void invalidatesTheOldInvitationWhenResending() {
        UUID companyUserId = UUID.randomUUID();
        CompanyProfile company = company(companyUserId, UUID.randomUUID());
        ProjectSupervisorInvitation old = invitation(company, "mentor@example.com", "old", ProjectSupervisorInvitationStatus.PENDING, LocalDateTime.now().plusDays(1));
        UUID oldId = UUID.randomUUID(); old.setId(oldId);
        when(companyProfileRepository.findByUserId(companyUserId)).thenReturn(Optional.of(company));
        when(invitationRepository.findById(oldId)).thenReturn(Optional.of(old));
        when(invitationRepository.save(any(ProjectSupervisorInvitation.class))).thenAnswer(i -> i.getArgument(0));

        service.resend(companyUserId, oldId);

        assertEquals(ProjectSupervisorInvitationStatus.REVOKED, old.getStatus());
        verify(invitationRepository).save(argThat(i -> !i.getTokenHash().equals(old.getTokenHash())));
    }

    @Test
    void preventsAnotherCompanyFromRevokingAnInvitation() {
        UUID ownerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        ProjectSupervisorInvitation invitation = invitation(company(ownerId, UUID.randomUUID()), "mentor@example.com", "token", ProjectSupervisorInvitationStatus.PENDING, LocalDateTime.now().plusDays(1));
        UUID id = UUID.randomUUID(); invitation.setId(id);
        when(companyProfileRepository.findByUserId(otherId)).thenReturn(Optional.of(company(otherId, UUID.randomUUID())));
        when(invitationRepository.findById(id)).thenReturn(Optional.of(invitation));

        assertThrows(ForbiddenException.class, () -> service.revoke(otherId, id));
        assertEquals(ProjectSupervisorInvitationStatus.PENDING, invitation.getStatus());
    }

    @Test
    void revokesItsOwnPendingInvitation() {
        UUID companyUserId = UUID.randomUUID();
        CompanyProfile company = company(companyUserId, UUID.randomUUID());
        ProjectSupervisorInvitation invitation = invitation(company, "mentor@example.com", "token", ProjectSupervisorInvitationStatus.PENDING, LocalDateTime.now().plusDays(1));
        UUID id = UUID.randomUUID(); invitation.setId(id);
        when(companyProfileRepository.findByUserId(companyUserId)).thenReturn(Optional.of(company));
        when(invitationRepository.findById(id)).thenReturn(Optional.of(invitation));

        service.revoke(companyUserId, id);

        assertEquals(ProjectSupervisorInvitationStatus.REVOKED, invitation.getStatus());
    }

    @Test
    void rejectsExistingEmailBeforeCreatingAnInvitation() {
        UUID companyUserId = UUID.randomUUID();
        ProjectSupervisorInvitationRequest request = new ProjectSupervisorInvitationRequest(); request.setEmail("mentor@example.com");
        when(companyProfileRepository.findByUserId(companyUserId)).thenReturn(Optional.of(company(companyUserId, UUID.randomUUID())));
        when(userRepository.existsByEmailIgnoreCase("mentor@example.com")).thenReturn(true);

        assertThrows(BadRequestException.class, () -> service.create(companyUserId, request));
        verifyNoInteractions(emailService);
    }

    @Test
    void acceptsOnceAndCreatesAnApprovedProjectSupervisorWithProfile() {
        String token = "accepted-token";
        CompanyProfile company = company(UUID.randomUUID(), UUID.randomUUID());
        ProjectSupervisorInvitation invitation = invitation(company, "mentor@example.com", token,
                ProjectSupervisorInvitationStatus.PENDING, LocalDateTime.now().plusDays(1));
        ProjectSupervisorInvitationAcceptRequest request = acceptRequest();
        when(invitationRepository.findByTokenHashForUpdate(hash(token))).thenReturn(Optional.of(invitation));
        when(userRepository.existsByEmailIgnoreCase("mentor@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hash");
        when(userRepository.save(any(User.class))).thenAnswer(i -> { User user = i.getArgument(0); user.setId(UUID.randomUUID()); return user; });
        when(supervisorProfileRepository.save(any(SupervisorProfile.class))).thenAnswer(i -> { SupervisorProfile profile = i.getArgument(0); profile.setId(UUID.randomUUID()); return profile; });
        when(tokenProvider.generateAccessToken(any(), any(), any())).thenReturn("access");
        when(tokenProvider.generateRefreshToken(any())).thenReturn("refresh");

        var response = service.accept(token, request);

        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        ArgumentCaptor<SupervisorProfile> profile = ArgumentCaptor.forClass(SupervisorProfile.class);
        verify(userRepository).save(user.capture()); verify(supervisorProfileRepository).save(profile.capture());
        assertEquals(UserStatus.APPROVED, user.getValue().getStatus());
        assertEquals(UserRole.PROJECT_SUPERVISOR, user.getValue().getRole());
        assertEquals("Engineering", profile.getValue().getDepartment());
        assertTrue(response.getUser().isOnboardingComplete());
        assertEquals("Senior Mentor", response.getUser().getSupervisorProfile().getJobTitle());
        assertEquals(ProjectSupervisorInvitationStatus.ACCEPTED, invitation.getStatus());
        assertThrows(BadRequestException.class, () -> service.accept(token, request));
    }

    @Test
    void rejectsProfilePhotoUrlsInPublicJsonRequests() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class, () -> mapper.readValue(
                "{\"password\":\"password123\",\"firstName\":\"Project\",\"lastName\":\"Mentor\",\"jobTitle\":\"Mentor\",\"department\":\"Engineering\",\"bio\":\"Bio\",\"profilePhotoUrl\":\"https://attacker.example/photo\"}",
                ProjectSupervisorInvitationAcceptRequest.class));
        assertThrows(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class, () -> mapper.readValue(
                "{\"jobTitle\":\"Mentor\",\"department\":\"Engineering\",\"bio\":\"Bio\",\"profilePhotoUrl\":\"https://attacker.example/photo\"}",
                SupervisorOnboardingRequest.class));
    }

    @Test
    void rejectsAnInvitationWhenAnotherCompanyAlreadyHasAPendingOne() {
        UUID companyUserId = UUID.randomUUID();
        CompanyProfile requester = company(companyUserId, UUID.randomUUID());
        ProjectSupervisorInvitationRequest request = new ProjectSupervisorInvitationRequest(); request.setEmail("mentor@example.com");
        ProjectSupervisorInvitation existing = invitation(company(UUID.randomUUID(), UUID.randomUUID()), "mentor@example.com", "existing", ProjectSupervisorInvitationStatus.PENDING, LocalDateTime.now().plusDays(1));
        when(companyProfileRepository.findByUserId(companyUserId)).thenReturn(Optional.of(requester));
        when(invitationRepository.findByEmailAndStatus("mentor@example.com", ProjectSupervisorInvitationStatus.PENDING)).thenReturn(List.of(existing));

        BadRequestException error = assertThrows(BadRequestException.class, () -> service.create(companyUserId, request));

        assertEquals("A pending invitation already exists for this email. Ask the owning company to resend it.", error.getMessage());
        assertEquals(ProjectSupervisorInvitationStatus.PENDING, existing.getStatus());
        verifyNoInteractions(emailService);
    }

    private ProjectSupervisorInvitationAcceptRequest acceptRequest() {
        ProjectSupervisorInvitationAcceptRequest request = new ProjectSupervisorInvitationAcceptRequest();
        request.setPassword("password123"); request.setFirstName("Project"); request.setLastName("Mentor");
        request.setPhoneNumber("123"); request.setJobTitle("Senior Mentor"); request.setDepartment("Engineering"); request.setBio("Experienced mentor");
        request.setLinkedinUrl("https://linkedin.example/mentor"); return request;
    }
    private CompanyProfile company(UUID userId, UUID companyId) { User user = User.builder().build(); user.setId(userId); CompanyProfile company = CompanyProfile.builder().user(user).companyName("Acme").build(); company.setId(companyId); return company; }
    private ProjectSupervisorInvitation invitation(CompanyProfile company, String email, String rawToken, ProjectSupervisorInvitationStatus status, LocalDateTime expiry) { return ProjectSupervisorInvitation.builder().company(company).email(email).tokenHash(hash(rawToken)).status(status).expiresAt(expiry).build(); }
    private String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new AssertionError(e); } }
}
