package com.talentbridge.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class StartupController {
    private final ApplicationAvailability availability;

    @GetMapping("/api/health/startup")
    public ResponseEntity<Void> startup() {
        // Cloud Run must keep startup CPU until ApplicationRunner transactions finish.
        return ResponseEntity.status(availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC
                ? 200 : 503).build();
    }
}
