package com.talentbridge.service;

import com.talentbridge.dto.response.CoordinatorAnalyticsResponse;
import com.talentbridge.entity.EvaluationReport;
import com.talentbridge.entity.Party;
import com.talentbridge.entity.Project;
import com.talentbridge.entity.Submission;
import com.talentbridge.enums.*;
import com.talentbridge.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
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
        var parties = partyRepository.findAll();
        var evaluations = evaluationReportRepository.findAll();
        long assignedParties = parties.stream().filter(p -> p.getAssignedProject() != null).count();
        long finalized = evaluations.stream().filter(EvaluationReport::isFinalized).count();

        return CoordinatorAnalyticsResponse.builder()
                .projectsByStatus(countEnum(ProjectStatus.values(), projectRepository.findAll().stream()
                        .map(Project::getStatus)
                        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))))
                .usersByRole(countEnum(UserRole.values(), userRepository.findAll().stream()
                        .map(u -> u.getRole())
                        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))))
                .usersByStatus(countEnum(UserStatus.values(), userRepository.findAll().stream()
                        .map(u -> u.getStatus())
                        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))))
                .submissionsByStatus(countEnum(SubmissionStatus.values(), submissionRepository.findAll().stream()
                        .map(Submission::getStatus)
                        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))))
                .totalParties(parties.size())
                .assignedParties(assignedParties)
                .unassignedParties(parties.size() - assignedParties)
                .finalizedEvaluations(finalized)
                .draftEvaluations(evaluations.size() - finalized)
                .build();
    }

    private <E extends Enum<E>> Map<String, Long> countEnum(E[] values, Map<E, Long> counts) {
        return Arrays.stream(values)
                .collect(Collectors.toMap(Enum::name, e -> counts.getOrDefault(e, 0L), (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
