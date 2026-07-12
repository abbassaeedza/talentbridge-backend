package com.talentbridge.controller;

import com.talentbridge.dto.request.*;
import com.talentbridge.dto.response.*;
import com.talentbridge.service.PartyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/parties")
@RequiredArgsConstructor
public class PartyController {
    private final PartyService partyService;

    // create() ✓
    @PostMapping
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<PartyResponse> create(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody PartyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(partyService.create(userId, req));
    }

    // join() ✓
    @PostMapping("/{partyId}/join")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<PartyResponse> join(
            @PathVariable UUID partyId,
            @AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(partyService.join(partyId, userId));
    }

    // leave() — void in service, returns 204
    @DeleteMapping("/{partyId}/leave")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Void> leave(
            @PathVariable UUID partyId,
            @AuthenticationPrincipal UUID userId) {
        partyService.leave(partyId, userId);
        return ResponseEntity.noContent().build();
    }

    // getMyParty() ✓
    @GetMapping("/my")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<PartyResponse> getMyParty(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(partyService.getMyParty(userId));
    }

    // getById() ✓
    @GetMapping("/{partyId}")
    public ResponseEntity<PartyResponse> getById(@PathVariable UUID partyId) {
        return ResponseEntity.ok(partyService.getById(partyId));
    }

    // getAll() ✓
    @GetMapping("/all")
    @PreAuthorize("hasAnyRole('COORDINATOR','PARTY_SUPERVISOR','PROJECT_SUPERVISOR')")
    public ResponseEntity<List<PartyResponse>> getAll() {
        return ResponseEntity.ok(partyService.getAll());
    }

    // getSupervised() ✓
    @GetMapping("/supervised")
    @PreAuthorize("hasAnyRole('PARTY_SUPERVISOR','PROJECT_SUPERVISOR')")
    public ResponseEntity<List<PartyResponse>> getSupervised(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(partyService.getSupervised(userId));
    }

    // applyToProject() ✓
    @PostMapping("/{partyId}/apply")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<ApplicationResponse> apply(
            @PathVariable UUID partyId,
            @AuthenticationPrincipal UUID leaderId,
            @Valid @RequestBody ApplicationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(partyService.applyToProject(partyId, leaderId, req));
    }

    // getApplicationsByParty() ✓
    @GetMapping("/{partyId}/applications")
    public ResponseEntity<List<ApplicationResponse>> getApplications(@PathVariable UUID partyId) {
        return ResponseEntity.ok(partyService.getApplicationsByParty(partyId));
    }

    // getApplicationsByProject() ✓
    @GetMapping("/projects/{projectId}/applications")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<List<ApplicationResponse>> getApplicationsByProject(@PathVariable UUID projectId) {
        return ResponseEntity.ok(partyService.getApplicationsByProject(projectId));
    }

    // changeLeader() ✓
    @PutMapping("/{partyId}/leader")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<PartyResponse> changeLeader(
            @PathVariable UUID partyId,
            @AuthenticationPrincipal UUID currentLeaderId,
            @RequestParam UUID newLeaderId) {
        return ResponseEntity.ok(partyService.changeLeader(partyId, currentLeaderId, newLeaderId));
    }

    // assignSupervisor() ✓
    @PutMapping("/{partyId}/supervisor")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<PartyResponse> assignSupervisor(
            @PathVariable UUID partyId,
            @RequestParam(required = false) UUID supervisorId,
            @RequestBody(required = false) AssignSupervisorRequest req) {
        UUID resolvedSupervisorId = supervisorId != null ? supervisorId : (req != null ? req.getSupervisorId() : null);
        return ResponseEntity.ok(partyService.assignSupervisor(partyId, resolvedSupervisorId));
    }

    @PutMapping("/{partyId}/name")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<PartyResponse> rename(
            @PathVariable UUID partyId,
            @RequestBody Map<String, String> req) {
        return ResponseEntity.ok(partyService.renameParty(partyId, req.get("name")));
    }

    @DeleteMapping("/{partyId}/members/{userId}")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<PartyResponse> removeMember(
            @PathVariable UUID partyId,
            @PathVariable UUID userId) {
        return ResponseEntity.ok(partyService.removeMember(partyId, userId));
    }

    @DeleteMapping("/{partyId}/project")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<PartyResponse> unassignProject(@PathVariable UUID partyId) {
        return ResponseEntity.ok(partyService.unassignProject(partyId));
    }
}
