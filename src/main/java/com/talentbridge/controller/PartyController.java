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

    @PostMapping
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<PartyResponse> create(
            @AuthenticationPrincipal UUID userId,
            @Valid @RequestBody PartyRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(partyService.create(userId, req));
    }

    @PostMapping("/{partyId}/join")
    @PreAuthorize("hasRole('STUDENT')")
    public PartyResponse join(
            @PathVariable UUID partyId,
            @AuthenticationPrincipal UUID userId) {
        return partyService.join(partyId, userId);
    }

    @DeleteMapping("/{partyId}/leave")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Void> leave(
            @PathVariable UUID partyId,
            @AuthenticationPrincipal UUID userId) {
        partyService.leave(partyId, userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('STUDENT')")
    public PartyResponse getMyParty(@AuthenticationPrincipal UUID userId) {
        return partyService.getMyParty(userId);
    }

    @GetMapping("/rules")
    public Map<String, Integer> getRules() {
        return partyService.getRules();
    }

    @GetMapping("/{partyId}")
    public PartyResponse getById(@PathVariable UUID partyId, @AuthenticationPrincipal UUID viewerId) {
        return partyService.getById(partyId, viewerId);
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('COORDINATOR')")
    public List<PartyResponse> getAll() {
        return partyService.getAll();
    }

    @GetMapping("/browse")
    @PreAuthorize("hasRole('PARTY_SUPERVISOR')")
    public List<PartyResponse> browse() {
        return partyService.getAll();
    }

    @GetMapping("/supervised")
    @PreAuthorize("hasAnyRole('PARTY_SUPERVISOR','PROJECT_SUPERVISOR')")
    public List<PartyResponse> getSupervised(@AuthenticationPrincipal UUID userId) {
        return partyService.getSupervised(userId);
    }

    @PostMapping("/{partyId}/apply")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<ApplicationResponse> apply(
            @PathVariable UUID partyId,
            @AuthenticationPrincipal UUID leaderId,
            @Valid @RequestBody ApplicationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(partyService.applyToProject(partyId, leaderId, req));
    }

    @GetMapping("/{partyId}/applications")
    public List<ApplicationResponse> getApplications(
            @PathVariable UUID partyId,
            @AuthenticationPrincipal UUID viewerId) {
        return partyService.getApplicationsByParty(partyId, viewerId);
    }

    @GetMapping("/projects/{projectId}/applications")
    @PreAuthorize("hasAnyRole('COORDINATOR','COMPANY','PROJECT_SUPERVISOR')")
    public List<ApplicationResponse> getApplicationsByProject(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal UUID viewerId) {
        return partyService.getApplicationsByProject(projectId, viewerId);
    }

    @PutMapping("/{partyId}/leader")
    @PreAuthorize("hasRole('STUDENT')")
    public PartyResponse changeLeader(
            @PathVariable UUID partyId,
            @AuthenticationPrincipal UUID currentLeaderId,
            @RequestParam UUID newLeaderId) {
        return partyService.changeLeader(partyId, currentLeaderId, newLeaderId);
    }

    @PutMapping("/{partyId}/supervisor")
    @PreAuthorize("hasRole('COORDINATOR')")
    public PartyResponse assignSupervisor(
            @PathVariable UUID partyId,
            @RequestParam(required = false) UUID supervisorId,
            @RequestBody(required = false) AssignSupervisorRequest req) {
        UUID resolvedSupervisorId = supervisorId != null ? supervisorId : (req != null ? req.getSupervisorId() : null);
        return partyService.assignSupervisor(partyId, resolvedSupervisorId);
    }

    @PutMapping("/{partyId}/claim-supervisor")
    @PreAuthorize("hasRole('PARTY_SUPERVISOR')")
    public PartyResponse claimSupervisor(
            @PathVariable UUID partyId,
            @AuthenticationPrincipal UUID supervisorId) {
        return partyService.claimSupervisor(partyId, supervisorId);
    }

    @PutMapping("/{partyId}/name")
    @PreAuthorize("hasRole('COORDINATOR')")
    public PartyResponse rename(
            @PathVariable UUID partyId,
            @RequestBody Map<String, String> req) {
        return partyService.renameParty(partyId, req.get("name"));
    }

    @DeleteMapping("/{partyId}/members/{userId}")
    @PreAuthorize("hasRole('COORDINATOR')")
    public PartyResponse removeMember(
            @PathVariable UUID partyId,
            @PathVariable UUID userId) {
        return partyService.removeMember(partyId, userId);
    }

    @DeleteMapping("/{partyId}/project")
    @PreAuthorize("hasRole('COORDINATOR')")
    public PartyResponse unassignProject(@PathVariable UUID partyId) {
        return partyService.unassignProject(partyId);
    }
}
