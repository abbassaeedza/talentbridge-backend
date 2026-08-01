package com.talentbridge.controller;
import com.talentbridge.entity.EvaluationReport;
import com.talentbridge.service.EvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/evaluations") @RequiredArgsConstructor
public class EvaluationController {
    private final EvaluationService evaluationService;

    @PostMapping("/trigger/{submissionId}")
    @PreAuthorize("hasRole('COORDINATOR')")
    public EvaluationReport trigger(@PathVariable UUID submissionId,
                                    @AuthenticationPrincipal UUID coordinatorId) {
        return evaluationService.triggerEvaluation(submissionId, coordinatorId);
    }

    @PutMapping("/{reportId}/finalize")
    @PreAuthorize("hasRole('COORDINATOR')")
    public EvaluationReport finalize(@PathVariable UUID reportId,
                                     @AuthenticationPrincipal UUID coordinatorId) {
        return evaluationService.finalizeReport(reportId, coordinatorId);
    }

    @GetMapping("/submission/{submissionId}")
    public ResponseEntity<?> getBySubmission(@PathVariable UUID submissionId,
                                             @AuthenticationPrincipal UUID viewerId) {
        return evaluationService.getBySubmissionId(submissionId, viewerId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
