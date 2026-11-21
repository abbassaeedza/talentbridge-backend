package com.talentbridge.controller;
import com.talentbridge.dto.response.EvaluationReportResponse;
import com.talentbridge.service.EvaluationService;
import com.talentbridge.service.QuestionnaireEvaluationService;
import org.springframework.http.CacheControl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/evaluations") @RequiredArgsConstructor
public class EvaluationController {
    private final EvaluationService evaluationService;
    private final QuestionnaireEvaluationService questionnaire;

    @PostMapping("/trigger/{submissionId}")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<?> trigger(@PathVariable UUID submissionId,
                                            @AuthenticationPrincipal UUID coordinatorId) {
        if(questionnaire.versionTwoSubmission(submissionId)) return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(questionnaire.start(submissionId,coordinatorId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(evaluationService.triggerEvaluation(submissionId, coordinatorId));
    }

    @PutMapping("/{reportId}/finalize")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<EvaluationReportResponse> finalize(@PathVariable UUID reportId,
                                             @AuthenticationPrincipal UUID coordinatorId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(questionnaire.versionTwoReport(reportId)?questionnaire.finalizeReport(reportId,coordinatorId):evaluationService.finalizeReport(reportId, coordinatorId));
    }

    @PostMapping("/{reportId}/reevaluate")
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<?> reevaluate(@PathVariable UUID reportId,
                                               @AuthenticationPrincipal UUID coordinatorId) {
        if(questionnaire.versionTwoReport(reportId)) return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(questionnaire.reevaluate(reportId,coordinatorId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(evaluationService.reevaluate(reportId, coordinatorId));
    }

    @GetMapping("/submission/{submissionId}")
    public ResponseEntity<?> getBySubmission(@PathVariable UUID submissionId,
                                             @AuthenticationPrincipal UUID viewerId) {
        return (questionnaire.versionTwoSubmission(submissionId)?questionnaire.report(submissionId,viewerId):evaluationService.getBySubmissionId(submissionId, viewerId))
            .map(r->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r))
            .orElse(ResponseEntity.notFound().build());
    }
}
