package com.talentbridge.controller;

import com.talentbridge.config.*;
import com.talentbridge.entity.User;
import com.talentbridge.enums.*;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.security.*;
import com.talentbridge.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={QuestionnaireEvaluationController.class,EvaluationController.class,AssessmentJobController.class})
@Import({SecurityConfig.class,AssessmentSecurityConfig.class,JwtAuthenticationFilter.class})
@TestPropertySource(properties="app.frontend-url=http://localhost:3000")
class QuestionnaireEvaluationControllerTest {
    @Autowired MockMvc mvc;
    @MockBean QuestionnaireEvaluationService service;
    @MockBean EvaluationService legacy;
    @MockBean QuestionnaireGenerationService generation;
    @MockBean AssessmentOidcVerifier google;
    @MockBean JwtTokenProvider tokens;
    @MockBean UserRepository users;
    UUID actor=UUID.randomUUID(),submission=UUID.randomUUID(),question=UUID.randomUUID(),run=UUID.randomUUID();

    void authenticate(UserRole role) {
        var user=User.builder().role(role).status(UserStatus.APPROVED).build();user.setId(actor);
        when(tokens.parseAccessToken("app")).thenReturn(new JwtTokenProvider.TokenIdentity(actor,0));
        when(users.findById(actor)).thenReturn(Optional.of(user));
    }

    @Test void coordinatorDiscoveryAndPollingAreNoStoreAndSideEffectFree() throws Exception {
        authenticate(UserRole.COORDINATOR);when(service.coordinatorSubmissions()).thenReturn(List.of());
        mvc.perform(get("/api/submissions").header("Authorization","Bearer app"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(content().json("[]"));
        mvc.perform(get("/api/evaluations/runs/{id}",run).header("Authorization","Bearer app"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(service).coordinatorSubmissions();verify(service).run(run);verifyNoMoreInteractions(service);
        verifyNoInteractions(legacy,generation);
    }

    @Test void anonymousAndNonCoordinatorsCannotDiscoverRunsOrPrivateReview() throws Exception {
        for(String path:List.of("/api/submissions","/api/evaluations/runs/"+run,"/api/evaluations/submission/"+submission+"/questionnaire")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            for(var role:List.of(UserRole.STUDENT,UserRole.COMPANY,UserRole.PARTY_SUPERVISOR,UserRole.PROJECT_SUPERVISOR)) {
                authenticate(role);mvc.perform(get(path).header("Authorization","Bearer app")).andExpect(status().isForbidden());
            }
        }
        verifyNoInteractions(service,legacy,generation);
    }

    @Test void newOverrideHttpBoundaryRejectsFractionStringNullOverflowAndOutOfRangeBeforeMutation() throws Exception {
        authenticate(UserRole.COORDINATOR);
        for(String mark:List.of("1.5","20.0","\"12\"","null","2147483648","9999999999999999999999999999","-1","21")) {
            mvc.perform(put("/api/evaluations/submission/{s}/questions/{q}/mark",submission,question)
                    .header("Authorization","Bearer app").contentType("application/json").content("{\"mark\":"+mark+",\"reason\":\"Bounded evidence\"}"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
        mvc.perform(put("/api/evaluations/submission/{s}/questions/{q}/mark",submission,question)
                .header("Authorization","Bearer app").contentType("application/json").content("{\"mark\":0,\"reason\":\"Valid reason اردو\"}"))
                .andExpect(status().isOk());
        verify(service).adjust(submission,question,actor,0,"Valid reason اردو");
    }

    @Test void approvedNonCoordinatorsCannotPutPrivateMarks() throws Exception {
        for(var role:List.of(UserRole.STUDENT,UserRole.COMPANY,UserRole.PARTY_SUPERVISOR,UserRole.PROJECT_SUPERVISOR)) {
            authenticate(role);
            mvc.perform(put("/api/evaluations/submission/{s}/questions/{q}/mark",submission,question)
                    .header("Authorization","Bearer app").contentType("application/json").content("{\"mark\":20,\"reason\":\"Must not mutate\"}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service,legacy,generation);
    }

    @Test void versionTwoTriggerIs202AndVersionOneRetainsSynchronous200() throws Exception {
        authenticate(UserRole.COORDINATOR);when(service.versionTwoSubmission(submission)).thenReturn(true);
        mvc.perform(post("/api/evaluations/trigger/{s}",submission).header("Authorization","Bearer app"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control","no-store"));
        verify(service).start(submission,actor);verifyNoInteractions(legacy);
        when(service.versionTwoSubmission(submission)).thenReturn(false);
        mvc.perform(post("/api/evaluations/trigger/{s}",submission).header("Authorization","Bearer app")).andExpect(status().isOk());
        verify(legacy).triggerEvaluation(submission,actor);
    }

    @Test void actualOidcOnlyInternalControllerRoutesEvaluationBeforeGenerationShortcut() throws Exception {
        UUID job=UUID.randomUUID();when(service.handles(job)).thenReturn(true);
        mvc.perform(post("/internal/assessment-jobs/{j}",job).header("Authorization","Bearer app").contentType("application/json").content("{\"jobId\":\""+job+"\"}"))
                .andExpect(status().isUnauthorized());
        when(google.verify("google")).thenReturn(true);
        mvc.perform(post("/internal/assessment-jobs/{j}",job).header("Authorization","Bearer google").contentType("application/json").content("{\"jobId\":\""+UUID.randomUUID()+"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/assessment-jobs/{j}",job).header("Authorization","Bearer google").contentType("application/json").content("{\"jobId\":\""+job+"\"}"))
                .andExpect(status().isNoContent());
        verify(service).handles(job);verify(service).handle(job);verifyNoInteractions(generation);
        UUID generationJob=UUID.randomUUID();
        mvc.perform(post("/internal/assessment-jobs/{j}",generationJob).header("Authorization","Bearer google").contentType("application/json").content("{\"jobId\":\""+generationJob+"\"}"))
                .andExpect(status().isNoContent());
        verify(generation).handle(generationJob);
    }

    @Test void ownerFinalizedReportRouteUsesFrozenAssignmentAndRejectsOtherRoles() throws Exception {
        UUID assignment=UUID.randomUUID();authenticate(UserRole.STUDENT);
        when(service.ownerReport(assignment,actor)).thenReturn(Optional.empty());
        mvc.perform(get("/api/questionnaires/{id}/report",assignment).header("Authorization","Bearer app")).andExpect(status().isNotFound());
        verify(service).ownerReport(assignment,actor);
        authenticate(UserRole.COMPANY);
        mvc.perform(get("/api/questionnaires/{id}/report",assignment).header("Authorization","Bearer app")).andExpect(status().isForbidden());
    }
}
