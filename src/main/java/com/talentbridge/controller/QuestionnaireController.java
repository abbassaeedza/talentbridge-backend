package com.talentbridge.controller;

import com.talentbridge.service.QuestionnaireService;
import com.talentbridge.dto.request.QuestionnaireAnswerRequest;
import com.talentbridge.dto.response.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController @RequestMapping("/api/questionnaires") @RequiredArgsConstructor
@PreAuthorize("hasRole('STUDENT')")
public class QuestionnaireController {
    private final QuestionnaireService service;

    @GetMapping("/my")
    public ResponseEntity<List<QuestionnaireSummaryResponse>> list(@AuthenticationPrincipal UUID studentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(studentId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<QuestionnaireResponse> get(@PathVariable UUID id, @AuthenticationPrincipal UUID studentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id, studentId));
    }

    @PutMapping("/{id}/answers")
    public ResponseEntity<QuestionnaireResponse> save(@PathVariable UUID id, @AuthenticationPrincipal UUID studentId,
            @Valid @RequestBody QuestionnaireAnswerRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.save(id, studentId, request));
    }
}
