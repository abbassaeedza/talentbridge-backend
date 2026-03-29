package com.talentbridge.controller;
import com.talentbridge.entity.Submission;
import com.talentbridge.service.SubmissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.UUID;

@RestController @RequestMapping("/api/submissions") @RequiredArgsConstructor
public class SubmissionController {
    private final SubmissionService submissionService;

    @PostMapping("/{partyId}/draft")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Submission> saveDraft(
            @PathVariable UUID partyId, @AuthenticationPrincipal UUID leaderId,
            @RequestParam(required=false) String repoUrl,
            @RequestParam(required=false) String branch,
            @RequestParam(required=false) List<MultipartFile> documents,
            @RequestParam(required=false) String notes) {
        return ResponseEntity.ok(submissionService.saveDraft(partyId, leaderId, repoUrl, branch, documents, notes));
    }

    @PostMapping("/{partyId}/submit")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Submission> finalSubmit(@PathVariable UUID partyId,
                                                   @AuthenticationPrincipal UUID leaderId) {
        return ResponseEntity.ok(submissionService.finalSubmit(partyId, leaderId));
    }

    @GetMapping("/{partyId}")
    public ResponseEntity<Submission> getByParty(@PathVariable UUID partyId) {
        return ResponseEntity.ok(submissionService.getByPartyId(partyId));
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("hasAnyRole('COORDINATOR','PROJECT_SUPERVISOR','PARTY_SUPERVISOR')")
    public ResponseEntity<List<Submission>> getByProject(@PathVariable UUID projectId) {
        return ResponseEntity.ok(submissionService.getByProjectId(projectId));
    }
}
