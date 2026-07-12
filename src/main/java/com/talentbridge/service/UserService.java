package com.talentbridge.service;

import com.talentbridge.dto.request.StudentOnboardingRequest;
import com.talentbridge.dto.response.PageResponse;
import com.talentbridge.dto.response.UserResponse;
import com.talentbridge.entity.*;
import com.talentbridge.enums.NotificationType;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.exception.*;
import com.talentbridge.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final StudentProfileRepository studentProfileRepository;
    private final NotificationService notificationService;

    public User getById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));
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
    public UserResponse approveUser(UUID userId, UUID coordinatorId) {
        User user = getById(userId);
        user.setStatus(UserStatus.APPROVED);
        user.setRejectionReason(null);
        user = userRepository.save(user);
        notificationService.notifyUserApproved(user);
        return toResponse(user);
    }

    @Transactional
    public UserResponse rejectUser(UUID userId, String reason) {
        User user = getById(userId);
        user.setStatus(UserStatus.REJECTED);
        user.setRejectionReason(reason);
        user = userRepository.save(user);
        notificationService.notifyUserRejected(user);
        return toResponse(user);
    }

    @Transactional
    public UserResponse suspendUser(UUID userId) {
        User user = getById(userId);
        user.setStatus(UserStatus.SUSPENDED);
        return toResponse(userRepository.save(user));
    }

    @Transactional
    public UserResponse unsuspendUser(UUID userId) {
        User user = getById(userId);
        user.setStatus(UserStatus.APPROVED);
        return toResponse(userRepository.save(user));
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

    public List<UserResponse> getSupervisors() {
        return userRepository.findByRoleIn(List.of(UserRole.PARTY_SUPERVISOR, UserRole.PROJECT_SUPERVISOR))
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
                .onboardingComplete(user.getRole() != UserRole.STUDENT || student != null)
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
                .build();
    }
}
