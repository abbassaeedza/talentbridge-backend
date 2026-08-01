package com.talentbridge.controller;

import com.talentbridge.dto.request.*;
import com.talentbridge.dto.response.*;
import com.talentbridge.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService projectService;

    @PostMapping
    @PreAuthorize("hasAnyRole('COMPANY','COORDINATOR')")
    public ResponseEntity<ProjectResponse> create(@AuthenticationPrincipal UUID userId,
                                                  @Valid @RequestBody ProjectRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projectService.create(userId, req));
    }

    @GetMapping
    public PageResponse<ProjectResponse> getOpen(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return projectService.getOpen(search, sortBy, page, size);
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('COORDINATOR')")
    public PageResponse<ProjectResponse> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return projectService.getAll(page, size, status);
    }

    @GetMapping("/global-deadline")
    public ResponseEntity<LocalDateTime> getGlobalDeadline() {
        return projectService.getGlobalDeadline()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public ProjectResponse getById(@PathVariable UUID id) {
        return projectService.getById(id);
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('COMPANY')")
    public List<ProjectResponse> getMyProjects(@AuthenticationPrincipal UUID userId) {
        return projectService.getByCreator(userId);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('COMPANY')")
    public ProjectResponse update(@PathVariable UUID id,
                                  @AuthenticationPrincipal UUID userId,
                                  @Valid @RequestBody ProjectRequest req) {
        return projectService.update(id, userId, req);
    }

    @PatchMapping("/{id}/internal-name")
    @PreAuthorize("hasRole('COMPANY')")
    public ProjectResponse patchInternalName(
            @PathVariable UUID id,
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) String name) {
        return projectService.patchInternalName(id, userId, name);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('COMPANY')")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal UUID userId) {
        projectService.delete(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/approve")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ProjectResponse approve(@PathVariable UUID id,
                                   @AuthenticationPrincipal UUID coordinatorId) {
        return projectService.approve(id, coordinatorId);
    }

    @PutMapping("/{id}/disapprove")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ProjectResponse disapprove(@PathVariable UUID id,
                                      @AuthenticationPrincipal UUID coordinatorId) {
        return projectService.disapprove(id, coordinatorId);
    }

    @PutMapping("/{id}/retract")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ProjectResponse retract(@PathVariable UUID id,
                                   @AuthenticationPrincipal UUID coordinatorId) {
        return projectService.retract(id, coordinatorId);
    }

    @PutMapping("/{id}/deadline")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ProjectResponse setDeadline(
            @PathVariable UUID id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime deadline) {
        return projectService.setDeadline(id, deadline);
    }

    @PutMapping("/global-deadline")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<Void> setGlobalDeadline(@Valid @RequestBody GlobalDeadlineRequest req) {
        projectService.setGlobalDeadline(req.getDeadline());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{projectId}/assign/{partyId}")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ProjectResponse assign(@PathVariable UUID projectId,
                                  @PathVariable UUID partyId,
                                  @AuthenticationPrincipal UUID coordinatorId) {
        return projectService.assignToParty(projectId, partyId, coordinatorId);
    }
}
