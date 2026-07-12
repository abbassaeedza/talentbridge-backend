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
    public ResponseEntity<UserResponse> getMe(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(userService.toResponse(userService.getById(userId)));
    }

    @PostMapping("/onboarding")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<StudentProfile> completeOnboarding(
            @AuthenticationPrincipal UUID userId,
            @RequestBody StudentOnboardingRequest req) {
        return ResponseEntity.ok(userService.completeOnboarding(userId, req));
    }

    @GetMapping("/my/scorecard")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Scorecard> getMyScorecard(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(scorecardService.getByStudentId(userId));
    }

    @GetMapping("/{userId}/scorecard")
    public ResponseEntity<Scorecard> getScorecard(@PathVariable UUID userId) {
        return ResponseEntity.ok(scorecardService.getByStudentId(userId));
    }

    @GetMapping("/pending")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<PageResponse<UserResponse>> getPending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(userService.getPendingUsers(page, size));
    }

    @PutMapping("/{userId}/approve")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<UserResponse> approve(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UUID coordinatorId) {
        return ResponseEntity.ok(userService.approveUser(userId, coordinatorId));
    }

    @PutMapping("/{userId}/reject")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<UserResponse> reject(
            @PathVariable UUID userId,
            @RequestParam(required = false) String reason,
            @AuthenticationPrincipal UUID coordinatorId) {
        return ResponseEntity.ok(userService.rejectUser(userId, reason));
    }

    @PutMapping("/{userId}/suspend")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<UserResponse> suspend(@PathVariable UUID userId) {
        return ResponseEntity.ok(userService.suspendUser(userId));
    }

    @PutMapping("/{userId}/unsuspend")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<UserResponse> unsuspend(@PathVariable UUID userId) {
        return ResponseEntity.ok(userService.unsuspendUser(userId));
    }

    @GetMapping("/students")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<java.util.List<UserResponse>> getStudents() {
        return ResponseEntity.ok(userService.getByRole(UserRole.STUDENT));
    }

    @GetMapping("/companies")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<java.util.List<UserResponse>> getCompanies() {
        return ResponseEntity.ok(userService.getByRole(UserRole.COMPANY));
    }

    @GetMapping("/supervisors")
    @PreAuthorize("hasAnyRole('COORDINATOR','STUDENT')")
    public ResponseEntity<java.util.List<UserResponse>> getSupervisors() {
        return ResponseEntity.ok(userService.getSupervisors());
    }

    @GetMapping("/coordinators")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<java.util.List<UserResponse>> getCoordinators() {
        return ResponseEntity.ok(userService.getCoordinators());
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
    public ResponseEntity<Map<String, String>> githubCallback(
            @AuthenticationPrincipal UUID userId,
            @RequestParam String code) {
        Map<String, String> tokenData = gitHubService.exchangeCodeForToken(code);
        String accessToken = tokenData.get("access_token");
        String username = gitHubService.getGitHubUsername(accessToken);
        userService.linkGitHub(userId, username, accessToken);
        return ResponseEntity.ok(Map.of(
            "message", "GitHub linked successfully",
            "username", username != null ? username : ""));
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    @GetMapping("/notifications")
    public ResponseEntity<?> getNotifications(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok(Map.of(
            "notifications", notificationService.getForUser(userId, page),
            "unreadCount",   notificationService.countUnread(userId)));
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
