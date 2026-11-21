package com.talentbridge.controller;

import com.talentbridge.service.QuestionnaireGenerationService;
import com.talentbridge.service.QuestionnaireEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequiredArgsConstructor
public class AssessmentJobController {
    private final QuestionnaireGenerationService generation;
    private final QuestionnaireEvaluationService evaluation;
    public record JobReference(UUID jobId) {}

    @PostMapping("/internal/assessment-jobs/{id}")
    public ResponseEntity<Void> handle(@PathVariable UUID id, @RequestBody JobReference body) {
        if (body==null || !id.equals(body.jobId())) return ResponseEntity.badRequest().build();
        if(evaluation.handles(id)) evaluation.handle(id);
        else generation.handle(id);
        return ResponseEntity.noContent().build();
    }
}
