package com.talentbridge.service;

import com.talentbridge.dto.response.CoordinatorAnalyticsResponse;
import com.talentbridge.enums.*;
import com.talentbridge.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CoordinatorAnalyticsService {
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final PartyRepository partyRepository;
    private final SubmissionRepository submissionRepository;
    private final EvaluationReportRepository evaluationReportRepository;

    public CoordinatorAnalyticsResponse getAnalytics() {
        long totalParties = partyRepository.count();
        long assignedParties = partyRepository.countByAssignedProjectIsNotNull();
        long totalEvaluations = evaluationReportRepository.count();
        long finalized = evaluationReportRepository.countByFinalizedTrue();

        return CoordinatorAnalyticsResponse.builder()
                .projectsByStatus(countEnum(ProjectStatus.values(), projectRepository.countByStatusGrouped()))
                .usersByRole(countEnum(UserRole.values(), userRepository.countByRoleGrouped()))
                .usersByStatus(countEnum(UserStatus.values(), userRepository.countByStatusGrouped()))
                .submissionsByStatus(countEnum(SubmissionStatus.values(), submissionRepository.countByStatusGrouped()))
                .totalParties(totalParties)
                .assignedParties(assignedParties)
                .unassignedParties(totalParties - assignedParties)
                .finalizedEvaluations(finalized)
                .draftEvaluations(totalEvaluations - finalized)
                .build();
    }

    private <E extends Enum<E>> Map<String, Long> countEnum(E[] values, java.util.List<Object[]> rows) {
        Map<E, Long> counts = rows.stream().collect(Collectors.toMap(row -> (E) row[0], row -> (Long) row[1]));
        return Arrays.stream(values)
                .collect(Collectors.toMap(Enum::name, e -> counts.getOrDefault(e, 0L), (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
