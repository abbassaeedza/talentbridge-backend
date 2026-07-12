package com.talentbridge.controller;

import com.talentbridge.dto.response.CoordinatorAnalyticsResponse;
import com.talentbridge.service.CoordinatorAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/coordinator/analytics")
@RequiredArgsConstructor
public class CoordinatorAnalyticsController {
    private final CoordinatorAnalyticsService analyticsService;

    @GetMapping
    @PreAuthorize("hasRole('COORDINATOR')")
    public ResponseEntity<CoordinatorAnalyticsResponse> getAnalytics() {
        return ResponseEntity.ok(analyticsService.getAnalytics());
    }
}
