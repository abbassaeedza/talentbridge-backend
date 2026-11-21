package com.talentbridge.controller;
import com.talentbridge.dto.response.SubmissionResponse;
import com.talentbridge.service.SubmissionService;
import com.talentbridge.service.QuestionnaireFinalSubmissionService;
import com.talentbridge.service.QuestionnaireGenerationService;
import com.talentbridge.dto.request.FinalSubmissionRequest;
import org.springframework.http.ResponseEntity;
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
    private final QuestionnaireFinalSubmissionService finalization;
    private final QuestionnaireGenerationService generation;

    @PostMapping("/{partyId}/draft")
    @PreAuthorize("hasRole('STUDENT')")
    public SubmissionResponse saveDraft(
            @PathVariable UUID partyId, @AuthenticationPrincipal UUID leaderId,
            @RequestParam(required=false) @Size(max = 500) String repoUrl,
            @RequestParam(required=false) @Size(max = 100) String branch,
            @RequestParam(required=false) List<MultipartFile> documents,
            @RequestParam(required=false) @Size(max = 2000) String notes) {
        return submissionService.saveDraft(partyId, leaderId, repoUrl, branch, documents, notes);
    }

    @PostMapping("/{partyId}/submit")
    @PreAuthorize("hasRole('STUDENT')")
    public SubmissionResponse finalSubmit(@PathVariable UUID partyId,
                                  @AuthenticationPrincipal UUID leaderId,
                                  @RequestBody(required=false) FinalSubmissionRequest request) {
        return finalization.finalSubmit(partyId, leaderId, request==null?null:request.expectedVersion());
    }

    @PostMapping("/{submissionId}/questionnaire/retry")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<QuestionnaireGenerationService.Review> retry(@PathVariable UUID submissionId) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(generation.retry(submissionId));
    }

    @GetMapping("/{submissionId}/questionnaire-review")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<QuestionnaireGenerationService.Review> review(@PathVariable UUID submissionId) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(generation.review(submissionId));
    }

    @GetMapping("/{partyId}")
    public SubmissionResponse getByParty(
            @PathVariable UUID partyId,
            @AuthenticationPrincipal UUID viewerId) {
        return submissionService.getByPartyId(partyId, viewerId);
    }

    @GetMapping("/project/{projectId}")
    @PreAuthorize("hasAnyRole('COORDINATOR','COMPANY','PROJECT_SUPERVISOR','PARTY_SUPERVISOR')")
    public List<SubmissionResponse> getByProject(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal UUID viewerId) {
        return submissionService.getByProjectId(projectId, viewerId);
    }
}
