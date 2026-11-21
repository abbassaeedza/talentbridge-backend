package com.talentbridge.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentbridge.config.SecurityConfig;
import com.talentbridge.entity.*;
import com.talentbridge.enums.*;
import com.talentbridge.repository.QuestionnaireAssignmentRepository;
import com.talentbridge.repository.UserRepository;
import com.talentbridge.security.JwtAuthenticationFilter;
import com.talentbridge.security.JwtTokenProvider;
import com.talentbridge.service.QuestionnaireService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.*;
import java.util.*;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(QuestionnaireController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, QuestionnaireService.class, QuestionnaireControllerTest.TimeConfig.class})
@TestPropertySource(properties = "app.frontend-url=http://localhost:3000")
class QuestionnaireControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockBean QuestionnaireAssignmentRepository assignments;
    @MockBean JwtTokenProvider tokens;
    @MockBean UserRepository users;
    final UUID owner = UUID.randomUUID();
    final UUID id = UUID.randomUUID();
    QuestionnaireAssignment assignment;

    @BeforeEach
    void fixture() {
        var submission = Submission.builder().build();
        submission.setId(UUID.randomUUID());
        var snapshot = QuestionnaireSnapshot.builder().submission(submission).state(QuestionnaireState.OPEN)
                .frozenBrief("PRIVATE BRIEF").opensAt(Instant.parse("2026-11-19T12:00:00Z"))
                .closesAt(Instant.parse("2026-11-21T12:00:00Z")).build();
        assignment = QuestionnaireAssignment.builder().snapshot(snapshot).studentId(owner).studentName("Frozen owner").build();
        assignment.setId(id);
        for (int i = 1; i <= 5; i++) {
            var q = QuestionnaireQuestion.builder().assignment(assignment).position(i).prompt("Question " + i)
                    .sourceReferences(mapper.createArrayNode()).hiddenRubric("HIDDEN RUBRIC").aiScore(20).build();
            q.setId(UUID.randomUUID());
            assignment.getQuestions().add(q);
        }
    }

    @Test
    void ownerDetailIsPrivateAndNeverReturnsRubricOrScore() throws Exception {
        authenticate(owner, UserRole.STUDENT, UserStatus.APPROVED);
        when(assignments.findByIdAndStudentId(id, owner)).thenReturn(Optional.of(assignment));
        mvc.perform(get("/api/questionnaires/{id}", id).header("Authorization", "Bearer token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.questions.length()").value(5))
                .andExpect(jsonPath("$.questions[0].prompt").value("Question 1"))
                .andExpect(jsonPath("$.questions[0].hiddenRubric").doesNotExist())
                .andExpect(jsonPath("$.questions[0].aiScore").doesNotExist())
                .andExpect(jsonPath("$.frozenBrief").doesNotExist());
    }

    @Test
    void listIsOwnerScopedAndContainsNoQuestionText() throws Exception {
        authenticate(owner, UserRole.STUDENT, UserStatus.APPROVED);
        when(assignments.findAllByStudentIdOrderByCreatedAtDesc(owner)).thenReturn(List.of(assignment));
        mvc.perform(get("/api/questionnaires/my").header("Authorization", "Bearer token"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].questions").doesNotExist());
    }

    @Test
    void authenticatedOtherStudentGets404() throws Exception {
        authenticate(UUID.randomUUID(), UserRole.STUDENT, UserStatus.APPROVED);
        mvc.perform(get("/api/questionnaires/{id}", id).header("Authorization", "Bearer token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anonymousPendingAndNonStudentsCannotUseOwnerRoutes() throws Exception {
        mvc.perform(get("/api/questionnaires/my")).andExpect(status().isUnauthorized());
        authenticate(owner, UserRole.STUDENT, UserStatus.PENDING);
        mvc.perform(get("/api/questionnaires/my").header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
        authenticate(owner, UserRole.COMPANY, UserStatus.APPROVED);
        mvc.perform(get("/api/questionnaires/my").header("Authorization", "Bearer token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void savesAllFiveDraftEntriesThroughHttp() throws Exception {
        authenticate(owner, UserRole.STUDENT, UserStatus.APPROVED);
        when(assignments.findOwnedForUpdate(id, owner)).thenReturn(Optional.of(assignment));
        mvc.perform(put("/api/questionnaires/{id}/answers", id).header("Authorization", "Bearer token")
                        .contentType("application/json").content(body("my draft", 0)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.answeredCount").value(5)).andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    void staleRevisionAndExactDeadlineReturn409() throws Exception {
        authenticate(owner, UserRole.STUDENT, UserStatus.APPROVED);
        when(assignments.findOwnedForUpdate(id, owner)).thenReturn(Optional.of(assignment));
        assignment.setRevision(3);
        mvc.perform(put("/api/questionnaires/{id}/answers", id).header("Authorization", "Bearer token")
                        .contentType("application/json").content(body("stale", 2)))
                .andExpect(status().isConflict());
        assignment.getSnapshot().setClosesAt(Instant.parse("2026-11-20T12:00:00Z"));
        mvc.perform(put("/api/questionnaires/{id}/answers", id).header("Authorization", "Bearer token")
                        .contentType("application/json").content(body("late", 3)))
                .andExpect(status().isConflict());
    }

    @Test
    void malformedIncompleteAndOversizedBodiesReturn400() throws Exception {
        authenticate(owner, UserRole.STUDENT, UserStatus.APPROVED);
        for (String body : List.of("{}", "{", "{\"expectedRevision\":0,\"answers\":[]}", body("a".repeat(2001), 0))) {
            mvc.perform(put("/api/questionnaires/{id}/answers", id).header("Authorization", "Bearer token")
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void unsupportedPlainTextControlsAreRejectedThroughHttpBeforeMutation() throws Exception {
        authenticate(owner, UserRole.STUDENT, UserStatus.APPROVED);
        when(assignments.findOwnedForUpdate(id, owner)).thenReturn(Optional.of(assignment));
        for (char control : new char[]{1, 8, 11, 12, 14, 31}) {
            mvc.perform(put("/api/questionnaires/{id}/answers", id).header("Authorization", "Bearer token")
                    .contentType("application/json").content(body("Saved code" + control, 0)))
                    .andExpect(status().isBadRequest());
            org.junit.jupiter.api.Assertions.assertTrue(assignment.getAnswers().isEmpty());
        }
        verify(assignments, never()).saveAndFlush(any());
    }

    private String body(String text, long revision) throws Exception {
        return mapper.writeValueAsString(Map.of("expectedRevision", revision, "answers", assignment.getQuestions().stream()
                .map(q -> Map.of("questionId", q.getId(), "text", text)).toList()));
    }

    private void authenticate(UUID userId, UserRole role, UserStatus status) {
        User user = User.builder().role(role).status(status).build();
        user.setId(userId);
        when(tokens.parseAccessToken("token")).thenReturn(new JwtTokenProvider.TokenIdentity(userId, 0));
        when(users.findById(userId)).thenReturn(Optional.of(user));
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC); }
    }
}
