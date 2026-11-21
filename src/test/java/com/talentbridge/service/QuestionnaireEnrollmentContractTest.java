package com.talentbridge.service;

import com.talentbridge.dto.request.FinalSubmissionRequest;
import com.talentbridge.entity.*;
import com.talentbridge.repository.*;
import com.talentbridge.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuestionnaireEnrollmentContractTest {
    @Test void draftAndFrozenInputsExposeOnlyTheRequiredVersionAndLabel() {
        Submission draft = Submission.builder().build();
        assertEquals(0, draft.getVersion());
        assertEquals(7L, new FinalSubmissionRequest(7L).expectedVersion());
        var snapshot = QuestionnaireSnapshot.builder().frozenLabel("Synthetic project").build();
        assertEquals("Synthetic project", snapshot.getFrozenLabel());
    }

    @Test void versionTwoCannotFallThroughAnyLegacyEvaluationMutation() {
        SubmissionRepository submissions = mock(); EvaluationReportRepository reports = mock(); UserRepository users = mock();
        GitHubService github = mock(); OpenAIService ai = mock(); ScorecardService scorecards = mock(); NotificationService notifications = mock();
        var service = new EvaluationService(submissions, reports, users, scorecards, github, ai, notifications);
        Submission submission = Submission.builder().assessmentVersion(2).build(); UUID id = UUID.randomUUID();
        when(submissions.findById(id)).thenReturn(Optional.of(submission));
        assertThrows(BadRequestException.class, () -> service.triggerEvaluation(id, UUID.randomUUID()));
        EvaluationReport report = EvaluationReport.builder().submission(submission).build();
        when(reports.findById(id)).thenReturn(Optional.of(report));
        assertThrows(BadRequestException.class, () -> service.reevaluate(id, UUID.randomUUID()));
        assertThrows(BadRequestException.class, () -> service.finalizeReport(id, UUID.randomUUID()));
        verifyNoInteractions(github, ai, scorecards, notifications, users);
        verify(submissions, never()).save(any()); verify(reports, never()).save(any());
    }
}
