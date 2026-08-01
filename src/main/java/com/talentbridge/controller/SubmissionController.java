package com.talentbridge.controller;
import com.talentbridge.entity.Submission;
import com.talentbridge.service.SubmissionService;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.validation.annotation.Validated;
import java.util.List;
import java.util.UUID;

@RestController @RequestMapping("/api/submissions") @RequiredArgsConstructor @Validated
public class SubmissionController {
    private final SubmissionService submissionService;

    @PostMapping("/{partyId}/draft")
    @PreAuthorize("hasRole('STUDENT')")
    public Submission saveDraft(
            @PathVariable UUID partyId, @AuthenticationPrincipal UUID leaderId,
            @RequestParam(required=false) @Size(max = 500) String repoUrl,
            @RequestParam(required=false) @Size(max = 100) String branch,
            @RequestParam(required=false) List<MultipartFile> documents,
            @RequestParam(required=false) @Size(max = 2000) String notes) {
        return submissionService.saveDraft(partyId, leaderId, repoUrl, branch, documents, notes);
    }

    @PostMapping("/{partyId}/submit")
    @PreAuthorize("hasRole('STUDENT')")
    public Submission finalSubmit(@PathVariable UUID partyId,
                                  @AuthenticationPrincipal UUID leaderId) {
        return submissionService.finalSubmit(partyId, leaderId);
    }

    @GetMapping("/{partyId}")
    public Submission getByParty(
            @PathVariable UUID partyId,
            @AuthenticationPrincipal UUID viewerId) {
        return submissionService.getByPartyId(partyId, viewerId);
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("hasAnyRole('COORDINATOR','COMPANY','PROJECT_SUPERVISOR','PARTY_SUPERVISOR')")
    public List<Submission> getByProject(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal UUID viewerId) {
        return submissionService.getByProjectId(projectId, viewerId);
    }
}
