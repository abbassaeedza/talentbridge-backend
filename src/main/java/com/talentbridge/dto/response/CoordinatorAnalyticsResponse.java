package com.talentbridge.dto.response;

import lombok.*;

import java.util.Map;

@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class CoordinatorAnalyticsResponse {
    private Map<String, Long> projectsByStatus;
    private Map<String, Long> usersByRole;
    private Map<String, Long> usersByStatus;
    private Map<String, Long> submissionsByStatus;
    private long totalParties;
    private long assignedParties;
    private long unassignedParties;
    private long finalizedEvaluations;
    private long draftEvaluations;
}
