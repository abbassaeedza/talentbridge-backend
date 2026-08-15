package com.talentbridge.service;

import com.talentbridge.enums.ProjectStatus;
import com.talentbridge.enums.SubmissionStatus;
import com.talentbridge.enums.UserRole;
import com.talentbridge.enums.UserStatus;
import com.talentbridge.repository.EvaluationReportRepository;
import com.talentbridge.repository.PartyRepository;
import com.talentbridge.repository.ProjectRepository;
import com.talentbridge.repository.SubmissionRepository;
import com.talentbridge.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoordinatorAnalyticsServiceTest {
    @Mock ProjectRepository projectRepository;
    @Mock UserRepository userRepository;
    @Mock PartyRepository partyRepository;
    @Mock SubmissionRepository submissionRepository;
    @Mock EvaluationReportRepository evaluationReportRepository;
    @InjectMocks CoordinatorAnalyticsService service;

    @Test
    void buildsAnalyticsFromDatabaseAggregates() {
        when(projectRepository.countByStatusGrouped()).thenReturn(List.<Object[]>of(new Object[]{ProjectStatus.OPEN, 3L}));
        when(userRepository.countByRoleGrouped()).thenReturn(List.<Object[]>of(new Object[]{UserRole.STUDENT, 8L}));
        when(userRepository.countByStatusGrouped()).thenReturn(List.<Object[]>of(new Object[]{UserStatus.PENDING, 2L}));
        when(submissionRepository.countByStatusGrouped()).thenReturn(List.<Object[]>of(new Object[]{SubmissionStatus.EVALUATED, 1L}));
        when(partyRepository.count()).thenReturn(4L);
        when(partyRepository.countByAssignedProjectIsNotNull()).thenReturn(3L);
        when(evaluationReportRepository.count()).thenReturn(2L);
        when(evaluationReportRepository.countByFinalizedTrue()).thenReturn(1L);

        var analytics = service.getAnalytics();

        assertEquals(3L, analytics.getProjectsByStatus().get("OPEN"));
        assertEquals(0L, analytics.getProjectsByStatus().get("CLOSED"));
        assertEquals(8L, analytics.getUsersByRole().get("STUDENT"));
        assertEquals(1L, analytics.getUnassignedParties());
        assertEquals(1L, analytics.getDraftEvaluations());
    }
}
