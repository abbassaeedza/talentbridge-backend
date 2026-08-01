package com.talentbridge.service;

import com.talentbridge.dto.request.StudentOnboardingRequest;
import com.talentbridge.dto.request.SupervisorOnboardingRequest;
import com.talentbridge.dto.request.NotificationPreferenceRequest;
import com.talentbridge.dto.response.PageResponse;
import com.talentbridge.dto.response.UserResponse;
import com.talentbridge.dto.response.SupervisorProfileResponse;
import com.talentbridge.dto.response.NotificationPreferenceResponse;
import com.talentbridge.entity.*;
import com.talentbridge.enums.NotificationType;
import com.talentbridge.enums.ModerationEventType;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.exception.*;
import com.talentbridge.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final SupervisorProfileRepository supervisorProfileRepository;
    private final ScorecardRepository scorecardRepository;
    private final UserModerationEventRepository moderationEventRepository;
    private final PartyRepository partyRepository;
    private final ApplicationRepository applicationRepository;
    private final NotificationService notificationService;
    private final FileStorageService fileStorageService;
    private final NotificationPreferenceRepository notificationPreferenceRepository;

    public User getById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));
    }

    public List<NotificationPreferenceResponse> getNotificationPreferences(UUID userId) {
        getById(userId);
        Map<NotificationType, NotificationPreference> saved = notificationPreferenceRepository.findByUserId(userId)
                .stream().collect(java.util.stream.Collectors.toMap(NotificationPreference::getType, p -> p));
        return Arrays.stream(NotificationType.values())
                .map(type -> new NotificationPreferenceResponse(type,
                        saved.get(type) == null || saved.get(type).isEmailEnabled()))
                .toList();
    }

    @Transactional
    public NotificationPreferenceResponse updateNotificationPreference(UUID userId, NotificationType type,
                                                                        NotificationPreferenceRequest req) {
        User user = getById(userId);
        NotificationPreference preference = notificationPreferenceRepository.findByUserIdAndType(userId, type)
                .orElseGet(() -> NotificationPreference.builder().user(user).type(type).build());
        preference.setEmailEnabled(req.getEmailEnabled());
        NotificationPreference saved = notificationPreferenceRepository.save(preference);
        return new NotificationPreferenceResponse(saved.getType(), saved.isEmailEnabled());
    }

    public UserResponse getStudentProfile(UUID studentId, UUID viewerId) {
        User viewer = getById(viewerId);
        User student = getById(studentId);
        if (student.getRole() != UserRole.STUDENT)
            throw new BadRequestException("User is not a student");
        if (viewer.getRole() == UserRole.COORDINATOR)
            return toCoordinatorResponse(student);
        if (student.getStatus() != UserStatus.APPROVED)
            throw new ForbiddenException("This student profile is not available");
        boolean allowed = switch (viewer.getRole()) {
            case STUDENT -> studentId.equals(viewerId);
            case COMPANY -> applicationRepository.existsForCompanyAndStudent(viewerId, studentId);
            case PARTY_SUPERVISOR -> partyRepository.existsByStudentAndPartySupervisor(studentId, viewerId);
            case PROJECT_SUPERVISOR -> partyRepository.existsByStudentAndProjectSupervisor(studentId, viewerId);
            default -> false;
        };
        if (!allowed)
            throw new ForbiddenException("Student profiles are not available for this role");
        return toResponse(student);
    }

    @Transactional
    public StudentProfile completeOnboarding(UUID userId, StudentOnboardingRequest req) {
        User user = getById(userId);
        StudentProfile profile = studentProfileRepository.findByUserId(userId)
                .orElse(StudentProfile.builder().user(user).build());
        profile.setAge(req.getAge());
        profile.setUniversity(req.getUniversity());
        profile.setYearOfStudy(req.getYearOfStudy());
        profile.setMajor(req.getMajor());
        profile.setSkills(req.getSkills() != null ? req.getSkills() : List.of());
        profile.setPastExperience(req.getPastExperience());
        profile.setBio(req.getBio());
        profile.setLinkedinUrl(req.getLinkedinUrl());
        profile.setPortfolioUrl(req.getPortfolioUrl());
        profile.setGpa(req.getGpa());
        return studentProfileRepository.save(profile);
    }

    @Transactional
    public SupervisorProfile completeSupervisorOnboarding(UUID userId, SupervisorOnboardingRequest req) {
        User user = getById(userId);
        if (user.getRole() != UserRole.PARTY_SUPERVISOR) throw new ForbiddenException("Only party supervisors can complete this onboarding");
        SupervisorProfile profile = supervisorProfileRepository.findByUserId(userId)
                .orElse(SupervisorProfile.builder().user(user).build());
        profile.setJobTitle(req.getJobTitle());
        profile.setDepartment(req.getDepartment());
        profile.setBio(req.getBio());
        profile.setLinkedinUrl(req.getLinkedinUrl());
        return supervisorProfileRepository.save(profile);
    }

    @Transactional
    public SupervisorProfile uploadSupervisorProfilePhoto(UUID userId, MultipartFile file) {
        User user = getById(userId);
        if (user.getRole() != UserRole.PARTY_SUPERVISOR && user.getRole() != UserRole.PROJECT_SUPERVISOR)
            throw new ForbiddenException("Only supervisors can upload a profile photo");
        SupervisorProfile profile = supervisorProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new BadRequestException("Complete supervisor onboarding before uploading a profile photo"));
        profile.setProfilePhotoUrl(fileStorageService.upload(file, "supervisor-profiles/" + userId));
        return supervisorProfileRepository.save(profile);
    }

    @Transactional
    public UserResponse approveUser(UUID userId, UUID coordinatorId) {
        User user = getByIdForUpdate(userId);
        ensureModeratable(user);
        if (user.getStatus() != UserStatus.PENDING && user.getStatus() != UserStatus.REJECTED)
            throw new BadRequestException("Only pending or rejected users can be approved");
        user.setStatus(UserStatus.APPROVED);
        user.setRejectionReason(null);
        user = userRepository.save(user);
        notificationService.notifyUserApproved(user);
        return toCoordinatorResponse(user);
    }

    @Transactional
    public UserResponse rejectUser(UUID userId, String reason, UUID coordinatorId) {
        User user = getByIdForUpdate(userId);
        ensureModeratable(user);
        if (user.getStatus() != UserStatus.PENDING && user.getStatus() != UserStatus.REJECTED)
            throw new BadRequestException("Only pending or rejected users can be rejected");
        if (user.getStatus() != UserStatus.REJECTED)
            recordModerationEvent(user, ModerationEventType.REJECTED, coordinatorId);
        user.setStatus(UserStatus.REJECTED);
        user.setRejectionReason(reason);
        user = userRepository.save(user);
        notificationService.notifyUserRejected(user);
        return toCoordinatorResponse(user);
    }

    @Transactional
    public UserResponse suspendUser(UUID userId, UUID coordinatorId) {
        User user = getByIdForUpdate(userId);
        ensureModeratable(user);
        if (user.getStatus() != UserStatus.APPROVED && user.getStatus() != UserStatus.SUSPENDED)
            throw new BadRequestException("Only approved users can be suspended");
        if (user.getStatus() != UserStatus.SUSPENDED)
            recordModerationEvent(user, ModerationEventType.SUSPENDED, coordinatorId);
        user.setStatus(UserStatus.SUSPENDED);
        return toCoordinatorResponse(userRepository.save(user));
    }

    @Transactional
    public UserResponse unsuspendUser(UUID userId) {
        User user = getByIdForUpdate(userId);
        ensureModeratable(user);
        if (user.getStatus() != UserStatus.SUSPENDED)
            throw new BadRequestException("Only suspended users can be unsuspended");
        user.setStatus(UserStatus.APPROVED);
        return toCoordinatorResponse(userRepository.save(user));
    }

    @Transactional
    public void deleteRejectedUser(UUID userId) {
        User user = getByIdForUpdate(userId);
        ensureModeratable(user);
        if (user.getStatus() != UserStatus.REJECTED)
            throw new BadRequestException("Only rejected users can be deleted");
        scorecardRepository.findByStudentId(userId).ifPresent(scorecardRepository::delete);
        userRepository.delete(user);
    }

    private void ensureModeratable(User user) {
        if (user.getRole() == UserRole.COORDINATOR)
            throw new BadRequestException("Coordinator accounts cannot be moderated");
    }

    public PageResponse<UserResponse> getPendingUsers(int page, int size) {
        // Uses Page<User> findByStatus(UserStatus, Pageable) from UserRepository
        Page<User> result = userRepository.findByStatus(
                UserStatus.PENDING,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return PageResponse.<UserResponse>builder()
                .content(result.getContent().stream().map(this::toResponse).toList())
                .totalPages(result.getTotalPages())
                .totalElements(result.getTotalElements())
                .page(result.getNumber())
                .size(result.getSize())
                .first(result.isFirst())
                .last(result.isLast())
                .build();
    }

    public List<UserResponse> getByRole(UserRole role) {
        return userRepository.findByRole(role).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<UserResponse> getSupervisors(UUID viewerId) {
        User viewer = getById(viewerId);
        List<User> supervisors = viewer.getRole() == UserRole.COORDINATOR
                ? userRepository.findByRoleIn(List.of(UserRole.PARTY_SUPERVISOR, UserRole.PROJECT_SUPERVISOR))
                : userRepository.findByRoleInAndStatus(
                        List.of(UserRole.PARTY_SUPERVISOR, UserRole.PROJECT_SUPERVISOR),
                        UserStatus.APPROVED);
        return supervisors
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<UserResponse> getCoordinators() {
        return userRepository.findByRole(UserRole.COORDINATOR).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public void broadcastNotification(UUID coordinatorId, String title, String message, UserRole role) {
        getById(coordinatorId);
        if (title == null || title.isBlank()) throw new BadRequestException("Broadcast title is required");
        if (message == null || message.isBlank()) throw new BadRequestException("Broadcast message is required");

        List<User> recipients = role != null
                ? userRepository.findByRole(role)
                : userRepository.findAll();

        recipients.stream()
                .filter(u -> u.getStatus() == UserStatus.APPROVED || u.getStatus() == UserStatus.SUSPENDED)
                .forEach(u -> notificationService.send(
                        u,
                        NotificationType.GENERAL,
                        title.trim(),
                        message.trim(),
                        coordinatorId.toString(),
                        "BROADCAST"));
    }

    @Transactional
    public User linkGitHub(UUID userId, String githubUsername, String accessToken) {
        User user = getById(userId);
        user.setGithubUsername(githubUsername);
        user.setGithubAccessToken(accessToken);
        return userRepository.save(user);
    }

    public UserResponse toResponse(User user) {
        StudentProfile student = user.getStudentProfile();
        CompanyProfile company = user.getCompanyProfile();
        SupervisorProfile supervisor = user.getSupervisorProfile();

        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRole())
                .status(user.getStatus())
                .phoneNumber(user.getPhoneNumber())
                .githubUsername(user.getGithubUsername())
                .rejectionReason(user.getRejectionReason())
                .onboardingComplete(user.getRole() != UserRole.STUDENT ? user.getRole() != UserRole.PARTY_SUPERVISOR || supervisor != null : student != null)
                .createdAt(user.getCreatedAt())
                .studentProfile(student == null ? null : UserResponse.StudentProfileDto.builder()
                        .id(student.getId())
                        .age(student.getAge())
                        .university(student.getUniversity())
                        .yearOfStudy(student.getYearOfStudy())
                        .major(student.getMajor())
                        .skills(student.getSkills())
                        .pastExperience(student.getPastExperience())
                        .bio(student.getBio())
                        .linkedinUrl(student.getLinkedinUrl())
                        .portfolioUrl(student.getPortfolioUrl())
                        .gpa(student.getGpa())
                        .build())
                .companyProfile(company == null ? null : UserResponse.CompanyProfileDto.builder()
                        .id(company.getId())
                        .companyName(company.getCompanyName())
                        .industry(company.getIndustry())
                        .description(company.getDescription())
                        .website(company.getWebsite())
                        .logoUrl(company.getLogoUrl())
                        .country(company.getCountry())
                        .city(company.getCity())
                        .build())
                .supervisorProfile(supervisor == null ? null : SupervisorProfileResponse.builder()
                        .id(supervisor.getId()).jobTitle(supervisor.getJobTitle()).department(supervisor.getDepartment())
                        .bio(supervisor.getBio()).linkedinUrl(supervisor.getLinkedinUrl()).profilePhotoUrl(supervisor.getProfilePhotoUrl()).build())
                .build();
    }

    private UserResponse toCoordinatorResponse(User user) {
        UserResponse response = toResponse(user);
        String email = normalizeEmail(user.getEmail());
        response.setSuspensionCount(moderationEventRepository
                .countByNormalizedEmailAndEventType(email, ModerationEventType.SUSPENDED));
        response.setRejectionCount(moderationEventRepository
                .countByNormalizedEmailAndEventType(email, ModerationEventType.REJECTED));
        return response;
    }

    private void recordModerationEvent(User user, ModerationEventType type, UUID coordinatorId) {
        moderationEventRepository.save(UserModerationEvent.builder()
                .normalizedEmail(normalizeEmail(user.getEmail()))
                .eventType(type)
                .coordinatorId(coordinatorId)
                .build());
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private User getByIdForUpdate(UUID id) {
        return userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));
    }
}
