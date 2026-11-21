package com.talentbridge.controller;

import com.talentbridge.service.QuestionnaireEvaluationService;
import com.talentbridge.dto.response.EvaluationReportResponse;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor
public class QuestionnaireEvaluationController {
    private final QuestionnaireEvaluationService service;
    public record Adjustment(JsonNode mark,JsonNode reason){}

    @GetMapping("/api/submissions") @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<List<QuestionnaireEvaluationService.CoordinatorSubmission>> submissions() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.coordinatorSubmissions());
    }
    @GetMapping("/api/evaluations/runs/{id}") @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<QuestionnaireEvaluationService.RunView> run(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.run(id));
    }
    @PostMapping("/api/evaluations/runs/{id}/retry") @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<QuestionnaireEvaluationService.RunView> retry(@PathVariable UUID id) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.retry(id));
    }
    @GetMapping("/api/evaluations/submission/{id}/questionnaire") @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<QuestionnaireEvaluationService.PrivateReview> review(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.privateReview(id));
    }
    @PutMapping("/api/evaluations/submission/{id}/questions/{questionId}/mark") @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<QuestionnaireEvaluationService.PrivateReview> adjust(@PathVariable UUID id,@PathVariable UUID questionId,
            @AuthenticationPrincipal UUID actor,@RequestBody Adjustment request) {
        Integer mark=request.mark()!=null && request.mark().isIntegralNumber() && request.mark().canConvertToInt()?request.mark().intValue():null;
        String reason=request.reason()!=null && request.reason().isTextual()?request.reason().textValue():null;
        if(mark==null || mark<0 || mark>20 || reason==null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.adjust(id,questionId,actor,mark,reason));
    }
    @GetMapping("/api/questionnaires/{id}/report") @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<EvaluationReportResponse> ownerReport(@PathVariable UUID id,@AuthenticationPrincipal UUID student) {
        return service.ownerReport(id,student).map(r->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r))
                .orElse(ResponseEntity.notFound().build());
    }
}
