package com.talentbridge.controller;

import com.talentbridge.dto.request.*;
import com.talentbridge.dto.response.*;
import com.talentbridge.service.ProjectSupervisorInvitationService;
import com.talentbridge.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ProjectSupervisorInvitationController {
    private final ProjectSupervisorInvitationService invitationService;
    private final UserService userService;

    @GetMapping("/api/project-supervisor-invitations/{token}")
    public ProjectSupervisorInvitationResponse validate(@PathVariable String token) { return invitationService.validate(token); }

    @PostMapping("/api/project-supervisor-invitations/{token}/accept")
    public AuthResponse accept(@PathVariable String token, @Valid @RequestBody ProjectSupervisorInvitationAcceptRequest request) { return invitationService.accept(token, request); }

    @GetMapping("/api/company/project-supervisor-invitations")
    @PreAuthorize("hasRole('COMPANY')")
    public List<ProjectSupervisorInvitationResponse> list(@AuthenticationPrincipal UUID userId) { return invitationService.list(userId); }
    @PostMapping("/api/company/project-supervisor-invitations")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<ProjectSupervisorInvitationResponse> create(@AuthenticationPrincipal UUID userId, @Valid @RequestBody ProjectSupervisorInvitationRequest request) { return ResponseEntity.status(HttpStatus.CREATED).body(invitationService.create(userId, request)); }
    @PostMapping("/api/company/project-supervisor-invitations/{id}/resend")
    @PreAuthorize("hasRole('COMPANY')")
    public ProjectSupervisorInvitationResponse resend(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) { return invitationService.resend(userId, id); }
    @DeleteMapping("/api/company/project-supervisor-invitations/{id}")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) { invitationService.revoke(userId, id); return ResponseEntity.noContent().build(); }
    @GetMapping("/api/company/project-supervisors")
    @PreAuthorize("hasRole('COMPANY')")
    public List<UserResponse> supervisors(@AuthenticationPrincipal UUID userId) { return invitationService.listAcceptedSupervisors(userId, userService); }
}
