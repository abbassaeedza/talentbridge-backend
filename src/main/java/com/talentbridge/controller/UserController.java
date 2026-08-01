package com.talentbridge.controller;

import com.talentbridge.dto.request.StudentOnboardingRequest;
import com.talentbridge.dto.response.PageResponse;
import com.talentbridge.dto.response.UserResponse;
import com.talentbridge.entity.*;
import com.talentbridge.enums.UserRole;
import com.talentbridge.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final NotificationService notificationService;
    private final ScorecardService scorecardService;
    private final GitHubService gitHubService;

    @GetMapping("/me")
    public UserResponse getMe(@AuthenticationPrincipal UUID userId) {
        return userService.toResponse(userService.getById(userId));
    }

    @PostMapping("/onboarding")
    @PreAuthorize("hasRole('STUDENT')")
    public StudentProfile completeOnboarding(
            @AuthenticationPrincipal UUID userId,
            @RequestBody StudentOnboardingRequest req) {
        return userService.completeOnboarding(userId, req);
    }

    @GetMapping("/my/scorecard")
    @PreAuthorize("hasRole('STUDENT')")
    public Scorecard getMyScorecard(@AuthenticationPrincipal UUID userId) {
        return scorecardService.getByStudentId(userId);
    }

    @GetMapping("/{userId}/scorecard")
    public Scorecard getScorecard(@PathVariable UUID userId) {
        return scorecardService.getByStudentId(userId);
    }

    @GetMapping("/{userId}")
    @PreAuthorize("hasAnyRole('COORDINATOR','COMPANY','PARTY_SUPERVISOR','PROJECT_SUPERVISOR')")
    public UserResponse getStudentProfile(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UUID viewerId) {
        return userService.getStudentProfile(userId, viewerId);
    }

    @GetMapping("/pending")
    @PreAuthorize("hasRole('COORDINATOR')")
    public PageResponse<UserResponse> getPending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return userService.getPendingUsers(page, size);
    }

    @PutMapping("/{userId}/approve")
    @PreAuthorize("hasRole('COORDINATOR')")
    public UserResponse approve(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UUID coordinatorId) {
        return userService.approveUser(userId, coordinatorId);
    }

    @PutMapping("/{userId}/reject")
    @PreAuthorize("hasRole('COORDINATOR')")
    public UserResponse reject(
            @PathVariable UUID userId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UUID coordinatorId) {
        return userService.rejectUser(userId, reason, coordinatorId);
    }

    @PutMapping("/{userId}/suspend")
    @PreAuthorize("hasRole('COORDINATOR')")
    public UserResponse suspend(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UUID coordinatorId) {
        return userService.suspendUser(userId, coordinatorId);
    }

    @PutMapping("/{userId}/unsuspend")
    @PreAuthorize("hasRole('COORDINATOR')")
    public UserResponse unsuspend(@PathVariable UUID userId) {
        return userService.unsuspendUser(userId);
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<Void> deleteRejected(@PathVariable UUID userId) {
        userService.deleteRejectedUser(userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/students")
    @PreAuthorize("hasRole('COORDINATOR')")
    public java.util.List<UserResponse> getStudents() {
        return userService.getByRole(UserRole.STUDENT);
    }

    @GetMapping("/companies")
    @PreAuthorize("hasRole('COORDINATOR')")
    public java.util.List<UserResponse> getCompanies() {
        return userService.getByRole(UserRole.COMPANY);
    }

    @GetMapping("/supervisors")
    @PreAuthorize("hasAnyRole('COORDINATOR','STUDENT')")
    public java.util.List<UserResponse> getSupervisors(@AuthenticationPrincipal UUID viewerId) {
        return userService.getSupervisors(viewerId);
    }

    @GetMapping("/coordinators")
    @PreAuthorize("hasRole('COORDINATOR')")
    public java.util.List<UserResponse> getCoordinators() {
        return userService.getCoordinators();
    }

    @PostMapping("/broadcast")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<Void> broadcast(
            @AuthenticationPrincipal UUID coordinatorId,
            @RequestBody Map<String, String> req) {
        UserRole role = null;
        if (req.get("role") != null && !req.get("role").isBlank()) {
            role = UserRole.valueOf(req.get("role"));
        }
        userService.broadcastNotification(coordinatorId, req.get("title"), req.get("message"), role);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/github/callback")
    @PreAuthorize("hasRole('STUDENT')")
    public Map<String, String> githubCallback(
            @AuthenticationPrincipal UUID userId,
            @RequestParam String code) {
        Map<String, String> tokenData = gitHubService.exchangeCodeForToken(code);
        String accessToken = tokenData.get("access_token");
        String username = gitHubService.getGitHubUsername(accessToken);
        userService.linkGitHub(userId, username, accessToken);
        return Map.of(
            "message", "GitHub linked successfully",
            "username", username != null ? username : "");
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    @GetMapping("/notifications")
    public Map<String, Object> getNotifications(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page) {
        return Map.of(
            "notifications", notificationService.getForUser(userId, page),
            "unreadCount", notificationService.countUnread(userId));
    }

    @PutMapping("/notifications/{id}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId) {
        notificationService.markAsRead(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/notifications/read-all")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal UUID userId) {
        notificationService.markAllAsRead(userId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/notifications/clear")
    public ResponseEntity<Void> clearNotifications(@AuthenticationPrincipal UUID userId) {
        notificationService.clearAll(userId);
        return ResponseEntity.noContent().build();
    }
}
