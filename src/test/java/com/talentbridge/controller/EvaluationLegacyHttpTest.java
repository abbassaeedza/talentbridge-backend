package com.talentbridge.controller;

import com.talentbridge.entity.*;
import com.talentbridge.enums.SubmissionStatus;
import com.talentbridge.exception.GlobalExceptionHandler;
import com.talentbridge.repository.*;
import com.talentbridge.service.*;
import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class EvaluationLegacyHttpTest {
    @Test void draftCannotInvokeLegacyEvaluationThroughHttp() throws Exception {
        SubmissionRepository submissions=mock(); EvaluationReportRepository reports=mock(); UserRepository users=mock();
        GitHubService github=mock(); OpenAIService ai=mock(); ScorecardService scorecards=mock(); NotificationService notifications=mock();
        UUID id=UUID.randomUUID();
        when(submissions.findById(id)).thenReturn(Optional.of(Submission.builder().status(SubmissionStatus.DRAFT)
                .repoUrl("https://github.com/synthetic/repository").party(Party.builder().leader(User.builder().build()).build())
                .project(Project.builder().build()).build()));
        when(users.findById(any())).thenReturn(Optional.of(User.builder().build()));
        var service=new EvaluationService(submissions,reports,users,scorecards,github,ai,notifications);
        var mvc=standaloneSetup(new EvaluationController(service,mock(QuestionnaireEvaluationService.class))).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/api/evaluations/trigger/{id}",id)).andExpect(status().isBadRequest());
        verifyNoInteractions(github,ai,scorecards,notifications);
        verify(submissions,never()).save(any());
    }

    @Test void malformedProviderBodyCannotEscapeInThrownOrLoggedCauses() {
        SubmissionRepository submissions=mock(); EvaluationReportRepository reports=mock(); UserRepository users=mock();
        GitHubService github=mock(); OpenAIService ai=mock(); ScorecardService scorecards=mock(); NotificationService notifications=mock();
        UUID id=UUID.randomUUID(), actor=UUID.randomUUID();
        when(submissions.findById(id)).thenReturn(Optional.of(Submission.builder().status(SubmissionStatus.SUBMITTED)
                .repoUrl("https://github.com/synthetic/repository").party(Party.builder().leader(User.builder().build()).build())
                .project(Project.builder().build()).build()));
        when(users.findById(actor)).thenReturn(Optional.of(User.builder().build()));
        when(github.fetchContributorData(anyString(),any())).thenReturn(new GitHubService.ContributorData(List.of(),Map.of()));
        String sentinel="PRIVATE_RESPONSE_SENTINEL";
        when(ai.evaluateRepository(any(),any(),any(),any())).thenReturn("{\""+sentinel+"\": ");
        var logger=(Logger)LoggerFactory.getLogger(EvaluationService.class);
        var logs=new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            var service=new EvaluationService(submissions,reports,users,scorecards,github,ai,notifications);
            var thrown=assertThrows(RuntimeException.class,()->service.triggerEvaluation(id,actor));
            assertEquals("EVALUATION_RESPONSE_INVALID",thrown.getMessage()); assertNull(thrown.getCause());
            assertTrue(logs.list.stream().allMatch(event -> !event.getFormattedMessage().contains(sentinel)
                    && event.getThrowableProxy()==null));
        } finally { logger.detachAppender(logs); }
    }
}
