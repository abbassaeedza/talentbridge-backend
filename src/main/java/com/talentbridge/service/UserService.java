package com.talentbridge.service;

import com.talentbridge.dto.request.StudentOnboardingRequest;
import com.talentbridge.dto.response.PageResponse;
import com.talentbridge.entity.*;
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
    public User approveUser(UUID userId, UUID coordinatorId) {
        User user = getById(userId);
        user.setStatus(UserStatus.APPROVED);
        user.setRejectionReason(null);
        user = userRepository.save(user);
        notificationService.notifyUserApproved(user);
        return user;
    }

    @Transactional
    public User rejectUser(UUID userId, String reason) {
        User user = getById(userId);
        user.setStatus(UserStatus.REJECTED);
        user.setRejectionReason(reason);
        user = userRepository.save(user);
        notificationService.notifyUserRejected(user);
        return user;
    }

    @Transactional
    public User suspendUser(UUID userId) {
        User user = getById(userId);
        user.setStatus(UserStatus.SUSPENDED);
        return userRepository.save(user);
    }

    @Transactional
    public User unsuspendUser(UUID userId) {
        User user = getById(userId);
        user.setStatus(UserStatus.APPROVED);
        return userRepository.save(user);
    }

    public PageResponse<User> getPendingUsers(int page, int size) {
        // Uses Page<User> findByStatus(UserStatus, Pageable) from UserRepository
        Page<User> result = userRepository.findByStatus(
                UserStatus.PENDING,
                PageRequest.of(page, size, Sort.by("createdAt").descending()));
        return PageResponse.<User>builder()
                .content(result.getContent())
                .totalPages(result.getTotalPages())
                .totalElements(result.getTotalElements())
                .page(result.getNumber())
                .size(result.getSize())
                .first(result.isFirst())
                .last(result.isLast())
                .build();
    }

    @Transactional
    public User linkGitHub(UUID userId, String githubUsername, String accessToken) {
        User user = getById(userId);
        user.setGithubUsername(githubUsername);
        user.setGithubAccessToken(accessToken);
        return userRepository.save(user);
    }
}